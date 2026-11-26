//
//  MessageManager.swift
//  OSHI
//
//  Created by MORICEAU on 01/11/2025.
//  FIXED: Mesh reconnection detection and routing
//

import Foundation
import Combine
import CryptoKit
import UIKit

// MARK: - Ratchet Error Types

enum RatchetError: Error {
    case messageSkipped
    case tooManySkippedMessages
    case authenticationFailed
    case invalidMessage
}

/// Tiny ref-typed holder for a UIBackgroundTaskIdentifier so the iOS expiration
/// handler and the post-send defer can both safely call `end()` without
/// double-ending (which iOS treats as a fatal misuse).
/// @unchecked Sendable: `end()` is lock-guarded; `id` is assigned once right
/// after acquisition, before the holder crosses any concurrency boundary.
final class SendBackgroundTaskHolder: @unchecked Sendable {
    private let lock = NSLock()
    var id: UIBackgroundTaskIdentifier = .invalid

    func end() {
        lock.lock()
        let toEnd = id
        id = .invalid
        lock.unlock()
        if toEnd != .invalid {
            UIApplication.shared.endBackgroundTask(toEnd)
            OshiLog.messaging.info("🔋 OshiSend background task ended: id=\(toEnd.rawValue)")
        }
    }
}

/// Minimal async counting semaphore. Swift has no built-in awaitable semaphore,
/// so we back one with an actor + continuations. Used to bound how many media
/// uploads run their heavy in-memory work (base64 + AES-GCM + IPFS envelope) at
/// once — sending several uncompressed videos concurrently overran iOS's per-app
/// memory limit and got the app Jetsam-killed.
actor AsyncSemaphore {
    private var permits: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(value: Int) { self.permits = value }

    func wait() async {
        if permits > 0 {
            permits -= 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func signal() {
        if waiters.isEmpty {
            permits += 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}

/// Serializes media uploads to ONE at a time (bounding peak memory) AND serves
/// them strictly in *ticket* order, so a batch of attachments uploads in the
/// visible top→bottom order the user picked — not in `Task.detached` scheduling
/// order, which raced and uploaded the bottom (last-tapped) item first.
///
/// Tickets come from `MessageManager.nextMediaUploadTicket()`, assigned
/// synchronously at send-tap time (the send loop calls `sendMessage` top→bottom),
/// so the order is fixed before the racing background tasks ever start.
actor OrderedUploadGate {
    private var active = false
    private var nextToServe: UInt64 = 1
    private var waiters: [UInt64: CheckedContinuation<Void, Never>] = [:]

    func wait(ticket: UInt64) async {
        // It's my turn only if nothing is uploading and every earlier ticket has
        // already been served. Otherwise park until signal() reaches my number.
        if !active && ticket == nextToServe {
            active = true
            return
        }
        await withCheckedContinuation { waiters[ticket] = $0 }
    }

    func signal() {
        active = false
        nextToServe &+= 1
        if let cont = waiters.removeValue(forKey: nextToServe) {
            active = true
            cont.resume()
        }
    }
}

class MessageManager: ObservableObject {
    /// Serializes the memory-heavy media-send path (one upload's base64+encrypt+
    /// envelope at a time) so multi-attachment sends can't blow the memory ceiling,
    /// and uploads them in the user's top→bottom order. See `OrderedUploadGate`.
    static let mediaUploadGate = OrderedUploadGate()

    /// Monotonic media-upload ticket, handed out synchronously at send-tap time so
    /// a multi-media batch uploads in the order the user selected (top→bottom)
    /// instead of in background-task scheduling order. See `OrderedUploadGate`.
    private static let mediaTicketLock = NSLock()
    private static var mediaTicketCounter: UInt64 = 0
    static func nextMediaUploadTicket() -> UInt64 {
        mediaTicketLock.lock(); defer { mediaTicketLock.unlock() }
        mediaTicketCounter &+= 1
        return mediaTicketCounter
    }

    @Published var conversations: [Conversation] = []
    @Published var messages: [SecureMessage] = []
    @Published var pinnedMessages: [String: String] = [:]  // [conversationKey: messageId]
    /// Pending conversations created via QR scan before any message is exchanged.
    /// These survive updateConversations() rebuilds until a real message exists.
    private var pendingConversations: [String: Conversation] = [:]
    /// __MESHCORE_INTEROP_2026_11_25__ Tests drive a radio without Bluetooth through this.
    var meshCoreRadioOverride: MeshCoreManager?
    private var ipfsCache: [String: SecureMessage] = [:]
    private var ipfsCacheTimestamps: [String: Date] = [:]
    private let ipfsCacheTimeout: TimeInterval = 300 // 5 minutes
    private var isPollingInProgress = false

    /// 🔧 FIX: Per-IPFS-hash count of poll fetches that ended in a decrypt
    /// failure (message parked in `pendingDecryption`, not stored). We keep such
    /// messages on the VPS queue so a later poll can re-fetch them once the
    /// Double-Ratchet re-syncs — instead of deleting them on first fetch (which
    /// left the recipient with a push banner but no message). After
    /// `maxUndecryptableFetches` we purge to avoid an infinite re-fetch loop on a
    /// genuinely-undecryptable envelope. Accessed only inside the serialized poll
    /// loop (guarded by `isPollingInProgress`), so no extra locking is needed.
    private var undecryptableFetchCount: [String: Int] = [:]
    private let maxUndecryptableFetches = 8

    /// 🔧 LAG FIX: bot messageIds already handled this session. A bot message with
    /// inline media (e.g. a 600 KB photo) can't be ack'd via the URL-path DELETE
    /// (the ~800 KB envelope overflows the request URL → it fails), so the server
    /// keeps re-returning it every 2 s poll. Re-base64-decoding that payload +
    /// media on every poll was the lag. Once we've handled a messageId we skip the
    /// whole decode. Accessed only inside the serialized poll loop.
    private var seenBotMessageIds = Set<String>()

    /// 🔧 PERF (2026-06-02): Counts poll ticks so the percent-encoded queue-key
    /// fallback only runs every Nth tick (every ~30 s with the 2 s timer), not
    /// every poll. The base64url form is the server's canonical encoding; the
    /// fallback is a safety net for any legacy bot messages queued under the
    /// other form. Polling it every tick wasted ~1 800 HTTP requests/hour
    /// (constant 404s) keeping the cellular radio awake. UInt8 wraps naturally;
    /// `% 15 == 0` fires on every 15th tick so we hit the fallback ~every 30 s.
    private var pollFallbackCounter: UInt8 = 0

    /// Evict old cache entries to prevent memory bloat
    private func evictOldCacheEntries() {
        if ipfsCache.count > 50 {
            let sortedKeys = ipfsCacheTimestamps.sorted { $0.value < $1.value }.prefix(ipfsCache.count - 30).map { $0.key }
            for key in sortedKeys {
                ipfsCache.removeValue(forKey: key)
                ipfsCacheTimestamps.removeValue(forKey: key)
            }
        }
    }
    
    // MARK: - Upload Progress Tracking
    @Published var uploadProgress: [String: Double] = [:]  // messageId -> progress (0.0 to 1.0)
    @Published var uploadStatus: [String: UploadStatus] = [:]  // messageId -> status
    
    enum UploadStatus: Equatable {
        case preparing
        case uploading(progress: Double)
        case processing
        case completed
        case failed(message: String)
        
        var displayText: String {
            switch self {
            case .preparing: return "upload.status.preparing".localized
            case .uploading(let progress): return String(format: "upload.status.uploading".localized, Int(progress * 100))
            case .processing: return "upload.status.processing".localized
            case .completed: return "upload.status.completed".localized
            case .failed(let message): return message
            }
        }
    }
    
    // MARK: - VPS Server Configuration
    // ✅ Direct VPS connection (no Tor) - simpler and lower latency
    // Port 80 = nginx reverse proxy (routes to internal services)
    // Internal ports (behind firewall): 8081=messages, 8082=upload, 8083=VoIP, 8084=push
    private let vpsPublicURL = "https://oshi-messenger.com"

    private var currentVPSURL: String {
        return vpsPublicURL
    }
    
    // MARK: - Base64URL Encoding Helper
    // 🔧 CRITICAL FIX: Use base64url encoding for public keys in URLs
    // Standard base64 uses +/= which break URL paths even when percent-encoded
    // Base64url uses -_ instead and is URL-safe
    private func base64urlEncode(_ base64String: String) -> String {
        return base64String
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    
    // Decode base64url back to standard base64
    static func base64urlDecode(_ base64urlString: String) -> String {
        var result = base64urlString
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        // Add padding if needed
        let remainder = result.count % 4
        if remainder > 0 {
            result += String(repeating: "=", count: 4 - remainder)
        }
        return result
    }
    
    // 🔐 IPFS via VPS Proxy - hides user IP from IPFS gateways
    // VPS fetches from gateways on behalf of user, then returns content
    // nginx routes: /api/ipfs/proxy/ → port 8086 (IPFS fetch proxy)
    //               /api/pinata/ → port 8082 (Pinata API for uploads)
    private let vpsIPFSProxyURL = "https://oshi-messenger.com/api/ipfs/proxy/"

    // ⚠️ 2026-07-17: the public-IPFS-gateway fallback list was REMOVED. Since the
    // Pinata key was revoked (2026-07-16) blobs are stored only in `/var/oshi_ipfs`
    // on our own VPS and are NEVER published to the IPFS DHT, so gateways like
    // gateway.pinata.cloud / ipfs.io / dweb.link / w3s.link / nftstorage.link
    // could not resolve our CIDs even in principle. Verified live: all five 404 or
    // time out. Keeping them cost ~30 s of dead timeouts before failing AND leaked
    // the CID + the user's IP to third parties. `/api/ipfs/proxy/` is the only path
    // that can serve our blobs; if it fails, fail fast.

    // 🔒 Privacy setting - always use VPS proxy (default: true for IP protection)
    private let useVPSProxyForIPFS = true
    
    private var cancellables = Set<AnyCancellable>()
    /// 🔧 LAUNCH-FREEZE FIX: `lazy`, not a stored `let`. A stored property forced
    /// `DoubleRatchetSessionManager.init()` — whole-blob `SecItemCopyMatching` +
    /// a JSON decode of every ratchet session — to run inside `@StateObject`
    /// construction on the main thread, before the first frame. Nothing in the
    /// launch path needs a ratchet; the first send/receive builds it instead.
    private lazy var ratchetManager = DoubleRatchetSessionManager.shared
    private var meshReconnectionTimer: Timer?
    private var ipfsPollingTimer: Timer?
    private var syncPollCount = 0  // Track sync cycles during polling
    private var meshAvailabilityTimer: Timer?  // 🔧 NEW: Fast mesh status checker
    private var pendingMeshMessages: [(message: SecureMessage, recipient: String)] = []
    static var sharedMeshManager: MeshNetworkManager?
    
    // ✅ FIX: Strong reference — must survive background mode for APNs push → checkPendingIPFSMessages()
    private static var _shared: MessageManager?
    static var shared: MessageManager? {
        get { _shared }
        set { _shared = newValue }
    }
    static var sharedIdentityManager: IdentityManager?
    
    private let maxMessagesInMemory = 30
    
    // Out-of-order message handling
    //
    // __PENDING_RACE_2026_10_20__ 🔴 CRASH (EXC_BAD_ACCESS at 0x10, 1.0.46/181,
    // iPhone 18,2, iOS 26.7). This dictionary was plain stored state, and three
    // comments in this file state the invariant it relied on: "les appelants sont
    // sur le thread principal (`receiveMessage` y est confine)". That invariant
    // is FALSE, and the crash report says exactly where it broke:
    //
    //   main thread          NSTimer -> retryAllPendingDecryption(force:)
    //                        -> tryDecryptPendingMessages -> withdrawPhantomBanners
    //                        -> publishUndecryptableSnapshot  ** ITERATING **
    //
    //   cooperative pool     Task { v2PullTick } -> v2PullAndProcess
    //                        -> processLegacyIPFSData -> decryptMessage
    //                        -> handleSessionDesync -> queueForDecryption
    //                        -> savePendingDecryption         ** MUTATING **
    //
    // The V2 pull runs in an async Task and is NOT confined to main, so a writer
    // on the cooperative pool mutated the dictionary while the main thread walked
    // it. The faulting frame is `__CocoaDictionary.Iterator.nextKey()` ->
    // `objc_msgSend`, which is misleading and worth naming: nothing here is ever
    // bridged to Objective-C. That frame IS the corruption. Swift reads the
    // native/Cocoa variant discriminator out of the dictionary's storage; when a
    // concurrent mutation has freed and reused that allocation, the discriminator
    // comes back garbage, Swift takes the Cocoa branch, and objc_msgSend is sent
    // to a non-object — 0x10. Chasing a bridging bug would have found nothing.
    //
    // So access is serialised instead of assumed. The lock is RECURSIVE because
    // these functions re-enter each other: `queueForDecryption` is in the middle
    // of a read-modify-write when it calls `withdrawPhantomBanners`, which reads
    // the dictionary again through `publishUndecryptableSnapshot`. A plain
    // NSLock would turn this crash into a main-thread deadlock, which is worse —
    // it would be a hang with no report.
    //
    // ⚠️ Every access goes through the computed property. The storage below is
    // `_pendingDecryption` and must not be touched directly; that is the whole
    // point, and it keeps all 24 existing call sites unchanged.
    private var _pendingDecryption: [String: [SecureMessage]] = [:]
    private let pendingDecryptionLock = NSRecursiveLock()
    private var pendingDecryption: [String: [SecureMessage]] {
        get {
            pendingDecryptionLock.lock()
            defer { pendingDecryptionLock.unlock() }
            return _pendingDecryption
        }
        set {
            pendingDecryptionLock.lock()
            defer { pendingDecryptionLock.unlock() }
            _pendingDecryption = newValue
        }
    }
    private let maxPendingPerSender = 50
    private var decryptionRetryTimer: Timer?
    
    // Track failed decryption attempts per sender
    private var failedDecryptionCount: [String: Int] = [:]
    private let maxFailedDecryptions = 5

    // __PHANTOM_BANNER_2026_10_11__ Quand retenter le dechiffrement d'un pair.
    //
    // SIGNALE (avis App Store 10/10, 5★, FR): « manque de fluidite a travers
    // l'interface et les notifications excessives alors que je n'ai rien recu ».
    // Les DEUX symptomes sortent d'ici, et c'est la meme cause.
    //
    // `failedDecryptionCount` et `maxFailedDecryptions` existaient depuis le
    // debut — declares, remis a zero en cas de succes… et JAMAIS incrementes ni
    // lus. Le compteur etait mort, donc le plafond n'a jamais existe:
    // `decryptionRetryTimer` (5 s, RunLoop.main) rejouait
    // `decryptMessage` -> `getOrCreateSession` -> `queue.sync` sur la file du
    // ratchet, plus un aller-retour Keychain, pour CHAQUE message gare — toutes
    // les cinq secondes, indefiniment, des qu'un seul message restait
    // indechiffrable. D'ou l'interface qui accroche periodiquement sans raison
    // visible: ce n'est pas le rendu, c'est le thread principal qui attend le
    // ratchet. Et comme la banniere APNs est dessinee par l'extension AVANT que
    // quiconque sache s'il y a un message derriere (elle ne dechiffre pas — c'est
    // delibere, dechiffrer la desynchroniserait), l'utilisateur a bien eu la
    // notification et jamais le message.
    //
    // On ne RENONCE pas: un desync se repare (re-handshake automatique,
    // `RatchetHealSignal`), et abandonner perdrait un vrai message. On espace.
    // Les retentatives EVENEMENTIELLES (reveil APNs, retour au premier plan,
    // ouverture du fil) passent `force: true` et ignorent l'attente, donc la
    // latence percue quand il se passe vraiment quelque chose ne change pas.
    private var nextDecryptionRetry: [String: Date] = [:]
    /// Plafond de l'attente entre deux balayages pour un meme pair.
    private let maxDecryptionRetryInterval: TimeInterval = 300

    /// 5 s, 10, 20, 40, 80, 160, puis 300 au plafond.
    private func decryptionRetryDelay(afterFailures n: Int) -> TimeInterval {
        min(TimeInterval(5 << min(max(n, 0), 6)), maxDecryptionRetryInterval)
    }

    // 🔧 NEW: Store envelopes from unknown senders for later decryption
    // Key is sender hint (first 8 chars), value is array of (envelope, ipfsHash)
    private var unknownSenderEnvelopes: [String: [(envelope: EncryptedEnvelope, ipfsHash: String)]] = [:]
    private let maxUnknownEnvelopesPerHint = 20

    // 📱 MULTI-DEVICE: Store envelopes that failed group decryption for retry after group sync
    private var pendingGroupEnvelopes: [(envelope: EncryptedEnvelope, dict: [String: Any], ipfsHash: String)] = []
    private var _lastGroupRecoverySyncAt: Date = .distantPast
    private let maxPendingGroupEnvelopes = 200
    
    // 🔧 IMPROVED: Enhanced mesh availability tracking
    @Published var isMeshAvailable: Bool = false
    private var lastMeshCheck: Date = .distantPast
    private let meshCheckInterval: TimeInterval = 2.0  // Check every 2 seconds

    // 📶 Network Reachability & Message Retry Queue
    @Published var isNetworkAvailable: Bool = true
    // `internal` (not `private`) purely so `@testable import OSHI` can observe the
    // drain: `@testable` widens internal, never private. Inert at runtime — nothing
    // outside MessageManager writes these. See OSHITests/MessageRetryQueueTests.swift.
    var messageRetryQueue: [RetryableMessage] = []
    var isRetrying: Bool = false
    let maxRetryAttempts = 3

    /// __RETRY_CAP_IS_PER_MESSAGE_2026_09_12__ Ids that already burned
    /// `maxRetryAttempts` *with connectivity up*.
    ///
    /// Without this the cap was per-CYCLE, not per-message: `retryFailedMessages`
    /// dropped the exhausted entry from the queue, but the message itself stayed
    /// `.failed`, so the very next foreground/reachability sweep in
    /// `enqueueFailedOutgoingForRetry` re-queued it through `addToRetryQueue`,
    /// which builds a fresh `RetryableMessage` with `retryCount` back at 0. An
    /// undeliverable message was therefore re-driven in bursts of three on every
    /// foreground for a full 24 h.
    ///
    /// Process-local on purpose: `retryFailedMessages` only runs when
    /// `isNetworkAvailable`, so landing here means three genuine failures against
    /// a live network — but a relaunch is a fair reason to give it another go.
    /// `internal` for `@testable` observation only (see `messageRetryQueue`).
    var permanentlyFailedMessageIds = Set<String>()

    /// __COVER_RETRY_2026_11_18__ Per-message disguised-retry schedules (cover mode
    /// only; process-local — a relaunch starts a fresh schedule from the queue entry's
    /// `createdAt`, so the 1 h limit still counts from the first failure).
    var coverRetrySchedules: [String: CoverRetrySchedule] = [:]
    /// Set when a disguised request succeeded since the last tick (see `coverRetryKick`).
    var coverRetryKicked = false
    private var coverRetryTimer: Timer?

    struct RetryableMessage: Codable {
        let messageId: String
        let content: String
        let recipientKey: String
        /// 🔧 LAUNCH-FREEZE FIX: NOT persisted (see CodingKeys). The retry itself
        /// re-reads the live `SecureMessage` (`retrySendViaCloud`), so these bytes
        /// were only ever copied from one RetryableMessage into the next — while
        /// making `message_retry_queue.json` grow by the full size of every failed
        /// media send, and that file is decoded synchronously on the main thread
        /// at launch. `var` + default so the synthesized decoder can omit it.
        var mediaData: Data? = nil
        let mediaType: MediaManager.MediaType?
        let mediaFileName: String?
        var retryCount: Int
        let createdAt: Date

        /// `mediaData` is deliberately absent: excluded from BOTH encode and decode.
        enum CodingKeys: String, CodingKey {
            case messageId, content, recipientKey, mediaType, mediaFileName, retryCount, createdAt
        }

        init(messageId: String, content: String, recipientKey: String, mediaData: Data? = nil, mediaType: MediaManager.MediaType? = nil, mediaFileName: String? = nil, retryCount: Int = 0, createdAt: Date = Date()) {
            self.messageId = messageId
            self.content = content
            self.recipientKey = recipientKey
            self.mediaData = mediaData
            self.mediaType = mediaType
            self.mediaFileName = mediaFileName
            self.retryCount = retryCount
            self.createdAt = createdAt
        }
    }
    
    init() {
        LaunchTimingLogger.mark("MessageManager.init.start")
        LaunchTimingLogger.measure("MessageManager.loadMessages") { loadMessages() }
        LaunchTimingLogger.measure("MessageManager.loadPinnedMessages") { loadPinnedMessages() }
        // 🔧 LAUNCH-FREEZE FIX: the retry queue and the pending-decryption queue are
        // both media-bearing JSON blobs read with Data(contentsOf:) + JSONDecoder.
        // Doing that here ran it on the main thread INSIDE @StateObject construction,
        // i.e. before the first frame — the more files a user had exchanged, the
        // longer the splash hung. Neither store is needed to draw that frame: the
        // retry queue is drained by the reachability observer, the pending queue by
        // the 30 s decryption retry timer. Read off-main, publish back.
        loadDeferredQueues()
        LaunchTimingLogger.measure("MessageManager.setupMessageListener") { setupMessageListener() }
        // __REVEAL_OUTBOX_2026_08_22__ Au lancement aussi: l'app a pu rester
        // fermée plus longtemps qu'une échéance, et c'est le cas NORMAL pour une
        // révélation de 48 h.
        drainRevealOutbox()
        LaunchTimingLogger.measure("MessageManager.cleanupUserDefaults") { cleanupUserDefaults() }
        LaunchTimingLogger.mark("MessageManager.init.end")
        
        DispatchQueue.main.async {
            MessageManager.shared = self
        }
        
        // 🔧 CRITICAL FIX: Delay polling start to allow identity manager to be connected
        // The app sets sharedIdentityManager in initializeApp() which runs after init()
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            self?.startIPFSPolling()
            self?.startMeshAvailabilityMonitor()
            self?.startMeshReconnectionMonitor()
            self?.startPendingDecryptionRetry()
            OshiLog.messaging.info("✅ MessageManager: All polling services started (after identity setup)")
        }
        
        setupMeshNetworkObservers()
        setupBlockedContactObservers()  // ✅ NEW: Listen for block/unblock events
        setupCallSignalObservers()      // 📞 NEW: Listen for call signal requests
        setupProfileUpdateObservers()   // 📸 NEW: Listen for profile picture broadcasts
        setupNetworkReachabilityObservers()  // 📶 NEW: Listen for network changes
        setupDeliveryConfirmationObservers() // 📬 NEW: Listen for delivery confirmations
        setupCovertChannelObservers()   // 🕵️ NEW: Listen for covert channel messages
    }

    // MARK: - Delivery Confirmation Handling

    private func setupDeliveryConfirmationObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleDeliveryConfirmed),
            name: NSNotification.Name("MessageDeliveryConfirmed"),
            object: nil
        )
        OshiLog.messaging.info("📬 Delivery confirmation observer set up")
    }

    @objc private func handleDeliveryConfirmed(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let messageId = userInfo["messageId"] as? String else {
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self,
                  let me = MessageManager.sharedIdentityManager?.publicKey,
                  let index = self.messages.firstIndex(where: { $0.id == messageId })
            else { return }

            // Only advance messages *I* sent. `MessageDeliveryConfirmed` is
            // overloaded: the RECEIVER also posts it (on storing an inbound IPFS
            // message, to drive IPFS unpin cleanup) with the inbound message's
            // id — without this guard that would wrongly mark a received message
            // as "delivered".
            guard self.messages[index].senderPublicKey == me else { return }

            // Monotonic upgrade: pending/sent → delivered. Never downgrade a
            // delivered message, never resurrect a failed one. This is the M3
            // fix — accurate per-message receipts (which carry the exact
            // messageId) previously did nothing because only `.pending` was
            // upgraded, while sent messages are created as `.sent`.
            let status = self.messages[index].deliveryStatus
            let advanced = status.advanced(to: .delivered)
            if advanced != status {
                self.messages[index].deliveryStatus = advanced
                self.saveMessages()
                OshiLog.messaging.info("📬 Message \(messageId.prefix(8))… → DELIVERED (was \(status.rawValue))")
            }
        }
    }

    // MARK: - Covert Channel Receive Handling

    private func setupCovertChannelObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCovertMessageReceived(_:)),
            name: NSNotification.Name("CovertMessageReceived"),
            object: nil
        )
        OshiLog.messaging.info("🕵️ Covert channel observer set up")
    }

    @objc private func handleCovertMessageReceived(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let data = userInfo["data"] as? Data,
              let senderPublicKey = userInfo["senderPublicKey"] as? String else {
            OshiLog.messaging.info("⚠️ CovertMessageReceived: Invalid notification format")
            return
        }

        #if DEBUG
        OshiLog.messaging.info("🕵️ Processing covert message from \(senderPublicKey.prefix(8))...")
        #endif

        // Try to decode as EncryptedMessage (what sendViaCovertChannel encodes)
        if let encryptedContent = try? JSONDecoder().decode(EncryptedMessage.self, from: data) {
            // Reconstruct a SecureMessage
            let myPublicKey = MessageManager.sharedIdentityManager?.publicKey ?? ""
            let message = SecureMessage(
                id: UUID().uuidString,
                senderAddress: String(senderPublicKey.prefix(10)),
                recipientAddress: String(myPublicKey.prefix(10)),
                encryptedContent: encryptedContent,
                timestamp: Date(),
                isRead: false,
                deliveryStatus: .delivered,
                senderPublicKey: senderPublicKey,
                recipientPublicKey: myPublicKey,
                deliveryMethod: .ipfs
            )
            receiveMessage(message)
            OshiLog.messaging.info("✅ Covert message routed to receive pipeline")
        } else {
            OshiLog.messaging.info("⚠️ CovertMessageReceived: Failed to decode EncryptedMessage")
        }
    }

    deinit {
        ipfsPollingTimer?.invalidate()
        meshReconnectionTimer?.invalidate()
        meshAvailabilityTimer?.invalidate()  // 🔧 NEW
        decryptionRetryTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }
    
    // MARK: - Call Signal Handling
    
    private func setupCallSignalObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSendCallSignal),
            name: NSNotification.Name("SendCallSignalMessage"),
            object: nil
        )
        OshiLog.messaging.info("📞 Call signal observer set up")
    }
    
    @objc private func handleSendCallSignal(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let recipient = userInfo["recipient"] as? String,
              let content = userInfo["content"] as? String,
              let sender = userInfo["sender"] as? String else {
            OshiLog.messaging.info("❌ Invalid call signal notification")
            return
        }
        
        #if DEBUG
        OshiLog.messaging.info("📞 Sending call signal to \(recipient.prefix(8))...")
        #endif
        
        // Send as a message via cloud (IPFS)
        Task {
            await sendCallSignalViaCloud(content: content, recipient: recipient, sender: sender)
        }
    }
    
    private func sendCallSignalViaCloud(content: String, recipient: String, sender: String) async {
        guard let identityManager = MessageManager.sharedIdentityManager else {
            OshiLog.messaging.info("❌ No identity manager for call signal")
            return
        }
        
        #if DEBUG
        OshiLog.messaging.info("📞 sendCallSignalViaCloud: Starting...")
        OshiLog.messaging.info("   Recipient: \(recipient.prefix(8))...")
        #endif
        
        do {
            let sharedSecret = try identityManager.computeSharedSecret(with: recipient)
            
            let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                with: recipient,
                ourPublicKey: sender,
                sharedSecret: sharedSecret
            )
            
            let ratchetMessage = try session.encrypt(content)
            let ratchetData = try JSONEncoder().encode(ratchetMessage)
            let encryptedMessage = try identityManager.wrapRatchetMessage(ratchetData, for: recipient)
            
            // Encode EncryptedMessage to JSON, then base64
            let encryptedData = try JSONEncoder().encode(encryptedMessage)
            
            // Create message for IPFS - use TimeInterval for timestamp (same as regular messages)
            let messageId = UUID().uuidString
            let timestamp = Date().timeIntervalSince1970
            
            let messagePayload: [String: Any] = [
                "id": messageId,
                "senderAddress": sender,
                "recipientAddress": recipient,
                "encryptedContent": encryptedData.base64EncodedString(),
                "timestamp": timestamp  // Use TimeInterval, not ISO8601 string
            ]
            
            guard let jsonData = try? JSONSerialization.data(withJSONObject: messagePayload) else {
                OshiLog.messaging.info("❌ Failed to serialize call signal")
                return
            }
            
            OshiLog.messaging.info("📞 Call signal payload created, uploading to IPFS...")
            
            // Upload to IPFS via VPS
            try await uploadCallSignalToIPFS(jsonData, recipient: recipient)
            OshiLog.messaging.info("✅ Call signal sent via cloud successfully!")
            
        } catch {
            OshiLog.messaging.info("❌ Failed to send call signal: \(error)")
        }
    }
    
    private func uploadCallSignalToIPFS(_ data: Data, recipient: String) async throws {
        // Parse the message payload
        guard let messageDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            OshiLog.messaging.info("❌ Call signal: Failed to parse message data as JSON")
            throw NSError(domain: "MessageManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to parse message data"])
        }
        
        OshiLog.messaging.info("📤 Call signal: Uploading to IPFS...")
        
        do {
            // Use existing PinataService
            let ipfsHash = try await PinataService.shared.pinJSON(messageDict, name: "call_signal_\(Date().timeIntervalSince1970)")
            
            OshiLog.messaging.info("✅ Call signal uploaded to IPFS: \(ipfsHash)")
            
            // Queue for recipient on VPS
            try await queueIPFSHash(ipfsHash, for: recipient)
        } catch {
            OshiLog.messaging.info("❌ Call signal: PinataService error: \(error)")
            throw error
        }
    }
    
    /// 🔕 __SILENT_PATHS_BYPASSED_THE_SUPPRESSION_2026_08_18__
    ///
    /// THE BUG Hugo reported: "I get notifications from the other iPhone for
    /// nothing — if they are profile updates there should be no notification."
    /// He was right, and the reason is not that the suppression list is wrong.
    ///
    /// `notifyVPS` correctly appends `?nopush=1` for silent control traffic, and
    /// `📸PROFILE_UPDATE📸` and `🎨WALLPAPER_UPDATE🎨` are both on its list. But
    /// the profile and wallpaper senders do NOT go through `notifyVPS` — they have
    /// their own Pinata upload and call THIS function, which never suppressed
    /// anything. So the list was right and simply never consulted on the path that
    /// mattered: the server's queue fallback fired a generic "You have a new
    /// message" alert, the recipient's app processed the payload silently, and the
    /// banner had nothing behind it.
    ///
    /// Call signals keep their push on purpose — a call has to ring.
    ///
    /// - Parameter suppressPush: `true` for protocol traffic the peer must process
    ///   but must never be alerted about.
    private func queueIPFSHash(_ hash: String, for recipient: String,
                               suppressPush: Bool = false) async throws {
        guard CoverTransport.legacyOneToOneRelayAllowed else { throw CoverTransport.Failure.legacyRelayRefused }
        let vpsURL = "https://oshi-messenger.com"

        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        let encodedRecipient = base64urlEncode(recipient)

        // Use the same endpoint format as regular messages: /api/queue/{recipientKey}/{ipfsHash}
        let queueURLString = "\(vpsURL)/api/queue/\(encodedRecipient)/\(hash)\(suppressPush ? "?nopush=1" : "")"
        guard let url = URL(string: queueURLString) else {
            OshiLog.messaging.info("❌ Invalid queue URL: \(queueURLString)")
            return
        }
        
        #if DEBUG
        OshiLog.messaging.info("📡 Queuing call signal for recipient: \(recipient.prefix(16))...")
        OshiLog.messaging.info("   URL: \(queueURLString.prefix(80))...")
        #endif
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            
            if let httpResponse = response as? HTTPURLResponse {
                OshiLog.messaging.info("📡 Queue response status: \(httpResponse.statusCode)")
                if let responseText = String(data: data, encoding: .utf8) {
                    OshiLog.messaging.info("   Response: \(responseText)")
                }
                
                if httpResponse.statusCode == 200 {
                    OshiLog.messaging.info("✅ IPFS hash queued for recipient")
                } else {
                    OshiLog.messaging.info("⚠️ Queue returned non-200 status: \(httpResponse.statusCode)")
                }
            }
        } catch {
            OshiLog.messaging.info("❌ Failed to queue IPFS hash: \(error)")
            throw error
        }
    }
    
    /// Handle incoming call signal messages
    private func handleCallSignalMessage(_ content: String, from senderKey: String) {
        // Extract the base64 encoded signal data
        let signalPrefix = "📞CALL_SIGNAL📞"
        guard content.hasPrefix(signalPrefix) else { return }
        
        let base64Signal = String(content.dropFirst(signalPrefix.count))
        guard let signalData = Data(base64Encoded: base64Signal) else {
            OshiLog.messaging.info("❌ Failed to decode call signal data")
            return
        }
        
        #if DEBUG
        OshiLog.messaging.info("📞 Processing call signal from \(senderKey.prefix(8))...")
        OshiLog.messaging.info("   Signal data size: \(signalData.count) bytes")
        #endif
        
        // Get sender name from existing contacts or create one
        let senderName = String(senderKey.prefix(8)) + "..."
        
        // Forward to VoiceCallManager - it will handle all signal types
        // (callRequest, callAccept, callDecline, callEnd)
        VoiceCallManager.shared.handleReceivedCallSignal(
            from: senderKey,
            peerName: senderName,
            encryptedData: signalData
        )
    }
    
    // MARK: - Blocked Contact Observers
    
    private func setupBlockedContactObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleContactBlocked),
            name: .contactBlocked,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleContactUnblocked),
            name: .contactUnblocked,
            object: nil
        )
    }
    
    @objc private func handleContactBlocked(_ notification: Notification) {
        guard let publicKey = notification.userInfo?["publicKey"] as? String else { return }
        #if DEBUG
        OshiLog.messaging.info("🚫 Contact blocked notification received: \(publicKey.prefix(10))...")
        #endif
        
        // Refresh conversations to hide blocked contact
        DispatchQueue.main.async {
            self.updateConversations()
        }
    }
    
    @objc private func handleContactUnblocked(_ notification: Notification) {
        guard let publicKey = notification.userInfo?["publicKey"] as? String else { return }
        #if DEBUG
        OshiLog.messaging.info("✅ Contact unblocked notification received: \(publicKey.prefix(10))...")
        #endif

        // Refresh conversations to show unblocked contact
        DispatchQueue.main.async {
            self.updateConversations()
        }
    }

    // MARK: - 📸 Profile Picture Sync

    private func setupProfileUpdateObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleBroadcastProfileUpdate),
            name: NSNotification.Name("BroadcastProfileUpdate"),
            object: nil
        )
        OshiLog.messaging.info("📸 Profile update observer set up")

        // 🎨 Wallpaper sync observer
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleBroadcastWallpaperUpdate),
            name: NSNotification.Name("BroadcastWallpaperUpdate"),
            object: nil
        )
        OshiLog.messaging.info("🎨 Wallpaper update observer set up")
    }

    // MARK: - 📶 Network Reachability & Message Retry

    private func setupNetworkReachabilityObservers() {
        // __COVER_RETRY_2026_11_18__ Cheap when idle: one check of an empty queue every 5 s.
        coverRetryTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.coverRetryTick() }
        }
        // Subscribe to NetworkReachabilityManager state
        NetworkReachabilityManager.shared.$isConnected
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isConnected in
                self?.isNetworkAvailable = isConnected
            }
            .store(in: &cancellables)

        // Listen for network becoming reachable to retry failed messages
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleNetworkBecameReachable),
            name: Notification.Name.networkBecameReachable,
            object: nil
        )

        // Listen for network becoming unreachable
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleNetworkBecameUnreachable),
            name: Notification.Name.networkBecameUnreachable,
            object: nil
        )

        // Drain the retry queue whenever the app returns to the foreground.
        // The network-transition trigger alone misses the common case where a
        // send fails while connectivity never actually drops (e.g. the V2 relay
        // switch, a transient server 5xx, a suspended-mid-send). Those messages
        // would otherwise sit `.failed` until the user manually re-sent — the
        // "I have to re-type everything to resend" symptom. On every activation
        // we re-enqueue any lingering failed outgoing messages and drain.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppBecameActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )

        OshiLog.messaging.info("📶 Network reachability observers set up")
    }

    @objc private func handleNetworkBecameReachable() {
        OshiLog.messaging.info("📶 Network became reachable - checking for messages to retry")
        enqueueFailedOutgoingForRetry()
        retryFailedMessages()
    }

    @objc private func handleAppBecameActive() {
        OshiLog.messaging.info("📶 App became active - re-driving any unsent messages")
        enqueueFailedOutgoingForRetry()
        retryFailedMessages()
        // __REVEAL_OUTBOX_2026_08_22__ Les couches échues partent ici, et au
        // lancement. Un minuteur ne survivrait pas à 48 h ni à une semaine; ce
        // qui survit, c'est une échéance écrite sur le disque et rejouée chaque
        // fois que l'app revient.
        drainRevealOutbox()
    }

    /// Vide la file des couches de révélation échues.
    ///
    /// Le brancheur est ici plutôt que dans `RevealOutbox` pour que la file ne
    /// sache RIEN du réseau: elle sait quand, pas comment. C'est ce qui la rend
    /// testable sans transport.
    func drainRevealOutbox() {
        Task { @MainActor in
            let outbox = RevealOutbox.shared
            outbox.onWarning = { message in
                OshiLog.storage.info("⚠️ RevealOutbox: \(message)")
            }
            outbox.send = { [weak self] item, bytes in
                guard let self,
                      let identityManager = MessageManager.sharedIdentityManager,
                      let meshManager = MessageManager.sharedMeshManager else { return }
                // Chaque couche est un envoi à part entière, avec son PROPRE
                // identifiant: c'est `revealId` qui les relie, pas l'id du
                // message. Réutiliser l'id de la couche 0 ferait dédoublonner
                // les couches suivantes à l'arrivée — elles disparaîtraient
                // toutes sauf la première.
                _ = self.sendMessage(
                    content: "",
                    recipientAddress: item.peerKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    mediaAttachment: bytes,
                    mediaType: .image,
                    mediaFileName: item.filename,
                    skipLocalMessage: true,   // une couche n'est pas une bulle
                    reveal: RevealSendContext(revealId: item.revealId,
                                              layerIndex: item.layerIndex,
                                              layerCount: item.layerCount,
                                              totalMs: item.totalMs),
                    forcedMessageId: UUID().uuidString
                )
            }
            outbox.drain()
        }
    }

    /// UserDefaults kill switch for `driveUnsentTransfersAfterBackgroundRelaunch`.
    /// Defaults to OFF — unlike the resume ledger, this one SPENDS the few seconds
    /// of runtime iOS grants a background relaunch, and it has never been measured
    /// on a device. Set `oshi_media_bg_relaunch_drive` to `true` to enable.
    static let bgRelaunchDriveKey = "oshi_media_bg_relaunch_drive"

    /// Re-drive unsent messages when iOS relaunched us IN THE BACKGROUND to deliver
    /// background-URLSession events (see the AppDelegate's
    /// `handleEventsForBackgroundURLSession`).
    ///
    /// MECHANISM / why this is needed: the media chunk PUTs run on a background
    /// URLSession, so they complete even after the process is gone. But the rest of
    /// the send — `commit`, then the ratchet key-message envelope — runs in-process,
    /// and the continuation waiting on those chunks died with the old process. So
    /// the bytes reached the server and the message never did; it sat until the user
    /// next opened the app, because the only drain trigger is
    /// `UIApplication.didBecomeActiveNotification`, which a background relaunch
    /// never posts. This adds the missing TRIGGER for the existing drain — it is not
    /// a second retry mechanism.
    ///
    /// Cheap by construction: with the V2 send-resume ledger in place the resumed
    /// attempt re-uses the same blob, finds every chunk already stored, and only has
    /// to commit + send the envelope.
    func driveUnsentTransfersAfterBackgroundRelaunch() {
        guard UserDefaults.standard.bool(forKey: Self.bgRelaunchDriveKey) else { return }
        // Only in the background: when the app IS active, didBecomeActive already
        // did this and running it twice would race `isRetrying`.
        guard UIApplication.shared.applicationState == .background else { return }
        OshiLog.messaging.info("📶 Background relaunch - re-driving unsent transfers")
        enqueueFailedOutgoingForRetry()
        retryFailedMessages()
    }

    /// Sweep the message store for OUR outgoing messages still stuck in a
    /// non-terminal-sent state (`.failed` or long-`.pending`) and make sure each
    /// is in the retry queue. `retrySendViaCloud` re-drives from the live
    /// `SecureMessage`, so we only need the id enqueued; content/media are passed
    /// best-effort for completeness. `addToRetryQueue` dedupes by id, so this is
    /// safe to call repeatedly (foreground, network-up, startup). Without this,
    /// a message that failed while connectivity stayed up was never re-driven and
    /// the user had to re-type it to resend.
    ///
    /// `internal` rather than `private` only so `@testable` can prove the
    /// `permanentlyFailedMessageIds` skip above; no caller outside this file.
    func enqueueFailedOutgoingForRetry() {
        guard let myKey = MessageManager.sharedIdentityManager?.publicKey, !myKey.isEmpty else { return }
        var enqueued = 0
        let resendCutoff = Date().addingTimeInterval(-24 * 60 * 60)
        for msg in messages where msg.senderPublicKey == myKey && msg.deliveryStatus == .failed {
            // Only auto-resend RECENT failures (<24h). Ancient failed messages
            // stay tap-to-retry — mass-resending them would spam recipients
            // with duplicates from days/weeks ago.
            guard msg.timestamp > resendCutoff else { continue }
            // Already burned every attempt against a live network. Re-queueing it
            // here is what made `maxRetryAttempts` a per-cycle cap instead of a
            // per-message one — see `permanentlyFailedMessageIds`. It stays
            // `.failed` and stays tap-to-retry; we just stop re-driving it.
            if permanentlyFailedMessageIds.contains(msg.id) { continue }
            // __MESHCORE_INTEROP_2026_11_25__ Radio-only threads have no OSHI key and no relay mailbox:
            // a failed MeshCore / raw-Meshtastic text is retried by the user over its radio, never here.
            if MeshCoreInterop.isInteropKey(msg.recipientPublicKey)
                || LoRaNodeIdentityMap.isFallbackConversationKey(msg.recipientPublicKey)
                || LoRaChannelThread.isChannelKey(msg.recipientPublicKey) { continue }
            // Skip control/special payloads that must never be auto-resent.
            let content = msg.plaintextContent ?? ""
            if content.hasPrefix("📞CALL_SIGNAL") || content.hasPrefix("📸PROFILE") { continue }
            let hasMedia = msg.mediaAttachment != nil || msg.originalMediaData != nil
            guard hasMedia || !content.isEmpty else { continue }
            if messageRetryQueue.contains(where: { $0.messageId == msg.id }) { continue }
            addToRetryQueue(
                messageId: msg.id,
                content: content,
                recipientKey: msg.recipientPublicKey,
                mediaData: msg.mediaAttachment ?? msg.originalMediaData,
                mediaType: msg.mediaType,
                mediaFileName: msg.mediaFileName
            )
            enqueued += 1
        }
        if enqueued > 0 {
            OshiLog.messaging.info("📶 Re-enqueued \(enqueued) lingering failed message(s) for auto-resend")
        }
    }

    @objc private func handleNetworkBecameUnreachable() {
        OshiLog.messaging.info("📶 Network became unreachable - pausing cloud uploads")
    }

    /// Add a failed message to the retry queue
    func addToRetryQueue(messageId: String, content: String, recipientKey: String, mediaData: Data? = nil, mediaType: MediaManager.MediaType? = nil, mediaFileName: String? = nil) {
        // Check if already in queue
        guard !messageRetryQueue.contains(where: { $0.messageId == messageId }) else {
            OshiLog.messaging.info("📶 Message \(messageId.prefix(8))... already in retry queue")
            return
        }

        let retryableMessage = RetryableMessage(
            messageId: messageId,
            content: content,
            recipientKey: recipientKey,
            mediaData: mediaData,
            mediaType: mediaType,
            mediaFileName: mediaFileName
        )
        messageRetryQueue.append(retryableMessage)
        saveRetryQueue()
        OshiLog.messaging.info("📶 Added message \(messageId.prefix(8))... to retry queue (total: \(messageRetryQueue.count))")
    }

    /// Persist the retry queue so offline messages survive an app restart and
    /// still auto-resend when the network returns (previously RAM-only → any
    /// force-quit before reconnection silently dropped the queued messages).
    private func saveRetryQueue() {
        FileStorage.shared.save(messageRetryQueue, forKey: "message_retry_queue")
    }

    /// Drop a message from the retry queue once a transport has confirmed it is
    /// sent. The enqueue happens BEFORE any network work (see `sendMessage`), so
    /// without a matching removal on the success paths the queue would grow
    /// forever and re-drive already-delivered messages on the next reachability
    /// event. Idempotent — a message that isn't queued is a no-op.
    func removeFromRetryQueue(messageId: String) {
        guard let index = messageRetryQueue.firstIndex(where: { $0.messageId == messageId }) else { return }
        messageRetryQueue.remove(at: index)
        saveRetryQueue()
    }

    /// Reload the persisted retry queue + pending-decryption queue on launch,
    /// OFF the main thread (both files can hold megabytes and were decoded inside
    /// `@StateObject` init, before the first frame). Drops retry entries older
    /// than a day (stale); the existing reachability observer and the 30 s
    /// decryption timer then drain the two queues as before.
    private func loadDeferredQueues() {
        Task.detached(priority: .utility) { [weak self] in
            let saved: [RetryableMessage] = FileStorage.shared.load(forKey: "message_retry_queue") ?? []
            let restored: [String: [SecureMessage]] = FileStorage.shared.load(forKey: "pending_decryption") ?? [:]
            let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
            let fresh = saved.filter { $0.createdAt > cutoff }

            await MainActor.run {
                guard let self else { return }
                if !fresh.isEmpty {
                    self.messageRetryQueue = fresh
                    OshiLog.messaging.info("📶 Reloaded \(fresh.count) message(s) into retry queue from disk")
                }
                if !restored.isEmpty {
                    self.pendingDecryption = restored
                    let total = restored.values.reduce(0) { $0 + $1.count }
                    OshiLog.messaging.info("📥 Restored \(total) pending-decryption message(s) from disk across \(restored.count) sender(s)")
                    // __PHANTOM_BANNER_2026_10_11__ Republier pour l'extension: ces
                    // messages ont ete gares dans une session PRECEDENTE, et le
                    // serveur re-poussera les memes. Sans cette ligne, l'instantane
                    // ne serait reecrit qu'au prochain echec.
                    self.publishUndecryptableSnapshot()
                }
            }
        }
    }

    /// Retry all failed messages when network becomes available.
    ///
    /// `internal` rather than `private` only so `@testable` can drive the drain;
    /// no caller outside this file.
    func retryFailedMessages() {
        guard !isRetrying && !messageRetryQueue.isEmpty else { return }
        guard isNetworkAvailable else {
            OshiLog.messaging.info("📶 Network still unavailable, cannot retry")
            return
        }

        isRetrying = true
        OshiLog.messaging.info("📶 Retrying \(messageRetryQueue.count) failed messages...")

        // __RETRY_DRAIN_MAIN_ACTOR_2026_09_12__ The drain runs ON the main actor and
        // addresses the queue by messageId — never by a captured index.
        //
        // This used to be a bare `Task { }` inside a nonisolated class (the app target
        // does NOT set SWIFT_DEFAULT_ACTOR_ISOLATION — only the Watch target does), so
        // the loop ran off-main and read `messageRetryQueue[i]` while main-actor code
        // mutated the same array: a genuine data race on a Swift Array. Worse, the
        // index range was computed once, up front, while the array can shrink across
        // the `await` below — this loop removes entries itself, and the send paths call
        // `removeFromRetryQueue` on delivery. A stale `i` then indexed out of range and
        // crashed during a reconnect drain, i.e. exactly when several messages were
        // unsent. The bounds guards that used to be here covered the two WRITES but not
        // the read that actually crashed.
        //
        // A value-copy snapshot plus id-addressed mutation removes both hazards. Which
        // messages get retried is unchanged; the order is now oldest-first (the old
        // `.reversed()` existed only to make index-based removal safe within a single
        // pass, and it drained newest-first), so a batch retry now reaches the relay in
        // send order.
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            defer { self.isRetrying = false }

            let snapshot = self.messageRetryQueue
            for queued in snapshot {
                let newRetryCount = queued.retryCount + 1
                let messageId = queued.messageId

                // Skip if max retries exceeded. In cover mode the disguised-retry
                // schedule owns the limit (1 h, `coverRetryTick`); counting its
                // reachability/foreground drains here would retire a message after
                // three app activations on a network that blocks the relay.
                if newRetryCount > self.maxRetryAttempts, CoverTransport.mode == nil {
                    OshiLog.messaging.info("📶 Message \(messageId.prefix(8))... exceeded max retries, removing from queue")
                    self.removeFromRetryQueue(messageId: messageId)
                    self.permanentlyFailedMessageIds.insert(messageId)
                    continue
                }

                OshiLog.messaging.info("📶 Retrying message \(messageId.prefix(8))... (attempt \(newRetryCount))")

                // Update retry count in queue — by id, because the array can have
                // shifted underneath us while we were suspended.
                if let idx = self.messageRetryQueue.firstIndex(where: { $0.messageId == messageId }) {
                    self.messageRetryQueue[idx].retryCount = newRetryCount
                }

                // Create updated message for retry
                let updatedMessage = RetryableMessage(
                    messageId: queued.messageId,
                    content: queued.content,
                    recipientKey: queued.recipientKey,
                    mediaData: queued.mediaData,
                    mediaType: queued.mediaType,
                    mediaFileName: queued.mediaFileName,
                    retryCount: newRetryCount
                )

                // Attempt to resend via cloud
                let success = await self.retrySendViaCloud(message: updatedMessage)

                if success {
                    OshiLog.messaging.info("📶 Message \(messageId.prefix(8))... retry successful!")
                    _ = await MainActor.run {
                        // Remove from queue
                        if let index = self.messageRetryQueue.firstIndex(where: { $0.messageId == messageId }) {
                            self.messageRetryQueue.remove(at: index)
                            self.saveRetryQueue()
                        }
                        // Update message status
                        if let msgIndex = self.messages.firstIndex(where: { $0.id == messageId }) {
                            self.messages[msgIndex].deliveryStatus = .sent
                            self.saveMessages()
                        }
                    }
                } else {
                    OshiLog.messaging.info("📶 Message \(messageId.prefix(8))... retry failed")
                }

                // Small delay between retries to avoid overwhelming the server
                try? await Task.sleep(nanoseconds: 500_000_000)  // 0.5 seconds
            }
            // `isRetrying` is cleared by the `defer` at the top of this Task, which
            // also covers the early exits the old tail-assignment missed.
        }
    }

    /// __COVER_WAITING_BUBBLE_2026_11_18__ True while cover mode holds this outgoing
    /// message for a disguised retry: the bubble shows the clock and "Waiting for
    /// disguised connection" instead of the red "tap to retry".
    func isWaitingForCover(_ messageId: String, status: DeliveryStatus) -> Bool {
        CoverWaitingState.isWaiting(failed: status == .failed,
                                    coverOn: CoverTransport.mode != nil,
                                    queued: messageRetryQueue.contains(where: { $0.messageId == messageId }),
                                    gaveUp: permanentlyFailedMessageIds.contains(messageId))
    }

    /// __COVER_RETRY_2026_11_18__ The disguised path is answering again (a V2 pull
    /// through the cover transport succeeded): let the next tick retry parked rows
    /// now instead of waiting out their backoff (bounded by `CoverRetrySchedule.minSpacing`).
    func coverRetryKick() {
        DispatchQueue.main.async { [weak self] in self?.coverRetryKicked = true }
    }

    /// __COVER_RETRY_2026_11_18__ Re-drives queued 1:1 messages over the disguised
    /// path on `CoverRetrySchedule` while cover mode is ON. `retrySendViaCloud` →
    /// `fallbackToIPFS`, which in cover mode tries V2 (disguised) and otherwise
    /// throws before any legacy transport: this never sends undisguised.
    @MainActor
    func coverRetryTick(now: Date = Date()) {
        let kicked = coverRetryKicked
        coverRetryKicked = false
        guard !messageRetryQueue.isEmpty, !isRetrying else { return }
        guard CoverTransport.mode != nil else { coverRetrySchedules.removeAll(); return }
        var due: [RetryableMessage] = []
        for queued in messageRetryQueue {
            var schedule = coverRetrySchedules[queued.messageId] ?? CoverRetrySchedule(firstFailure: queued.createdAt)
            switch schedule.decision(now: now, kicked: kicked) {
            case .wait: break
            case .giveUp:
                OshiLog.messaging.info("🕵️ \(queued.messageId.prefix(8))… not delivered over the disguised path in 1 h — left failed")
                removeFromRetryQueue(messageId: queued.messageId)
                permanentlyFailedMessageIds.insert(queued.messageId)
                coverRetrySchedules.removeValue(forKey: queued.messageId)
                // __COVER_WAITING_BUBBLE_2026_11_18__ the bubble stops "waiting" and
                // shows "tap to retry"; nothing else about the row changed to redraw it.
                objectWillChange.send()
                continue
            case .attempt:
                schedule.recordAttempt(now: now)
                due.append(queued)
            }
            coverRetrySchedules[queued.messageId] = schedule
        }
        guard !due.isEmpty else { return }
        isRetrying = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.isRetrying = false }
            for queued in due {
                guard await self.retrySendViaCloud(message: queued) else { continue }
                OshiLog.messaging.info("🕵️ \(queued.messageId.prefix(8))… delivered on disguised retry")
                self.removeFromRetryQueue(messageId: queued.messageId)
                self.coverRetrySchedules.removeValue(forKey: queued.messageId)
                if let i = self.messages.firstIndex(where: { $0.id == queued.messageId }), self.messages[i].deliveryStatus == .failed {
                    self.messages[i].deliveryStatus = .sent
                    self.saveMessages()
                }
            }
        }
    }

    /// Retry sending a single message by re-driving the SAME transport chain the
    /// primary send uses (V2 relay → Pinata + `/api/queue`) on the live
    /// `SecureMessage`.
    ///
    /// The previous implementation POSTed Double-Ratchet ciphertext to
    /// `\(currentVPSURL):8082/upload/{recipient}` and treated HTTP 200 as
    /// success — but no receiver ever polls that endpoint (they read
    /// `/api/pending/{key}` and the V2 pull loop). So a retry "succeeded",
    /// flipped the bubble to `.sent`, yet the recipient never got the message:
    /// false-sent + silent loss. It also skipped V2 entirely and wrapped media
    /// in an ad-hoc `📎MEDIA:` string no receive handler parses. Routing through
    /// `fallbackToIPFS` fixes all three: it marks `.sent` on success and
    /// re-queues idempotently on failure (`addToRetryQueue` dedupes by id).
    private func retrySendViaCloud(message: RetryableMessage) async -> Bool {
        let target: SecureMessage? = await MainActor.run {
            self.messages.first(where: { $0.id == message.messageId })
        }
        guard let msg = target else {
            // The message was deleted since it was queued — nothing to resend.
            return true
        }
        // 🔧 DURABILITY FIX companion: sends are now enqueued BEFORE the transport
        // runs, so the queue legitimately contains messages that are in flight or
        // already delivered. Re-driving those would duplicate them on the recipient.
        // Anything past `.pending` is done; a still-`.pending` message younger than
        // 60 s is almost certainly the live send still working.
        if msg.deliveryStatus != .pending && msg.deliveryStatus != .failed { return true }
        if msg.deliveryStatus == .pending, Date().timeIntervalSince(msg.timestamp) < 60 { return false }
        do {
            try await fallbackToIPFS(message: msg, messageId: msg.id,
                                     recipientPublicKey: msg.recipientPublicKey)
            return true
        } catch {
            OshiLog.messaging.info("📶 Retry via real transport failed for \(msg.id.prefix(8))…: \(error)")
            return false
        }
    }

    /// Handle request to broadcast profile update to all contacts
    @objc private func handleBroadcastProfileUpdate(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let payloadData = userInfo["payload"] as? Data else {
            OshiLog.messaging.info("❌ Invalid profile update broadcast notification")
            return
        }

        OshiLog.messaging.info("📸 Broadcasting profile update to all contacts...")

        Task {
            await broadcastProfileToAllContacts(payloadData: payloadData)
        }
    }

    /// Send profile update to all contacts with conversations
    private func broadcastProfileToAllContacts(payloadData: Data) async {
        guard let identityManager = MessageManager.sharedIdentityManager else {
            OshiLog.messaging.info("❌ No identity manager for profile broadcast")
            return
        }

        // Get all unique contacts from conversations
        let contactKeys = conversations.map { $0.participantPublicKey }
        guard !contactKeys.isEmpty else {
            OshiLog.messaging.info("📸 No contacts to broadcast profile to")
            return
        }

        // A profile is not an address-book announcement. A contact who has only
        // messaged us must not receive our picture (or later profile fields)
        // until we have chosen to reply to them at least once.
        let eligibleContactKeys = contactKeys.filter { hasSentUserMessage(to: $0, as: identityManager.publicKey) }
        guard !eligibleContactKeys.isEmpty else {
            OshiLog.messaging.info("📸 No replied-to contacts eligible for profile broadcast")
            return
        }

        OshiLog.messaging.info("📸 Broadcasting profile to \(eligibleContactKeys.count) replied-to contacts")

        // Create profile update message content
        guard let payloadString = String(data: payloadData, encoding: .utf8) else {
            OshiLog.messaging.info("❌ Failed to encode profile payload")
            return
        }

        let profileMessageContent = "📸PROFILE_UPDATE📸\(payloadString)"

        for contactKey in eligibleContactKeys {
            // 🔴 PROFILE-SYNC FIX: send the profile update over the SAME reliable v2
            // transport that regular messages use, not the legacy IPFS-only path.
            // IPFS (Pinata) is a ceasing fallback — when pinJSON failed, the new
            // avatar never reached the contact, so a picture changed on one phone
            // never updated on the other. Try v2 first; fall through to the IPFS
            // path below only if the peer has no v2 bundle (older app) or v2 errors.
            let v2MessageId = UUID().uuidString
            let v2Message = SecureMessage(
                id: v2MessageId,
                senderAddress: identityManager.publicKey,
                recipientAddress: contactKey,
                encryptedContent: EncryptedMessage(ciphertext: "", signature: "",
                                                   senderPublicKey: identityManager.publicKey,
                                                   timestamp: Date()),
                timestamp: Date(),
                isRead: true,
                deliveryStatus: .sent,
                senderPublicKey: identityManager.publicKey,
                recipientPublicKey: contactKey,
                plaintextContent: profileMessageContent
            )
            if await v2TrySend(message: v2Message, messageId: v2MessageId, recipientPublicKey: contactKey) {
                OshiLog.messaging.info("📸 Profile sent via v2 (reliable) to \(contactKey.prefix(8))...")
                continue
            }
            do {
                let sharedSecret = try identityManager.computeSharedSecret(with: contactKey)
                let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                    with: contactKey,
                    ourPublicKey: identityManager.publicKey,
                    sharedSecret: sharedSecret
                )

                let ratchetMessage = try session.encrypt(profileMessageContent)
                let ratchetData = try JSONEncoder().encode(ratchetMessage)
                let encryptedMessage = try identityManager.wrapRatchetMessage(ratchetData, for: contactKey)

                // Encode and send via IPFS
                let encryptedData = try JSONEncoder().encode(encryptedMessage)
                let base64Encrypted = encryptedData.base64EncodedString()

                // Send via cloud (IPFS)
                await sendProfileUpdateViaCloud(
                    encryptedContent: base64Encrypted,
                    recipient: contactKey,
                    sender: identityManager.publicKey
                )

                OshiLog.messaging.info("📸 Profile sent to \(contactKey.prefix(8))...")
            } catch {
                OshiLog.messaging.info("❌ Failed to send profile to \(contactKey.prefix(8)): \(error)")
            }
        }
    }

    /// Send profile update message via IPFS/cloud
    /// Send profile update message via IPFS queue (same as regular messages)
    /// 🔧 FIX: Use IPFS-based queue instead of port 8083 so receiver can poll it
    private func sendProfileUpdateViaCloud(encryptedContent: String, recipient: String, sender: String) async {
        // Create message payload matching SecureMessage format
        let messagePayload: [String: Any] = [
            "senderPublicKey": sender,
            "recipientPublicKey": recipient,
            "encryptedContent": encryptedContent,
            "timestamp": Date().timeIntervalSince1970,
            "id": UUID().uuidString
        ]

        do {
            // Upload to IPFS via Pinata using pinJSON
            let hash = try await PinataService.shared.pinJSON(messagePayload, name: "profile_update_\(UUID().uuidString)")
            OshiLog.messaging.info("📸 Profile update uploaded to IPFS: \(hash.prefix(16))...")

            // Queue for recipient via standard API — SILENT: a new avatar is not
            // an event worth waking someone for.
            try await queueIPFSHash(hash, for: recipient, suppressPush: true)
            OshiLog.messaging.info("📸 Profile update queued for \(recipient.prefix(8))... (nopush)")
        } catch {
            OshiLog.messaging.info("❌ Failed to send profile via IPFS: \(error)")
        }
    }

    /// Track recent profile updates to prevent duplicate processing
    private static var recentProfileUpdates: [String: Date] = [:]
    /// Empreinte de la derniere charge par expediteur (voir le filtre ci-dessous).
    private static var recentProfileUpdateHashes: [String: Int] = [:]
    private static let profileUpdateCooldown: TimeInterval = 30 // 30 seconds cooldown

    /// Process incoming profile update from a contact
    func processIncomingProfileUpdate(from senderKey: String, content: String) {
        // 🔧 FIX: Prevent duplicate profile update processing
        let now = Date()
        // __SHARED_NICKNAME_2026_09_22__ Le filtre ne jette plus QUE les doublons
        // a l'identique. Avant, toute mise a jour de profil dans les 30 s suivant
        // la precedente etait perdue — un pseudo change juste apres un avatar (ou
        // deux fois de suite) n'arrivait jamais.
        let contentHash = content.hashValue
        if let lastUpdate = MessageManager.recentProfileUpdates[senderKey],
           now.timeIntervalSince(lastUpdate) < MessageManager.profileUpdateCooldown,
           MessageManager.recentProfileUpdateHashes[senderKey] == contentHash {
            OshiLog.messaging.info("⏭️ Profile update from \(senderKey.prefix(8))... skipped (duplicate within \(Int(MessageManager.profileUpdateCooldown))s)")
            return
        }
        MessageManager.recentProfileUpdates[senderKey] = now
        MessageManager.recentProfileUpdateHashes[senderKey] = contentHash

        // Remove the marker prefix
        let jsonString = content.replacingOccurrences(of: "📸PROFILE_UPDATE📸", with: "")

        guard let jsonData = jsonString.data(using: .utf8),
              let payload = try? JSONDecoder().decode(ProfileUpdatePayload.self, from: jsonData) else {
            OshiLog.messaging.info("❌ Failed to decode profile update from \(senderKey.prefix(8))...")
            return
        }

        OshiLog.messaging.info("📸 Processing profile update from \(senderKey.prefix(8))...")

        // Record the peer's post-quantum key, if they published one. Their NEXT
        // session with us can then be hybrid; the one running now is left alone,
        // because re-keying a live conversation would strand them mid-thread.
        if let pqB64 = payload.pqPublicKey, let pqKey = Data(base64Encoded: pqB64) {
            PostQuantumPeerDirectory.shared.record(pqKey, for: senderKey)
        }
        // CV-002: a group only moves to envelope v3 once EVERY member has said
        // they can read it, so this flag is what actually gates the migration.
        PeerCapabilityStore.shared.record(groupV3: payload.groupEnvelopeV3 == true,
                                          for: senderKey)
        // CV-010: the same rule — a rotating session is only built once the peer
        // has said it can hold one. Existing sessions are untouched; the upgrade
        // happens the next time the ratchet is re-established.
        PeerCapabilityStore.shared.record(ratchetV3: payload.ratchetV3 == true,
                                          for: senderKey)
        // __PEER_VERSION_2026_09_09__ La version annoncee par le pair, qui sert
        // uniquement a afficher une banniere non bloquante dans la conversation
        // quand il est reste avant le changement de calendrier de cles du
        // repondeur v3. Absente sur toutes les builds <= 1.0.43: `record`
        // ignore alors l'appel, et rien ne s'affiche.
        // La plateforme voyage avec la version et est enregistree avec elle:
        // sans elle, "1.0.43" et "1.6.13" sont deux chaines qui se comparent
        // proprement et ne veulent rien dire ensemble.
        PeerVersionStore.shared.record(payload.appVersion,
                                       platform: payload.appPlatform,
                                       for: senderKey)

        // __SHARED_NICKNAME_2026_09_22__ Le pseudo du pair. Le decodeur
        // synthetise rend nil pour une cle ABSENTE comme pour une cle VIDE; il
        // faut les distinguer (absente = client ancien, on garde; vide = le pair
        // a efface son pseudo), d'ou la relecture de la presence de la cle.
        // Aucun effet de bord visible hormis l'affichage: pas de message stocke,
        // pas de notification (on est deja sorti du chemin des messages).
        let nicknamePresent = ((try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any])?["displayName"] != nil
        let wireNickname = payload.displayName
        DispatchQueue.main.async {
            SharedNickname.apply(wireNickname, wirePresent: nicknamePresent, for: senderKey)
        }

        // Update the contact's profile in ContactPresenceManager
        ContactPresenceManager.shared.processProfileUpdate(from: senderKey, payload: payload)

        OshiLog.messaging.info("✅ Contact profile updated for \(senderKey.prefix(8))...")

        // 🔧 Clean up old entries periodically (keep last 100)
        if MessageManager.recentProfileUpdates.count > 100 {
            let sortedKeys = MessageManager.recentProfileUpdates.sorted { $0.value < $1.value }
            let keysToRemove = sortedKeys.prefix(50).map { $0.key }
            for key in keysToRemove {
                MessageManager.recentProfileUpdates.removeValue(forKey: key)
                MessageManager.recentProfileUpdateHashes.removeValue(forKey: key)
            }
        }
    }

    // MARK: - 🎨 Wallpaper Sync

    /// Handle request to broadcast wallpaper update to conversation participant
    @objc private func handleBroadcastWallpaperUpdate(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let payloadData = userInfo["payload"] as? Data,
              let conversationId = userInfo["conversationId"] as? String else {
            OshiLog.messaging.info("❌ Invalid wallpaper update broadcast notification")
            return
        }

        // Check if this is a group wallpaper update
        if let groupId = UUID(uuidString: conversationId) {
            Task { @MainActor in
                guard let group = MessageGroupManager.shared.groups.first(where: { $0.id == groupId }) else { return }
                OshiLog.messaging.info("🎨 Broadcasting group wallpaper update to \(group.members.count) members...")
                guard let identityManager = MessageManager.sharedIdentityManager else { return }
                let myKey = identityManager.publicKey
                // Add system message inside the group (sender side)
                MessageGroupManager.shared.addSystemMessage(to: groupId, type: .wallpaperChanged, byPublicKey: myKey)
                for member in group.members where member.publicKey != myKey {
                    await self.sendWallpaperUpdateToParticipant(payloadData: payloadData, conversationId: member.publicKey)
                }
            }
            return
        }

        // 1:1 wallpaper update
        OshiLog.messaging.info("🎨 Broadcasting wallpaper update...")
        Task {
            await sendWallpaperUpdateToParticipant(payloadData: payloadData, conversationId: conversationId)
        }
    }

    /// Send wallpaper update to the conversation participant
    private func sendWallpaperUpdateToParticipant(payloadData: Data, conversationId: String) async {
        guard let identityManager = MessageManager.sharedIdentityManager else {
            OshiLog.messaging.info("❌ No identity manager for wallpaper broadcast")
            return
        }

        // Find contact key: from conversation lookup or use conversationId directly (for group members)
        let contactKey: String
        if let conversation = conversations.first(where: { $0.participantPublicKey == conversationId }) {
            contactKey = conversation.participantPublicKey
        } else {
            // Direct public key (used for group members without a 1:1 conversation)
            contactKey = conversationId
        }

        guard let payloadString = String(data: payloadData, encoding: .utf8) else {
            OshiLog.messaging.info("❌ Failed to encode wallpaper payload")
            return
        }

        let wallpaperMessageContent = "🎨WALLPAPER_UPDATE🎨\(payloadString)"

        do {
            let sharedSecret = try identityManager.computeSharedSecret(with: contactKey)
            let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                with: contactKey,
                ourPublicKey: identityManager.publicKey,
                sharedSecret: sharedSecret
            )

            let ratchetMessage = try session.encrypt(wallpaperMessageContent)
            let ratchetData = try JSONEncoder().encode(ratchetMessage)
            let encryptedMessage = try identityManager.wrapRatchetMessage(ratchetData, for: contactKey)

            let encryptedData = try JSONEncoder().encode(encryptedMessage)
            let base64Encrypted = encryptedData.base64EncodedString()

            await sendWallpaperUpdateViaCloud(
                encryptedContent: base64Encrypted,
                recipient: contactKey,
                sender: identityManager.publicKey
            )

            OshiLog.messaging.info("🎨 Wallpaper update sent to \(contactKey.prefix(8))...")
        } catch {
            OshiLog.messaging.info("❌ Failed to send wallpaper to \(contactKey.prefix(8)): \(error)")
        }
    }

    /// Send wallpaper update message via IPFS queue (same as regular messages)
    /// 🔧 FIX: Use IPFS-based queue instead of port 8083 so receiver can poll it
    private func sendWallpaperUpdateViaCloud(encryptedContent: String, recipient: String, sender: String) async {
        // Create message payload matching SecureMessage format
        let messagePayload: [String: Any] = [
            "senderPublicKey": sender,
            "recipientPublicKey": recipient,
            "encryptedContent": encryptedContent,
            "timestamp": Date().timeIntervalSince1970,
            "id": UUID().uuidString
        ]

        do {
            // Upload to IPFS via Pinata using pinJSON
            let hash = try await PinataService.shared.pinJSON(messagePayload, name: "wallpaper_update_\(UUID().uuidString)")
            OshiLog.messaging.info("🎨 Wallpaper update uploaded to IPFS: \(hash.prefix(16))...")

            // Queue for recipient via standard API — SILENT, same reasoning as the
            // profile update above.
            try await queueIPFSHash(hash, for: recipient, suppressPush: true)
            OshiLog.messaging.info("🎨 Wallpaper update queued for \(recipient.prefix(8))... (nopush)")
        } catch {
            OshiLog.messaging.info("❌ Failed to send wallpaper via IPFS: \(error)")
        }
    }

    /// Process incoming wallpaper update from a contact
    func processIncomingWallpaperUpdate(from senderKey: String, content: String) {
        // Remove the marker prefix
        let jsonString = content.replacingOccurrences(of: "🎨WALLPAPER_UPDATE🎨", with: "")

        guard let jsonData = jsonString.data(using: .utf8),
              var payload = try? JSONDecoder().decode(ChatWallpaperManager.WallpaperUpdatePayload.self, from: jsonData) else {
            OshiLog.messaging.info("❌ Failed to decode wallpaper update from \(senderKey.prefix(8))...")
            return
        }

        // 🔧 FIX: Map conversationId correctly
        // For group wallpapers: conversationId is a group UUID — keep it as-is
        // For 1:1 wallpapers: conversationId is OUR public key — remap to senderKey
        // Public keys are hex/base58 strings, never UUID format — UUID parse is sufficient
        let isGroupWallpaper = UUID(uuidString: payload.conversationId) != nil

        let mappedConversationId = isGroupWallpaper ? payload.conversationId : senderKey

        var updatedPayload = payload
        updatedPayload = ChatWallpaperManager.WallpaperUpdatePayload(
            conversationId: mappedConversationId,
            wallpaperData: payload.wallpaperData,
            timestamp: payload.timestamp,
            senderName: payload.senderName
        )
        updatedPayload.customImageBase64 = payload.customImageBase64
        payload = updatedPayload

        OshiLog.messaging.info("🎨 Processing wallpaper update from \(senderKey.prefix(8))... for conversation \(senderKey.prefix(8))...")

        // Apply the wallpaper and get sender name
        if let _ = ChatWallpaperManager.shared.processWallpaperUpdate(payload: payload) {
            if isGroupWallpaper, let groupId = UUID(uuidString: mappedConversationId) {
                // Group wallpaper: create system message INSIDE the group, not in 1:1 conversations
                DispatchQueue.main.async {
                    MessageGroupManager.shared.addSystemMessage(to: groupId, type: .wallpaperChanged, byPublicKey: senderKey)
                    NotificationCenter.default.post(name: NSNotification.Name("WallpaperChanged"), object: nil, userInfo: ["conversationId": mappedConversationId])
                }
                OshiLog.messaging.info("✅ Group wallpaper updated from \(senderKey.prefix(8))... for group \(mappedConversationId.prefix(8))")
            } else {
                // 1:1 wallpaper: create system message in the 1:1 conversation
                let senderName = ChatWallpaperManager.shared.processWallpaperUpdate(payload: payload)?.senderName ?? String(senderKey.prefix(8)) + "..."
                let myPublicKey = MessageManager.sharedIdentityManager?.publicKey ?? ""

                let emptyEncryptedContent = EncryptedMessage(
                    ciphertext: "",
                    signature: "",
                    senderPublicKey: senderKey,
                    timestamp: Date()
                )

                let systemMessage = SecureMessage(
                    id: UUID().uuidString,
                    senderAddress: String(senderKey.prefix(8)),
                    recipientAddress: String(myPublicKey.prefix(8)),
                    encryptedContent: emptyEncryptedContent,
                    timestamp: Date(),
                    isRead: false,
                    deliveryStatus: .delivered,
                    senderPublicKey: senderKey,
                    recipientPublicKey: myPublicKey,
                    plaintextContent: "🎨 \(senderName) " + "wallpaper.changed_notification".localized,
                    deliveryMethod: .ipfs
                )

                DispatchQueue.main.async { [weak self] in
                    self?.messages.append(systemMessage)
                    self?.objectWillChange.send()
                    NotificationCenter.default.post(name: NSNotification.Name("WallpaperChanged"), object: senderKey)
                }
                OshiLog.messaging.info("✅ 1:1 wallpaper updated from \(senderKey.prefix(8))...")
            }
        }
    }

    /// ⌨️ Un ping « en train d'écrire » reçu en 1:1.
    ///
    /// __TYPING_PING_WAS_STORED_2026_10_20__ Il n'y avait AUCUN gestionnaire
    /// pour ces deux sentinelles dans ce funnel: `TypingIndicatorManager`
    /// n'écoute le 1:1 que par la notification `ReceivedTypingStatus`, **que
    /// personne n'a jamais postée** (le groupe, lui, est bien câblé en direct
    /// depuis `GroupMessaging`). Le ping tombait donc jusqu'à `addMessage`, était
    /// rangé avec `isRead = false`, COMPTÉ comme non lu, et caché du fil par
    /// `computeFilteredMessages` — un badge devant une conversation vide. Pire:
    /// le nettoyage au chargement n'enlevait pas ces préfixes, donc le badge
    /// survivait au redémarrage.
    ///
    /// Le coup de pouce de ré-établissement de session envoie la sentinelle NUE
    /// (voir `sendRatchetRehandshake`): il n'y a rien à afficher, et prétendre
    /// que le pair tape serait un mensonge. On ne remonte donc l'indicateur que
    /// pour la forme JSON, et dans les deux cas on ne range RIEN.
    private func handleIncomingTypingPing(_ payload: String, from senderKey: String) {
        if let status = TypingStatus.fromMessageString(payload) {
            TypingIndicatorManager.shared.receiveTypingStatus(status)
        } else {
            OshiLog.messaging.info("⌨️ Bare typing sentinel from \(senderKey.prefix(8))… — session nudge, nothing to show")
        }
        // Le pair vient de prouver qu'il est là, quelle que soit la forme.
        ContactPresenceManager.shared.updateLastSeen(publicKey: senderKey)
    }

    /// 📖 Handle incoming read receipt (the peer opened our conversation).
    /// Marks OUR outgoing messages to that peer as read: advances the delivery
    /// status to `.delivered` (a read implies delivered) AND sets `isRead = true`
    /// so the bubble's read indicator (brand-color double-check) actually lights
    /// up. Previously only the status was advanced and `isRead` was never set on
    /// outgoing messages, so the "read" state was dead — the blue checks never
    /// appeared. Persisted so the read state survives relaunch.
    private func handleIncomingReadReceipt(from senderKey: String) {
        // __IDENTITY_CANONICAL_2026_08_15__ `senderKey` comes straight off the wire
        // and the stored keys are canonical; matching them raw left every bubble
        // stuck on its sent tick.
        let senderKey = MessageManager.canonicalIdentity(senderKey)
        // __BLOCKED_BY_PEER_2026_09_24__ a device that still blocks us never acks.
        BlockedByPeerStore.shared.clear(senderKey)
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let me = MessageManager.canonicalIdentity(MessageManager.sharedIdentityManager?.publicKey ?? "")
            var updated = 0
            for i in self.messages.indices {
                guard MessageManager.canonicalIdentity(self.messages[i].senderPublicKey) == me,
                      MessageManager.canonicalIdentity(self.messages[i].recipientPublicKey) == senderKey else { continue }
                var changed = false
                // A read receipt covers every earlier outgoing message.
                //
                // 🔧 (2026-08-08) Advances to `.read`, not `.delivered`. Read is
                // now a STATE with its own rank, so the tick can say "a person
                // opened this" instead of re-tinting the delivered one. The
                // `isRead` boolean is still written below for older persisted
                // messages and for anything still reading it.
                let advanced = self.messages[i].deliveryStatus.advanced(to: .read)
                if advanced != self.messages[i].deliveryStatus {
                    self.messages[i].deliveryStatus = advanced
                    changed = true
                }
                if !self.messages[i].isRead {
                    self.messages[i].isRead = true
                    changed = true
                }
                if changed { updated += 1 }
            }
            if updated > 0 {
                self.saveMessages()
                OshiLog.messaging.info("📖 Marked \(updated) outgoing message(s) as READ (receipt from \(senderKey.prefix(8))...)")
                self.objectWillChange.send()
            }
        }
    }

    /// 📬 Handle incoming delivery receipt from Android
    /// Extracts the messageId from a precise delivery-receipt payload
    /// "📬DELIVERY_RECEIPT📬<id>". Returns "" for a legacy bare-prefix receipt.
    /// Pure + static → unit-testable (ReceiptStateMachineTests).
    static func parseDeliveryReceiptMessageId(_ content: String) -> String {
        content
            .replacingOccurrences(of: "📬DELIVERY_RECEIPT📬", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func handleIncomingDeliveryReceipt(from senderKey: String, content: String) {
        // __IDENTITY_CANONICAL_2026_08_15__ Same wire-vs-stored spelling problem as
        // the read receipt above.
        let senderKey = MessageManager.canonicalIdentity(senderKey)
        BlockedByPeerStore.shared.clear(senderKey)   // __BLOCKED_BY_PEER_2026_09_24__
        let me = MessageManager.canonicalIdentity(MessageManager.sharedIdentityManager?.publicKey ?? "")
        // M2 fix: parse the precise form "📬DELIVERY_RECEIPT📬<messageId>".
        // Older peers may send the bare prefix with no id.
        let messageId = MessageManager.parseDeliveryReceiptMessageId(content)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            // Precise: advance EXACTLY the acked message (monotonic).
            if !messageId.isEmpty,
               let i = self.messages.firstIndex(where: {
                   $0.id == messageId &&
                   MessageManager.canonicalIdentity($0.senderPublicKey) == me &&
                   MessageManager.canonicalIdentity($0.recipientPublicKey) == senderKey
               }) {
                let s = self.messages[i].deliveryStatus
                let advanced = s.advanced(to: .delivered)
                if advanced != s {
                    self.messages[i].deliveryStatus = advanced
                    self.saveMessages()
                    self.objectWillChange.send()
                    OshiLog.messaging.info("📬 Message \(messageId.prefix(8))… → DELIVERED (precise receipt from \(senderKey.prefix(8))…)")
                    // ⭐️ Demonstrated success for the rating heuristic: the peer
                    // ACKED it. Composing/sending proves nothing; a receipt does.
                    // Arms a milestone only — never presents mid-conversation.
                    Task { @MainActor in RateAppManager.shared.recordMessageDelivered(from: senderKey) }
                }
                return
            }

            // Legacy fallback (peer sent no id): advance ONLY the oldest still-
            // unacked message to that peer — one receipt must not resolve
            // messages sent after it. `messages` is chronological (append order),
            // so firstIndex finds the oldest.
            if let i = self.messages.firstIndex(where: {
                MessageManager.canonicalIdentity($0.senderPublicKey) == me &&
                MessageManager.canonicalIdentity($0.recipientPublicKey) == senderKey &&
                $0.deliveryStatus == .sent
            }) {
                self.messages[i].deliveryStatus = .delivered
                self.saveMessages()
                self.objectWillChange.send()
                OshiLog.messaging.info("📬 Oldest unacked → DELIVERED (legacy receipt from \(senderKey.prefix(8))…)")
                Task { @MainActor in RateAppManager.shared.recordMessageDelivered(from: senderKey) }
            }
        }
    }

    /// 📸 Request profile from a contact (when we don't have their profile picture)
    func requestProfileFromContact(_ contactKey: String) {
        guard let identityManager = MessageManager.sharedIdentityManager else {
            OshiLog.messaging.info("❌ No identity manager for profile request")
            return
        }

        // Check if we already have their profile
        if ContactPresenceManager.shared.getProfileImage(publicKey: contactKey) != nil ||
           ContactPhotoManager.shared.getPhoto(for: contactKey) != nil {
            OshiLog.messaging.info("📸 Already have profile for \(contactKey.prefix(8))..., skipping request")
            return
        }

        OshiLog.messaging.info("📸 Requesting profile from \(contactKey.prefix(8))...")

        Task {
            do {
                let sharedSecret = try identityManager.computeSharedSecret(with: contactKey)
                let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                    with: contactKey,
                    ourPublicKey: identityManager.publicKey,
                    sharedSecret: sharedSecret
                )

                let requestContent = "📸PROFILE_REQUEST📸"
                let ratchetMessage = try session.encrypt(requestContent)
                let ratchetData = try JSONEncoder().encode(ratchetMessage)
                let encryptedMessage = try identityManager.wrapRatchetMessage(ratchetData, for: contactKey)

                let encryptedData = try JSONEncoder().encode(encryptedMessage)
                let base64Encrypted = encryptedData.base64EncodedString()

                await sendProfileUpdateViaCloud(
                    encryptedContent: base64Encrypted,
                    recipient: contactKey,
                    sender: identityManager.publicKey
                )

                OshiLog.messaging.info("📸 Profile request sent to \(contactKey.prefix(8))...")
            } catch {
                OshiLog.messaging.info("❌ Failed to send profile request: \(error)")
            }
        }
    }

    /// 📸 Send profile to a specific contact (used for first message, reply to profile request, etc.)
    /// Sends profile data only after the user has initiated contact.
    ///
    /// `initiatedByUser` is set by the first outgoing-message path, before its
    /// local bubble is persisted and so before `hasSentUserMessage` could see
    /// it — and, since 3 November 2026, by the two inbound PROFILE_REQUEST
    /// handlers. An explicit request is answered; an unsolicited broadcast to
    /// someone we have never replied to still is not. See
    /// __PROFILE_NEVER_REACHED_ANDROID_2026_11_03__ at those call sites for why
    /// and for the trade that choice makes.
    private func sendProfileToContact(
        _ contactKey: String,
        identityManager: IdentityManager,
        initiatedByUser: Bool = false
    ) async {
        guard initiatedByUser || hasSentUserMessage(to: contactKey, as: identityManager.publicKey) else {
            OshiLog.messaging.info("🔒 Profile withheld until user replies to \(contactKey.prefix(8))...")
            return
        }
        let payload = ContactPresenceManager.shared.createProfileUpdatePayload()

        guard let payloadData = try? JSONEncoder().encode(payload),
              let payloadString = String(data: payloadData, encoding: .utf8) else {
            OshiLog.messaging.info("❌ Failed to encode profile for \(contactKey.prefix(8))...")
            return
        }

        let profileMessageContent = "📸PROFILE_UPDATE📸\(payloadString)"

        do {
            let sharedSecret = try identityManager.computeSharedSecret(with: contactKey)
            let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                with: contactKey,
                ourPublicKey: identityManager.publicKey,
                sharedSecret: sharedSecret
            )

            let ratchetMessage = try session.encrypt(profileMessageContent)
            let ratchetData = try JSONEncoder().encode(ratchetMessage)
            let encryptedMessage = try identityManager.wrapRatchetMessage(ratchetData, for: contactKey)

            let encryptedData = try JSONEncoder().encode(encryptedMessage)
            let base64Encrypted = encryptedData.base64EncodedString()

            await sendProfileUpdateViaCloud(
                encryptedContent: base64Encrypted,
                recipient: contactKey,
                sender: identityManager.publicKey
            )

            OshiLog.messaging.info("📸 Profile sent to \(contactKey.prefix(8))...")
        } catch {
            OshiLog.messaging.info("❌ Failed to send profile to \(contactKey.prefix(8)): \(error)")
        }
    }

    private func hasSentUserMessage(to contactKey: String, as publicKey: String) -> Bool {
        messages.contains { message in
            let isOutgoing = (message.senderPublicKey == publicKey || message.senderAddress == publicKey) &&
                (message.recipientPublicKey == contactKey || message.recipientAddress == contactKey)
            guard isOutgoing else { return false }
            guard let content = message.plaintextContent else { return true }
            return !Self.meshReceiptControlPrefixes.contains(where: content.hasPrefix)
        }
    }

    // MARK: - Mesh Network Observers

    private func setupMeshNetworkObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(meshNetworkRestored),
            name: .meshNetworkRestored,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(meshNetworkLost),
            name: .meshNetworkLost,
            object: nil
        )

        // 🔧 FIX: Observer for CrossPlatformMesh messages (Android peers)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMeshMessageReceived(_:)),
            name: NSNotification.Name("MeshMessageReceived"),
            object: nil
        )

        // __LORA_ACK_2026_10_10__ The off-grid path could not report delivery.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleLoRaDeliveryConfirmed(_:)),
            name: .loRaDeliveryConfirmed,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleLoRaDeliveryFailed(_:)),
            name: .loRaDeliveryFailed,
            object: nil
        )
    }

    /// __LORA_ACK_2026_10_10__ The Meshtastic mesh confirmed a unicast message.
    ///
    /// This is a genuine end-to-end "it arrived" for the one transport that had
    /// no delivery signal at all: the radio's own ROUTING_APP acknowledgement.
    /// Advanced through `advanced(to:)` like every other status change, so it is
    /// monotonic and can never pull a `.read` message back to `.delivered` or
    /// revive a `.failed` one.
    @objc private func handleLoRaDeliveryConfirmed(_ notification: Notification) {
        guard let messageId = notification.userInfo?["messageId"] as? String,
              !messageId.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self,
                  let i = self.messages.firstIndex(where: { $0.id == messageId }) else { return }
            let current = self.messages[i].deliveryStatus
            let advanced = current.advanced(to: .delivered)
            guard advanced != current else { return }
            self.messages[i].deliveryStatus = advanced
            self.saveMessages()
            self.objectWillChange.send()
            OshiLog.messaging.info("📡 LoRa \(messageId.prefix(8))… → DELIVERED (mesh ACK)")
            // Demonstrated success, same as a peer receipt: the mesh confirmed
            // it reached the destination node.
            let peer = self.messages[i].recipientPublicKey
            Task { @MainActor in RateAppManager.shared.recordMessageDelivered(from: peer) }
        }
    }

    /// The radio named a failure — no route, timeout, full queue. Surfacing it
    /// is the point: the alternative is a bubble that says "sent" forever for a
    /// message the mesh has already given up on.
    ///
    /// Deliberately NOT marked `.failed` when the message had already reached
    /// `.delivered` or `.read` by another transport: a message racing LoRa and
    /// the relay must not be downgraded because the slower leg lost.
    @objc private func handleLoRaDeliveryFailed(_ notification: Notification) {
        guard let messageId = notification.userInfo?["messageId"] as? String,
              !messageId.isEmpty else { return }
        // `true` = the 90 s window ran out with no word at all (nobody in
        // range); `false` = the mesh itself named a failure (no route, queue
        // full). Both mean the same thing for routing — the peer did not get
        // it — and are logged apart because only one of them is diagnosable.
        let timedOut = notification.userInfo?["timedOut"] as? Bool ?? false
        DispatchQueue.main.async { [weak self] in
            guard let self,
                  let i = self.messages.firstIndex(where: { $0.id == messageId }) else { return }
            let current = self.messages[i].deliveryStatus
            guard current.rank < DeliveryStatus.delivered.rank else {
                OshiLog.messaging.info("📡 LoRa reported a failure for \(messageId.prefix(8))… but it is already \(current) — another transport delivered it")
                return
            }

            // __LORA_THEN_INTERNET_2026_10_27__ THE RADIO IS A FIRST CHOICE,
            // NOT A DEAD END.
            //
            // Until now this method's only move was `.failed` + tap-to-retry,
            // and its own comment defended that: "the user gets the choice
            // rather than the app silently re-spending LoRa airtime on a route
            // the mesh just said does not exist". That reasoning is right about
            // the RADIO and wrong about the INTERNET. Re-spending airtime would
            // indeed be pointless; handing the message to a working network
            // path is not — it is the whole reason "prefer my radio over the
            // internet" can be a preference rather than a sacrifice.
            //
            // So: radio first, and when the radio does not reach them, the
            // internet picks it up by itself. `excludeRadio` keeps the
            // escalation from re-entering the branches it is escalating out of.
            // Nothing is marked `.failed` before that attempt, because a
            // message about to go out over the relay has not failed.
            let message = self.messages[i]
            if self.escalateAfterLoRa(message, timedOut: timedOut) { return }

            self.messages[i].deliveryStatus = .failed
            self.saveMessages()
            self.objectWillChange.send()
            // No explicit re-queue: `.failed` is the state that carries the
            // tap-to-retry affordance in the bubble footer (see the
            // __BUBBLE_RUN_2026_08_18__ note on why `.failed` is exempt from
            // run-collapsing), so the user gets the choice rather than the app
            // silently re-spending LoRa airtime on a route the mesh just said
            // does not exist.
            OshiLog.messaging.info("📡 LoRa \(messageId.prefix(8))… → FAILED (\(timedOut ? "no ACK inside the window" : "mesh could not route it")), and no internet path to escalate to")
            RateAppManager.shared.recordFriction(reason: "lora-undeliverable")
        }
    }

    /// __LORA_THEN_INTERNET_2026_10_27__ The radio did not reach the peer. Send
    /// it over the internet instead, if there is an internet to send it over.
    ///
    /// - Returns: `true` when an escalation was started and the caller must NOT
    ///   mark the message failed; `false` when there is no network path, the
    ///   user forbade the relay (globally or in this one conversation), or the
    ///   message is one the relay cannot help with — in which case `.failed` is
    ///   the honest answer and the caller proceeds to set it.
    ///
    /// ⚠️ Deliberately does NOT fire for a GROUP message: `recipientPublicKey`
    /// is the only addressee the relay path takes, and a group envelope's goes
    /// out per member by the group send path, not here.
    ///
    /// The status is left exactly where LoRa left it (`.sent`) for the duration.
    /// That is honest — the bytes did leave over the radio — and it means the
    /// bubble does not flicker `failed` → `sent` under the user while a second
    /// transport is still working. The relay's own success and failure handling
    /// then owns the outcome, as it does for any other send.
    /// Not `@MainActor` despite only ever being called on the main queue: this
    /// class is NOT main-actor-isolated (the app target leaves
    /// `SWIFT_DEFAULT_ACTOR_ISOLATION` unset — only the Watch target sets it),
    /// so the `DispatchQueue.main.async` closure that calls this is a
    /// *nonisolated* context and could not call a main-actor method at all.
    /// `assumeIsolated` below carries the one main-actor read, exactly as
    /// `FileStorage` does for the same reason.
    @discardableResult
    private func escalateAfterLoRa(_ message: SecureMessage, timedOut: Bool) -> Bool {
        guard !message.recipientPublicKey.isEmpty else { return false }
        // Both switches folded into one answer: the app-wide guarantee and this
        // conversation's own offline-only setting. Someone who turned either off
        // asked for the message to WAIT, not to be put on the relay the moment
        // the radio came up short — that is the exact quiet re-routing
        // `__NEVER_OVER_THE_INTERNET_2026_10_20__` exists to prevent.
        let allowedHere = MainActor.assumeIsolated {
            TransportPolicy.internetAllowedForSending(inConversation: message.recipientPublicKey)
        }
        guard TransportPolicy.internetPicksUpAfterRadio(
                hasPhysicalPath: isNetworkAvailable && NetworkReachabilityManager.shared.hasPhysicalPath,
                internetAllowedHere: allowedHere,
                alreadyDelivered: message.deliveryStatus.rank >= DeliveryStatus.delivered.rank) else {
            OshiLog.messaging.info("📡 LoRa did not reach \(message.id.prefix(8))… and the relay may not pick it up (path=\(NetworkReachabilityManager.shared.hasPhysicalPath) allowedHere=\(allowedHere)) — it waits")
            return false
        }

        OshiLog.messaging.info("📡→🌐 LoRa \(timedOut ? "went unanswered" : "reported a failure") for \(message.id.prefix(8))… — escalating to the internet")
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.fallbackToIPFS(message: message, messageId: message.id,
                                              recipientPublicKey: message.recipientPublicKey,
                                              excludeRadio: true)
                OshiLog.messaging.info("📡→🌐 \(message.id.prefix(8))… left over the internet after the radio could not deliver it")
            } catch {
                // The relay could not take it either. NOW it has failed, and
                // `fallbackToIPFS` has already recorded that and queued the
                // retry — this branch only explains it in the log.
                OshiLog.messaging.info("📡→🌐 escalation of \(message.id.prefix(8))… failed on the internet too: \(error)")
            }
        }
        return true
    }

    @objc private func handleMeshMessageReceived(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let message = userInfo["message"] as? SecureMessage else {
            OshiLog.messaging.info("⚠️ MeshMessageReceived: Invalid message format")
            CallFileLogger.shared.log("DIAG_MSG_RX_NOTIF | invalid_message_format")
            return
        }

        OshiLog.messaging.info("📲 Processing MeshMessageReceived from CrossPlatformMesh")
        CallFileLogger.shared.log("DIAG_MSG_RX_NOTIF | id=\(message.id.prefix(8)) | from=\(message.senderPublicKey.prefix(12)) | hasPlaintext=\(message.plaintextContent != nil)")
        // Present only for an OSHI envelope reassembled off a LoRa radio; nil
        // for every other mesh transport. Carried through so the node → identity
        // binding can be made at the one point where it is PROVEN — after the
        // envelope decrypts. See LoRaNodeIdentityMap.
        let loraNodeNum = (notification.userInfo?["loraNodeNum"] as? NSNumber)?.uint32Value
        receiveMessage(message, loraNodeNum: loraNodeNum)
    }
    
    // 🔧 NEW: Dedicated mesh availability monitor - runs every 2 seconds
    private func startMeshAvailabilityMonitor() {
        meshAvailabilityTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.checkMeshAvailability()
        }
        
        if let timer = meshAvailabilityTimer {
            RunLoop.main.add(timer, forMode: .common)
        }
        
        // Initial check
        checkMeshAvailability()
        
        OshiLog.messaging.info("✅ Mesh availability monitor started (2s interval)")
    }
    
    // 🔧 NEW: Smart mesh availability checking
    private func checkMeshAvailability() {
        guard let meshManager = MessageManager.sharedMeshManager else {
            if isMeshAvailable {
                isMeshAvailable = false
                OshiLog.messaging.info("❌ Mesh unavailable: no mesh manager")
            }
            return
        }
        
        let wasAvailable = isMeshAvailable
        let hasConnectedPeers = !meshManager.connectedPeers.isEmpty
        let isAdvertising = meshManager.isAdvertising
        let isBrowsing = meshManager.isBrowsing
        
        // Mesh is available if we have peers AND networking is active.
        // 🔴 FLICKER FIX: publish ONLY on an actual change. `@Published` fires
        // objectWillChange on EVERY assignment (equal value or not), and this
        // runs every 2s forever. An unconditional write re-rendered every
        // observer of MessageManager — MessagesListView / ChatsTabView — twice a
        // second, re-installing `.navigationTitle` + `.searchable` and re-laying
        // the nav bar, which read as the "Messages" large title blinking.
        let newMeshAvailable = hasConnectedPeers && (isAdvertising || isBrowsing)
        if newMeshAvailable != wasAvailable {
            isMeshAvailable = newMeshAvailable
        }

        // Log only on changes
        if wasAvailable != isMeshAvailable {
            if isMeshAvailable {
                OshiLog.messaging.info("✅ MESH NOW AVAILABLE")
                OshiLog.messaging.info("   - Connected peers: \(meshManager.connectedPeers.count)")
                OshiLog.messaging.info("   - Advertising: \(isAdvertising)")
                OshiLog.messaging.info("   - Browsing: \(isBrowsing)")
                
                // Immediately try to send pending messages
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                    self?.retryPendingMeshMessages()
                }
            } else {
                OshiLog.messaging.info("❌ MESH NO LONGER AVAILABLE")
                OshiLog.messaging.info("   - Connected peers: \(meshManager.connectedPeers.count)")
                OshiLog.messaging.info("   - Advertising: \(isAdvertising)")
                OshiLog.messaging.info("   - Browsing: \(isBrowsing)")
            }
        }
        
        lastMeshCheck = Date()
    }
    
    // 🔧 NEW: Force mesh check if stale
    private func ensureFreshMeshStatus() {
        let timeSinceLastCheck = Date().timeIntervalSince(lastMeshCheck)
        if timeSinceLastCheck > meshCheckInterval {
            OshiLog.messaging.info("⚡ Forcing fresh mesh status check (last check \(String(format: "%.1f", timeSinceLastCheck))s ago)")
            checkMeshAvailability()
        }
    }
    
    // 🔧 IMPROVED: Handle mesh network restoration
    @objc private func meshNetworkRestored() {
        OshiLog.messaging.info("🎉 MESH NETWORK RESTORED NOTIFICATION")
        
        // Force immediate status update
        checkMeshAvailability()
        
        // Immediately try to send pending messages
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self else { return }
            OshiLog.messaging.info("🔄 Attempting to send \(self.pendingMeshMessages.count) pending messages")
            self.retryPendingMeshMessages()
        }
    }
    
    // Handle mesh network loss
    @objc private func meshNetworkLost() {
        OshiLog.messaging.info("⚠️ MESH NETWORK LOST - Will use IPFS for new messages")
        isMeshAvailable = false
    }
    
    // ✅ Call this when wallet manager becomes available
    func refreshConversations() {
        updateConversations()
        OshiLog.messaging.info("✅ Refreshed conversations: \(conversations.count)")
    }

    /// Add a pending conversation for a contact added via QR scan (no messages yet).
    /// This ensures the conversation survives updateConversations() rebuilds and
    /// appears in the conversation list so navigation can find it.
    func addPendingConversation(publicKey: String, identityManager: IdentityManager) {
        // __ANATOLE_1311_IOS__ (N18) Remember the contact BEFORE the early
        // return: a contact the user adds again (a re-scan, a group member they
        // explicitly add) is saved even when a row already exists, so it stays
        // listed if that thread's messages later go.
        if publicKey != identityManager.publicKey {
            ConversationPrefsStore.shared.markSaved(publicKey)
        }

        // Don't add if a real conversation already exists
        if conversations.contains(where: { $0.participantPublicKey == publicKey }) {
            OshiLog.messaging.info("📱 Conversation already exists for: \(publicKey.prefix(12))...")
            return
        }

        let conversation = Self.savedContactPlaceholder(publicKey: publicKey,
                                                        identityManager: identityManager,
                                                        date: Date())

        pendingConversations[publicKey] = conversation
        conversations.insert(conversation, at: 0)
        OshiLog.messaging.info("📱 Created PENDING conversation for QR contact: \(publicKey.prefix(12))...")
        // __EARLY_ASK_2026_10_09__ A contact the user deliberately added (QR,
        // pasted key, shared card). The early-return above means this only
        // fires for a peer we did not already have, and `recordContactAdded`
        // itself only arms the ask on the very first one.
        Task { @MainActor in RateAppManager.shared.recordContactAdded() }
    }

    /// __ANATOLE_1311_IOS__ (N18) The row for a saved contact nobody has
    /// written to yet. The placeholder carries no ciphertext (the row shows
    /// "New contact") and a DETERMINISTIC id: `updateConversations` only
    /// publishes when the list changes, and a fresh UUID on every rebuild would
    /// count as a change each time.
    static func savedContactPlaceholder(publicKey: String,
                                        identityManager: IdentityManager,
                                        date: Date) -> Conversation {
        let placeholderEncrypted = EncryptedMessage(
            ciphertext: "",
            signature: "",
            senderPublicKey: identityManager.publicKey,
            timestamp: date
        )
        let placeholderMessage = SecureMessage(
            id: "saved-contact-" + publicKey,
            senderAddress: identityManager.identityAddress,
            recipientAddress: publicKey,
            encryptedContent: placeholderEncrypted,
            timestamp: date,
            isRead: true,
            deliveryStatus: .delivered,
            senderPublicKey: identityManager.publicKey,
            recipientPublicKey: publicKey
        )
        return Conversation(
            participantAddress: publicKey,
            participantPublicKey: publicKey,
            lastMessage: placeholderMessage,
            unreadCount: 0
        )
    }
    
    // MARK: - Cleanup Functions
    
    private func cleanupUserDefaults() {
        OshiLog.messaging.info("🧹 Cleaning up UserDefaults...")
        
        let deprecatedKeys = [
            "doubleRatchetSessions",
            "doubleRatchetSessions_v2",
            "ratchetSessions",
            "sessions",
            "encryptedMessages",
            "pendingMessages",
            "messageQueue",
        ]
        
        var removedCount = 0
        
        for key in deprecatedKeys {
            if UserDefaults.standard.object(forKey: key) != nil {
                UserDefaults.standard.removeObject(forKey: key)
                removedCount += 1
                OshiLog.messaging.info("   🗑️ Removed: \(key)")
            }
        }
        
        UserDefaults.standard.synchronize()
        
        if removedCount > 0 {
            OshiLog.messaging.info("✅ Cleaned \(removedCount) key(s)")
        }
    }
    
    func clearRatchetSessions() {
        OshiLog.messaging.info("🔥 Starting complete session reset...")
        
        DoubleRatchetSessionManager.shared.resetAllSessions()
        
        messages.removeAll()
        conversations.removeAll()
        pendingConversations.removeAll()

        pendingDecryption.removeAll()
        failedDecryptionCount.removeAll()
        savePendingDecryption()

        let allKeys = Array(UserDefaults.standard.dictionaryRepresentation().keys)
        for key in allKeys {
            if key.lowercased().contains("ratchet") ||
               key.lowercased().contains("session") {
                UserDefaults.standard.removeObject(forKey: key)
                OshiLog.messaging.info("   🗑️ Deleted: \(key)")
            }
        }
        
        let keychainKeys = [
            "doubleRatchetSessions",
            "ratchetSessions",
            "sessions"
        ]
        
        for key in keychainKeys {
            try? KeychainHelper.delete(key: key)
            OshiLog.messaging.info("   🗑️ Attempted Keychain delete: \(key)")
        }
        
        UserDefaults.standard.synchronize()
        saveMessages()
        
        OshiLog.messaging.info("✅ Complete session reset finished!")
        OshiLog.messaging.info("✅ Sessions: \(DoubleRatchetSessionManager.shared.getSessionCount())")
        OshiLog.messaging.info("✅ Messages: \(messages.count)")
        OshiLog.messaging.info("⚠️ MUST close app completely (swipe up)")
        OshiLog.messaging.info("⚠️ MUST reopen app")
    }
    
    func clearRatchetSession(for publicKey: String) {
        pendingDecryption.removeValue(forKey: publicKey)
        failedDecryptionCount.removeValue(forKey: publicKey)
        savePendingDecryption()

        OshiLog.messaging.info("✅ Cleared pending queue for \(publicKey.prefix(10))...")
    }
    
    func completeReset() {
        DoubleRatchetSessionManager.shared.resetAllSessions()
        messages.removeAll()
        conversations.removeAll()
        pendingConversations.removeAll()
        ConversationPrefsStore.shared.forgetAllSaved()
        saveMessages()
        cleanupUserDefaults()
        OshiLog.messaging.info("✅ Complete reset!")
    }
    
    // MARK: - Polling
    
    private func startIPFSPolling() {
        OshiLog.messaging.info("🚀 Starting IPFS polling...")

        // Cancel existing timer FIRST before any new scheduling
        ipfsPollingTimer?.invalidate()
        ipfsPollingTimer = nil

        // 📱 MULTI-DEVICE: Delay initial poll if no groups (fresh device needs group sync first)
        // Groups check must run on MainActor — schedule it there
        DispatchQueue.main.async { [weak self] in
            let groupsEmpty = MessageGroupManager.shared.groups.isEmpty
            let initialDelay: TimeInterval = groupsEmpty ? 6.0 : 1.0
            DispatchQueue.main.asyncAfter(deadline: .now() + initialDelay) { [weak self] in
                OshiLog.messaging.info("⏰ Initial IPFS check (\(initialDelay)s after start)")
                self?.checkPendingIPFSMessages()
            }
        }
        
        // 🔧 v1.0.20 b40+: tightened from 2s → 1s for snappier message
        // delivery when iOS doesn't wake the app on content-available push.
        // The VPS GET /api/pending/{key} is ~1KB so doubling the poll rate
        // is negligible bandwidth. Multi-device sync still piggybacks but
        // its cadence (5s + 30s) is preserved by adjusting tick thresholds
        // (was 3 and %15; now 5 and %30 so wall-clock is unchanged).
        ipfsPollingTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] timer in
            // Throttle the per-tick log to once every 10s so the firehose
            // doesn't drown out call/notification diagnostics. Important
            // events (errors, message-arrival) still log unconditionally
            // from checkPendingIPFSMessages.
            if let self = self {
                self.syncPollCount += 1
                if self.syncPollCount % 10 == 0 {
                    // 🔧 FIX (main-thread stall): the DateFormatter used to be built on EVERY
                    // tick even though it is read once per ten. Allocating one costs a locale +
                    // ICU pattern lookup, and this runs on the main runloop at 1 Hz forever.
                    // Build it only when the line is actually emitted.
                    let formatter = DateFormatter()
                    formatter.timeStyle = .medium
                    OshiLog.messaging.info("⏰ IPFS poll tick #\(self.syncPollCount) at \(formatter.string(from: Date())) (1s cadence)")
                }
            }
            // 🔧 FIX (main-thread stall, 0x8BADF00D): `retryPendingDecryption: false`.
            //
            // MECHANISM: retryAllPendingDecryption() is synchronous and main-thread-bound —
            // decryptMessage -> getOrCreateSession -> `queue.sync` onto the ratchet serial
            // queue, plus a Keychain round-trip and a disk write per message. It is a no-op
            // while `pendingDecryption` is empty, but the moment a ratchet desync parks even
            // one message there, this timer replayed that whole pass on the main runloop
            // EVERY SECOND until the desync cleared — which is precisely the
            // `__NSFireTimer -> checkPendingIPFSMessages -> retryAllPendingDecryption` frame
            // at the top of all twelve watchdog reports.
            //
            // The dedicated `decryptionRetryTimer` (startPendingDecryptionRetry, 5 s) already
            // owns that replay, and every *event* entry point — APNs wake, foreground,
            // chat open — still passes the default `true` so a desynced message is retried
            // immediately when something actually happened. The only behaviour change is that
            // an idle 1 Hz poll no longer duplicates the 5 s timer, so the worst-case latency
            // for a desynced message goes from ~1 s to ~5 s (the documented 1.0.10-v6 bound).
            // Escape hatch: set `ipfsPollRetriesDecryptionEveryTick` = true to restore 1 Hz.
            let retryEveryTick = UserDefaults.standard.bool(forKey: "ipfsPollRetriesDecryptionEveryTick")
            self?.checkPendingIPFSMessages(retryPendingDecryption: retryEveryTick)

            // 📱 MULTI-DEVICE: piggyback sync — wall-clock cadence preserved
            // by doubling the tick thresholds (timer is 2× faster now).
            if let self = self {
                // First sync at 5s (tick 5), then every 30s (tick 30, 60, 90, …)
                if self.syncPollCount == 5 || self.syncPollCount % 30 == 0 {
                    MultiDeviceSyncManager.shared.performFullSync()
                }
            }
        }
        
        // Make sure timer runs on main RunLoop in common modes
        if let timer = ipfsPollingTimer {
            RunLoop.main.add(timer, forMode: .common)
            OshiLog.messaging.info("✅ IPFS polling started (10s interval for testing)")
            OshiLog.messaging.info("   Timer installed on RunLoop: \(timer.isValid)")
        } else {
            OshiLog.messaging.info("❌ Failed to create IPFS polling timer")
        }
    }
    
    // 🔧 IMPROVED: Mesh reconnection monitor focuses on retry logic
    private func startMeshReconnectionMonitor() {
        meshReconnectionTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            self?.retryPendingMeshMessages()  // Just retry, availability checked separately
        }
        
        if let timer = meshReconnectionTimer {
            RunLoop.main.add(timer, forMode: .common)
        }
        
        OshiLog.messaging.info("✅ Mesh reconnection monitor started (10s interval)")
    }
    
    private func startPendingDecryptionRetry() {
        // 🔧 FIX (1.0.10 v6): 30 s → 5 s. When a fresh inbound message hits a
        // ratchet desync (out-of-order key, session reset race), it gets queued
        // in pendingDecryption. The 30 s timer meant the user saw the "you have
        // a new message" push within 1 s but the message bubble took up to 30 s
        // to appear (and longer if the first retry also failed). Bumping to 5 s
        // brings the worst case down to ~5 s. Retry is no-op when the queue is
        // empty so this is essentially free in the common case.
        decryptionRetryTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            self?.retryAllPendingDecryption()
        }

        if let timer = decryptionRetryTimer {
            RunLoop.main.add(timer, forMode: .common)
        }
    }
    
    /// - Parameter force: ignorer l'attente de `nextDecryptionRetry`. Vrai pour
    ///   toute retentative declenchee par un EVENEMENT (reveil APNs, premier plan,
    ///   ouverture d'un fil); faux pour le balayage periodique, qui est le seul a
    ///   avoir besoin d'etre espace. __PHANTOM_BANNER_2026_10_11__
    private func retryAllPendingDecryption(force: Bool = false) {
        guard !pendingDecryption.isEmpty else { return }

        let now = Date()
        let senders = Array(pendingDecryption.keys).filter { sender in
            guard !force, let due = nextDecryptionRetry[sender] else { return true }
            return now >= due
        }
        guard !senders.isEmpty else { return }

        if force { senders.forEach { nextDecryptionRetry.removeValue(forKey: $0) } }

        OshiLog.messaging.info("🔄 Retrying \(senders.compactMap { pendingDecryption[$0]?.count }.reduce(0, +)) pending message(s) from \(senders.count) sender(s)\(force ? " (forced)" : "")...")

        for sender in senders {
            tryDecryptPendingMessages(from: sender)
        }
    }
    
    private func tryDecryptPendingMessages(from senderKey: String) {
        guard let pending = pendingDecryption[senderKey], !pending.isEmpty else { return }
        guard let identityManager = MessageManager.sharedIdentityManager else { return }

        OshiLog.messaging.info("🔓 Trying to decrypt \(pending.count) pending message(s) from \(senderKey.prefix(10))...")

        var decryptedIds: [String] = []

        for var pendingMessage in pending {
            if let decrypted = decryptMessage(pendingMessage, using: identityManager) {
                // 🔧 FIX (1.0.10): Route through receiveMessage instead of calling
                // addMessage directly. The previous path bypassed the local-notification
                // emit, web-session sync, and protocol-prefix filters — so a message that
                // arrived during a ratchet desync (queued + persisted in 1.0.9) would
                // eventually land in `messages` but the user got no in-app notification
                // and ChatView only updated if it happened to be on screen.
                //
                // Pre-setting plaintextContent makes the inner decryptMessage call short-
                // circuit (line 4493) so we don't try to consume the same ratchet step
                // twice and fail the second attempt.
                pendingMessage.plaintextContent = decrypted
                receiveMessage(pendingMessage)
                decryptedIds.append(pendingMessage.id)
                OshiLog.messaging.info("✅ Decrypted pending message (\(decrypted.count) chars)")

                failedDecryptionCount[senderKey] = 0
            }
        }
        
        if !decryptedIds.isEmpty {
            pendingDecryption[senderKey] = pending.filter { !decryptedIds.contains($0.id) }

            if pendingDecryption[senderKey]?.isEmpty == true {
                pendingDecryption.removeValue(forKey: senderKey)
            }
            // __PHANTOM_BANNER_2026_10_11__ Ca repart: on efface l'attente, le pair
            // redevient eligible au rythme normal.
            nextDecryptionRetry.removeValue(forKey: senderKey)
            failedDecryptionCount[senderKey] = 0
            savePendingDecryption()
            // L'instantane lu par l'extension ne doit plus annoncer ces ids.
            publishUndecryptableSnapshot()

            OshiLog.messaging.info("✅ Decrypted \(decryptedIds.count) pending message(s), \(pendingDecryption[senderKey]?.count ?? 0) remaining")
        } else {
            // __PHANTOM_BANNER_2026_10_11__ Rien n'est sorti de ce balayage. C'est
            // ICI qu'il faut compter: `failedDecryptionCount` n'etait incremente
            // nulle part, donc `maxFailedDecryptions` ne plafonnait rien et le
            // timer de 5 s rejouait le ratchet sur le thread principal pour
            // toujours.
            let failures = (failedDecryptionCount[senderKey] ?? 0) + 1
            failedDecryptionCount[senderKey] = failures
            let delay = decryptionRetryDelay(afterFailures: failures)
            nextDecryptionRetry[senderKey] = Date().addingTimeInterval(delay)

            if failures == maxFailedDecryptions {
                OshiLog.messaging.info("⏳ \(pending.count) message(s) from \(senderKey.prefix(10))… still undecryptable after \(failures) sweeps — backing the periodic retry off to \(Int(delay))s (event-driven retries unaffected)")

                // __MESH_MESSAGES_NEVER_HEALED_2026_11_02__ Reported as "on iOS
                // mesh messages work, but often they are not decrypted" — and
                // the reason is that NOTHING was repairing the session on this
                // path.
                //
                // OSHI has a thorough, Signal-shaped ratchet repair
                // (`RatchetHealSignal`), written for exactly this symptom. But
                // both of its triggers live inside NETWORK poll loops:
                // `v2PullAndProcess` builds the repair plans for v2 envelopes,
                // and `pollSingleQueue` resets a dead legacy chain. A message
                // that arrived over the RADIO reaches neither. It lands here
                // instead — parked, retried on a timer, for ever, against a
                // session that is broken and that nobody is mending. The retry
                // can only ever re-run the same failing decrypt.
                //
                // So register the parked ids as unreadable and ask. That reuses
                // the machinery as it stands, including its own per-peer
                // cooldown and resend caps, rather than inventing a second
                // repair that could drift from the first. It is a no-op when
                // the peer's build predates the resend list, and the request
                // rides the v2 lane — a peer we can reach by radio but not over
                // the internet still cannot be asked, which is a real limit of
                // the repair and not something this change pretends to solve.
                for message in pending {
                    V2SyncState.markUnreadable(msgId: message.id,
                                               from: senderKey,
                                               envelopeTs: message.timestamp)
                }
                Task { await MessageManager.requestPendingResends(from: senderKey) }
            }

            // La banniere a sonne, le message n'arrivera pas maintenant: retirer
            // la banniere orpheline plutot que la laisser dire qu'il y a quelque
            // chose a lire. Un renvoi reussi notifiera a nouveau.
            withdrawPhantomBanners(for: pending)
        }
    }

    /// __PHANTOM_BANNER_2026_10_11__ Retire les bannieres livrees pour des
    /// messages que l'app ne peut PAS afficher (gares en attente de
    /// dechiffrement), et republie la liste des ids gares pour que l'extension
    /// taise une poussee REPETEE sur le meme message.
    ///
    /// L'extension dessine la banniere avant de savoir s'il y a un message
    /// derriere — par construction, elle ne dechiffre pas. Quand l'app decouvre
    /// qu'il n'y a rien a montrer, personne ne retirait la banniere: c'est
    /// exactement « des notifications alors que je n'ai rien recu ».
    private func withdrawPhantomBanners(for parked: [SecureMessage]) {
        let ids = parked.map { $0.id }.filter { !$0.isEmpty }
        guard !ids.isEmpty else { return }
        NotificationManager.shared.withdrawBanners(forMessageIds: ids)
        publishUndecryptableSnapshot()
    }

    /// Publie dans l'App Group les ids encore gares, pour `NotificationService`.
    /// Reecrit l'ensemble a chaque fois: un id qui a fini par se dechiffrer
    /// disparait tout seul, sans entretien separe.
    private func publishUndecryptableSnapshot() {
        let ids = pendingDecryption.values.flatMap { $0 }.map { $0.id }
        // Hors du thread principal, pour la meme raison que `savePendingDecryption`:
        // l'appelant est dans le chemin de reception, et l'extension ne relit ce
        // fichier qu'a la prochaine poussee — rien n'attend cette ecriture.
        MessageManager.pendingDecryptionSaveQueue.async {
            AppGroupBridge.writeUndecryptableSnapshot(ids)
        }
    }
    
    // 🔧 IMPROVED: Always check mesh status first, better logging
    private func retryPendingMeshMessages() {
        // Always check mesh status first
        checkMeshAvailability()
        
        guard isMeshAvailable else {
            if !pendingMeshMessages.isEmpty {
                OshiLog.messaging.info("⏳ \(pendingMeshMessages.count) messages pending, but mesh not available yet")
            }
            return
        }
        
        guard !pendingMeshMessages.isEmpty else { return }
        guard let meshManager = MessageManager.sharedMeshManager else { return }
        
        guard !meshManager.connectedPeers.isEmpty else {
            OshiLog.messaging.info("⏳ Mesh manager exists but no connected peers yet")
            return
        }
        
        OshiLog.messaging.info("🔄 Mesh reconnected! Retrying \(pendingMeshMessages.count) pending message(s)...")
        
        let messagesToRetry = pendingMeshMessages
        pendingMeshMessages.removeAll()
        
        for (message, recipient) in messagesToRetry {
            Task {
                await retryMessageViaMesh(message: message, recipientAddress: recipient)
            }
        }
    }
    
    private func retryMessageViaMesh(message: SecureMessage, recipientAddress: String) async {
        guard let meshManager = MessageManager.sharedMeshManager else { return }

        // 🔧 FIX (false "Mesh" badge for far-away recipients):
        // Per-recipient reachability check — same guard the primary send
        // path uses at line ~4117. Without this, a random stranger walking
        // into Bluetooth range flips `isMeshAvailable` to true; the queue
        // replays for ALL pending messages; `meshManager.sendMessage`
        // doesn't error out when the specific pubkey is unreachable (it
        // just broadcasts to the local mesh hoping for a route); we then
        // wrongly flip the badge to `.mesh` even though the message was
        // already delivered via IPFS and the mesh send went nowhere.
        let crossPlatformReachable = CrossPlatformMesh.shared.isPeerLiveConnected(recipientAddress)
        let multipeerReachable = meshManager.isPeerConnected(publicKey: recipientAddress)
        guard crossPlatformReachable || multipeerReachable else {
            OshiLog.messaging.info("⏭️ Skipping mesh retry for \(recipientAddress.prefix(12))… — not a connected peer (CP=\(crossPlatformReachable), MP=\(multipeerReachable))")
            // Leave the entry queued so a future event with the ACTUAL
            // recipient connecting via mesh can deliver it. Cap the queue
            // separately if it grows.
            await MainActor.run {
                self.pendingMeshMessages.append((message, recipientAddress))
            }
            return
        }

        do {
            try meshManager.sendMessage(message, to: recipientAddress)
            OshiLog.messaging.info("✅ Queued message sent via mesh after reconnection!")

            await MainActor.run {
                if let index = self.messages.firstIndex(where: { $0.id == message.id }) {
                    self.messages[index].deliveryStatus = .sent
                    self.messages[index].deliveryMethod = .mesh
                    self.saveMessages()
                    // ⭐️ Queued-while-offline message flushed over the mesh.
                    RateAppManager.shared.recordMeshDeliverySucceeded()
                }
            }
        } catch {
            OshiLog.messaging.info("❌ Failed to send queued message: \(error)")
            await MainActor.run {
                self.pendingMeshMessages.append((message, recipientAddress))
            }
        }
    }
    
    // MARK: - IPFS Polling
    
    /// - Parameter retryPendingDecryption: replay the persisted pending-decryption queue
    ///   inline before polling. `true` for every *event* caller (APNs wake, foreground,
    ///   chat open) where the user is waiting on a specific message. The 1 Hz poll timer
    ///   passes `false` — see startIPFSPolling() for the mechanism.
    // __GROUP_SELF_HEAL_2026_10_01__ Fire once per app session: on the first poll after
    // launch, proactively ask peers for every group we belong to. This does not depend on a
    // (possibly stranded) group message being fetched first, so a device whose group list
    // was wiped recovers as soon as it is online with at least one sharing peer known.
    private var didLaunchGroupRecovery = false

    func checkPendingIPFSMessages(retryPendingDecryption: Bool = true) {
        OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        OshiLog.messaging.info("🔄 checkPendingIPFSMessages() CALLED at \(Date())")
        if !didLaunchGroupRecovery {
            didLaunchGroupRecovery = true
            // Generic resilience: once per session, ask known 1:1 peers to re-send any groups
            // we belong to, so a device that lost its group list recovers as members are reached.
            Task { @MainActor in self.requestAllGroupsRecovery() }
        }

        // 🔧 FIX (1.0.10): Replay any persisted pending-decryption queue every time
        // the app polls. Push notifications wake us into this method; without this
        // call, ratchet-desync messages waited up to 30 s for the periodic retry
        // timer before the user saw them in chat.
        if retryPendingDecryption {
            // __PHANTOM_BANNER_2026_10_11__ `force`: cet appel vient d'un
            // EVENEMENT (reveil APNs, premier plan, ouverture d'un fil). L'attente
            // exponentielle n'existe que pour calmer le balayage periodique; quand
            // il se passe vraiment quelque chose, on retente tout de suite.
            retryAllPendingDecryption(force: true)
        }

        // [V2] Refresh the remote config (kill switch / rollout %) — UNGATED so
        // a disabled client can be re-enabled — then pull the relay queue
        // (gated): pull → decrypt → persist → ack. Both throttled internally.
        v2ConfigTick()
        v2PullTick()

        Task {
            guard !isPollingInProgress else {
                OshiLog.messaging.info("⏭️ Polling already in progress, skipping")
                OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }
            isPollingInProgress = true
            defer { isPollingInProgress = false }

            guard let identityManager = MessageManager.sharedIdentityManager else {
                OshiLog.messaging.info("⏸️ No identity manager - waiting for initialization...")
                OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }

            let publicKey = identityManager.publicKey

            // 🔧 IMPORTANT: Verify public key is valid
            guard !publicKey.isEmpty else {
                OshiLog.messaging.info("⏸️ Public key is empty - identity not fully initialized")
                OshiLog.messaging.info("   isConnected: \(identityManager.isConnected)")
                OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }

            // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
            // Standard base64 +/= characters break URL paths even when encoded
            let encodedKey = base64urlEncode(publicKey)
            #if DEBUG
            OshiLog.messaging.info("🔑 Polling with key (\(publicKey.count) chars, prefix: \(publicKey.prefix(8))...)")
            #endif

            // Use nginx reverse proxy on port 80 (routes to internal port 8081)
            // Include deviceId for multi-device support (each device gets its own view of the queue)
            let deviceId = VoIPPushManager.getDeviceId()

            // 🔧 FIX: Also try percent-encoded standard base64 key
            // Bot server may queue with different encoding than base64url
            let percentEncodedKey = publicKey.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? publicKey

            // Poll with BOTH encodings to catch bot messages queued with different format
            // 🔧 PERF (2026-06-02): The percent-encoded fallback was tried on EVERY
            // poll as a safety net for bot messages queued under either encoding
            // — but the base64url path is the server's canonical form, so the
            // fallback returns HTTP 404 on every poll for users whose queue is
            // base64url-only. With a 2 s polling timer that's ~1 800 wasted HTTP
            // requests per hour (per the iPhone log), constantly waking the
            // cellular radio and burning battery. Now: always poll base64url
            // (primary); only poll the percent-encoded fallback every Nth tick
            // (~30 s cadence) — still catches anything legacy-queued promptly,
            // ~15× fewer wasted requests.
            let primaryKey = encodedKey.isEmpty ? percentEncodedKey : encodedKey
            var keysToTry: [String] = [primaryKey]
            pollFallbackCounter &+= 1
            if !percentEncodedKey.isEmpty,
               percentEncodedKey != primaryKey,
               pollFallbackCounter % 15 == 0 {
                keysToTry.append(percentEncodedKey)
            }

            for keyVariant in keysToTry {
                let queueURL = "\(currentVPSURL)/api/pending/\(keyVariant)?deviceId=\(deviceId)"
                if keysToTry.count > 1 {
                    OshiLog.messaging.info("🌐 Polling URL (\(keyVariant == encodedKey ? "base64url" : "percent-encoded fallback")): \(queueURL.prefix(80))...")
                } else {
                    OshiLog.messaging.info("🌐 Polling URL: \(queueURL)")
                }
                await pollSingleQueue(queueURL: queueURL, publicKey: publicKey, deviceId: deviceId)
            }

            OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    /// Poll a single VPS queue URL for pending messages
    private func pollSingleQueue(queueURL: String, publicKey: String, deviceId: String) async {
            OshiLog.messaging.info("🌐 Polling: \(queueURL.prefix(80))...")
            // DIAG_VPS_POLL_REQ — proves iPhone is polling for the right key.
            // Tail of the URL is the encoded public key + endpoint variant; the
            // first ~24 chars of `publicKey` lets us correlate with what Android
            // sent in DIAG_VPS_NOTIFY_REQ.
            CallFileLogger.shared.log("DIAG_VPS_POLL_REQ | pk=\(publicKey.prefix(24)) | urlTail=\(queueURL.suffix(80))")

            do {
                guard let url = URL(string: queueURL) else {
                    OshiLog.messaging.info("❌ Invalid URL!")
                    CallFileLogger.shared.log("DIAG_VPS_POLL_FAIL | reason=invalid_url | pk=\(publicKey.prefix(24))")
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    return
                }

                var request = URLRequest(url: url)
                request.timeoutInterval = 30  // 🔧 30s for large bot media responses
                // 🔒 __BOT_E2E_2026_09_23__ this build opens bot-seal-v1 envelopes.
                request.setValue(BotSealedEnvelope.capsHeaderValue, forHTTPHeaderField: BotSealedEnvelope.capsHeaderName)

                OshiLog.messaging.info("📡 Sending GET request to: \(queueURL.prefix(60))...")
                let (data, response) = try await URLSession.shared.data(for: request)

                guard let httpResponse = response as? HTTPURLResponse else {
                    OshiLog.messaging.info("❌ Not an HTTP response!")
                    CallFileLogger.shared.log("DIAG_VPS_POLL_FAIL | reason=not_http | pk=\(publicKey.prefix(24))")
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    return
                }

                OshiLog.messaging.info("📡 HTTP Status: \(httpResponse.statusCode)")
                CallFileLogger.shared.log("DIAG_VPS_POLL_RESP | code=\(httpResponse.statusCode) | bytes=\(data.count) | pk=\(publicKey.prefix(24))")

                let responseString = String(data: data, encoding: .utf8) ?? "No data"
                OshiLog.messaging.info("📡 Response size: \(data.count) bytes, preview: \(responseString.prefix(200))")
                
                // Accept 2xx success range, handle specific error codes
                guard (200...299).contains(httpResponse.statusCode) else {
                    if httpResponse.statusCode == 404 {
                        OshiLog.messaging.info("ℹ️ No pending messages (404)")
                    } else if httpResponse.statusCode == 429 {
                        OshiLog.messaging.info("⚠️ Rate limited (429) - will retry with backoff")
                    } else if httpResponse.statusCode >= 500 {
                        OshiLog.messaging.info("⚠️ Server error (\(httpResponse.statusCode)) - will retry")
                    } else {
                        OshiLog.messaging.info("⚠️ VPS returned \(httpResponse.statusCode)")
                        OshiLog.messaging.info("   Response: \(responseString.prefix(100))")
                    }
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    return
                }
                
                // 🔧 IMPROVED: Parse multiple possible response formats from VPS
                var hashesArray: [String] = []

                // Try to parse JSON
                guard let json = try? JSONSerialization.jsonObject(with: data) else {
                    OshiLog.messaging.info("⚠️ Failed to parse JSON response")
                    OshiLog.messaging.info("   Raw response: \(responseString.prefix(200))")
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    return
                }

                // Format 1: { "ipfsHashes": ["hash1", "hash2"] }
                if let jsonObject = json as? [String: Any],
                   let hashes = jsonObject["ipfsHashes"] as? [String] {
                    hashesArray = hashes
                    OshiLog.messaging.info("   📦 Parsed format: {ipfsHashes: [...]}")
                }
                // Format 2: { "hashes": ["hash1", "hash2"] }
                else if let jsonObject = json as? [String: Any],
                        let hashes = jsonObject["hashes"] as? [String] {
                    hashesArray = hashes
                    OshiLog.messaging.info("   📦 Parsed format: {hashes: [...]}")
                }
                // Format 3: ["hash1", "hash2"] - simple string array
                else if let simpleArray = json as? [String] {
                    hashesArray = simpleArray
                    OshiLog.messaging.info("   📦 Parsed format: [string array]")
                }
                // Format 4: [{"ipfsHash": "hash1"}, ...] - array of objects
                else if let objectArray = json as? [[String: Any]] {
                    for obj in objectArray {
                        // Try common key names for IPFS hash
                        if let hash = obj["ipfsHash"] as? String {
                            hashesArray.append(hash)
                        } else if let hash = obj["hash"] as? String {
                            hashesArray.append(hash)
                        } else if let hash = obj["cid"] as? String {
                            hashesArray.append(hash)
                        }
                    }
                    OshiLog.messaging.info("   📦 Parsed format: [{ipfsHash/hash/cid: ...}, ...]")
                    if hashesArray.isEmpty && !objectArray.isEmpty {
                        OshiLog.messaging.info("   ⚠️ Found \(objectArray.count) objects but no recognized hash keys")
                        OshiLog.messaging.info("   Object keys: \(objectArray.first?.keys.joined(separator: ", ") ?? "none")")
                    }
                }
                // Format 5: Empty response
                else {
                    OshiLog.messaging.info("   ⚠️ Unrecognized response format")
                    OshiLog.messaging.info("   JSON type: \(type(of: json))")
                    OshiLog.messaging.info("   Raw: \(responseString.prefix(200))")
                }

                guard !hashesArray.isEmpty else {
                    OshiLog.messaging.info("ℹ️ No pending messages or empty queue")
                    CallFileLogger.shared.log("DIAG_VPS_POLL_HASHES | count=0 | pk=\(publicKey.prefix(24))")
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    return
                }

                CallFileLogger.shared.log("DIAG_VPS_POLL_HASHES | count=\(hashesArray.count) | pk=\(publicKey.prefix(24)) | first=\(hashesArray.first?.prefix(16) ?? "")")
                OshiLog.messaging.info("📬 ════════════════════════════════════════════════════════════")
                OshiLog.messaging.info("📬 FOUND \(hashesArray.count) MESSAGE(S) ON VPS QUEUE!")
                OshiLog.messaging.info("📬 ════════════════════════════════════════════════════════════")
                for (i, hash) in hashesArray.enumerated() {
                    let preview = hash.count > 80 ? "\(hash.prefix(40))...(\(hash.count) chars)" : hash
                    OshiLog.messaging.info("📬 [\(i + 1)] \(preview)")
                }
                
                // 🔧 2026-06-11 (b54): parallel IPFS pre-fetch.
                //
                // Before this fix: the for-loop below processed each hash
                // SEQUENTIALLY — fetch IPFS (~0.5-3s) → decrypt → store →
                // next hash. For a 100-hash overnight backlog this took
                // 1.5-5 minutes during which messages trickled in.
                //
                // Now: fetch all (non-bot) blobs in PARALLEL with a small
                // concurrency cap (8 fetches at once is the sweet spot for
                // VPS proxy + cellular without exhausting URLSession's
                // connection pool), reading from the NSE App Group cache
                // first for any hashes the extension already grabbed when
                // the push arrived. Then we iterate the cached blobs in
                // the original order so per-pair Double Ratchet state is
                // advanced sequentially (decryption MUST stay sequential
                // per sender to preserve ratchet chain ordering).
                //
                // Expected speedup for a 100-message overnight backlog:
                // 5 min → ~10-15 s.
                let nonBotHashes = hashesArray.filter { !$0.hasPrefix("bot:") }
                var prefetchedBlobs: [String: Data] = [:]
                if !nonBotHashes.isEmpty {
                    OshiLog.messaging.info("⚡ Pre-fetching \(nonBotHashes.count) IPFS blob(s) in parallel (cap 8)...")
                    let fetchStart = Date()
                    prefetchedBlobs = await withTaskGroup(of: (String, Data?).self) { group in
                        var results: [String: Data] = [:]
                        let maxConcurrent = 8
                        var inflight = 0
                        var idx = 0
                        while idx < nonBotHashes.count {
                            while inflight < maxConcurrent && idx < nonBotHashes.count {
                                let hash = nonBotHashes[idx]
                                idx += 1
                                inflight += 1
                                group.addTask { [weak self] in
                                    // 1. NSE pre-fetched blob in App Group? (zero network)
                                    if let cached = AppGroupBridge.consumePrefetchedBlob(ipfsHash: hash) {
                                        return (hash, cached)
                                    }
                                    // 2. Otherwise network fetch via VPS proxy or IPFS gateway.
                                    if let raw = await self?.fetchIPFSBlobBytes(ipfsHash: hash) {
                                        return (hash, raw)
                                    }
                                    return (hash, nil)
                                }
                            }
                            if let (h, blob) = await group.next() {
                                inflight -= 1
                                if let blob { results[h] = blob }
                            }
                        }
                        for await (h, blob) in group {
                            if let blob { results[h] = blob }
                        }
                        return results
                    }
                    let dt = Date().timeIntervalSince(fetchStart)
                    OshiLog.messaging.info("⚡ Pre-fetch done in \(String(format: "%.1f", dt))s — \(prefetchedBlobs.count)/\(nonBotHashes.count) cached")
                }

                // 🔧 2026-06-11 (b54): collect VPS acks instead of awaiting each
                // one inside the loop. Every processed message used to block on
                // its own HTTP DELETE (~100-300 ms) before the next message could
                // decrypt — for an overnight backlog of ~100 messages that alone
                // serialized 10-30 s of round-trips AFTER the blobs were already
                // in hand, which the user perceived as messages "arriving in
                // batches". All acks now fire in parallel after the loop.
                var hashesToAck: [String] = []

                for hash in hashesArray {
                    #if DEBUG
                    OshiLog.messaging.info("━━ Processing Message ━━━━━━━━━━━━━━━━━━━━━━━━")
                    OshiLog.messaging.info("📥 IPFS hash: \(hash)")
                    #endif
                    
                    // 🤖 BOT MESSAGE: Handle bot: prefixed envelopes directly (not IPFS)
                    if hash.hasPrefix("bot:") {
                        OshiLog.messaging.info("🤖 Bot message detected in queue")
                        await processBotEnvelope(hash, publicKey: publicKey)
                        continue
                    }
                    
                    do {
                        // 🔧 2026-06-11 (b54): if the parallel pre-fetch
                        // already grabbed the encrypted blob, skip the HTTP
                        // round-trip entirely and go straight to processing.
                        // Drops typical per-message latency from 0.5-3s to
                        // ~10-50ms (decrypt-only).
                        let message: SecureMessage
                        if let cachedBlob = prefetchedBlobs[hash] {
                            OshiLog.messaging.info("   ⚡ Using pre-fetched blob (\(cachedBlob.count) bytes) — no HTTP")
                            message = try await processIPFSData(cachedBlob, ipfsHash: hash)
                        } else {
                            OshiLog.messaging.info("   🔄 Fetching from IPFS...")
                            message = try await fetchFromIPFS(ipfsHash: hash)
                        }
                        
                        #if DEBUG
                        OshiLog.messaging.info("   ✅ Fetched from IPFS successfully")
                        OshiLog.messaging.info("   📨 Message details:")
                        OshiLog.messaging.info("      Sender: \(message.senderPublicKey.prefix(8))...")
                        OshiLog.messaging.info("      Recipient: \(message.recipientPublicKey.prefix(8))...")
                        OshiLog.messaging.info("      Sender matches me: \(message.senderPublicKey == publicKey)")
                        OshiLog.messaging.info("      Recipient matches me: \(message.recipientPublicKey == publicKey)")
                        OshiLog.messaging.info("      Has plaintext: \(message.plaintextContent != nil)")
                        #endif

                        if message.senderPublicKey == publicKey {
                            OshiLog.messaging.info("   ⏭️ Skipping own sent message")
                            hashesToAck.append(hash)
                            continue
                        }

                        if message.recipientPublicKey != publicKey {
                            #if DEBUG
                            OshiLog.messaging.info("   ⚠️ MESSAGE NOT FOR US! (expected: \(publicKey.prefix(8))..., got: \(message.recipientPublicKey.prefix(8))...)")
                            #endif
                            hashesToAck.append(hash)
                            continue
                        }
                        
                        OshiLog.messaging.info("   ✅ Message IS for us! Processing...")
                        
                        // Skip call signals - they're already handled in processIPFSData
                        if let plaintext = message.plaintextContent,
                           plaintext.hasPrefix("📞CALL_SIGNAL📞") {
                            OshiLog.messaging.info("   ⏭️ Call signal already processed, skipping storage")
                            hashesToAck.append(hash)
                            continue
                        }

                        // Returns true when the message could NOT be decrypted and
                        // was parked in `pendingDecryption` (so we must keep it on
                        // the server for a later retry). Returns false on success,
                        // for already-stored duplicates, AND for successfully
                        // decrypted control messages (call signals / profile
                        // updates / receipts) that `receiveMessage` intentionally
                        // doesn't store — all of those are safe to ack/purge.
                        let decryptPending: Bool = await MainActor.run { [weak self] () -> Bool in
                            guard let self = self else {
                                OshiLog.messaging.info("   ❌ Self is nil")
                                return false
                            }

                            if let existingIndex = self.messages.firstIndex(where: { $0.id == message.id }) {
                                let existingMethod = self.messages[existingIndex].deliveryMethod?.rawValue ?? "none"
                                OshiLog.messaging.info("   ⏭️ Message already exists (delivery: \(existingMethod))")
                                return false
                            }

                            var updatedMessage = message
                            updatedMessage.deliveryMethod = .ipfs

                            OshiLog.messaging.info("   📨 Calling receiveMessage()...")
                            self.receiveMessage(updatedMessage)
                            OshiLog.messaging.info("   ✅ receiveMessage() completed")
                            OshiLog.messaging.info("   📊 Total messages now: \(self.messages.count)")

                            // Decryption failed iff receiveMessage parked it in the
                            // pending-decryption retry queue (keyed by sender).
                            return self.pendingDecryption[message.senderPublicKey]?
                                .contains(where: { $0.id == message.id }) ?? false
                        }

                        if decryptPending {
                            // 🔧 FIX: Keep the message on the VPS queue so it can be
                            // re-fetched after the ratchet re-syncs — instead of
                            // deleting it now (which produced "push banner, but no
                            // message" for the recipient). `retryAllPendingDecryption()`
                            // (run at the top of every poll) keeps retrying the parked
                            // copy meanwhile. Bound the retries so a permanently
                            // undecryptable envelope eventually stops re-fetching.
                            let attempts = (undecryptableFetchCount[hash] ?? 0) + 1
                            undecryptableFetchCount[hash] = attempts
                            if attempts >= maxUndecryptableFetches {
                                OshiLog.messaging.info("   🧹 Still undecryptable after \(attempts) fetches — purging from VPS queue")
                                CallFileLogger.shared.log("DIAG_MSG_UNDECRYPTABLE_PURGE | id=\(message.id.prefix(8)) | attempts=\(attempts)")
                                hashesToAck.append(hash)
                                undecryptableFetchCount.removeValue(forKey: hash)

                                // __LEGACY_RATCHET_DEAD_END_2026_08_15__
                                // Reaching here is a PROOF, not a guess: this envelope
                                // survived `maxUndecryptableFetches` fetches across
                                // several app runs and never decrypted. The legacy chain
                                // with this peer is dead — and until now we answered that
                                // by deleting the evidence and saying NOTHING. The peer's
                                // ratchet still believes it is in sync, so their NEXT
                                // message dies exactly the same way, and the one after
                                // that, forever: "internet messages never arrive", one
                                // direction only, with no error anywhere.
                                //
                                // MEASURED on the 14/17 pair: id 3061A792 from
                                // eAbMQUdW6Utu… burned attempts 4→8 in one minute and was
                                // purged, with no reset in either direction.
                                //
                                // Three things, in this order:
                                //   1. stop the 5 s retry timer grinding on the parked
                                //      copy of a message we just gave up on;
                                //   2. drop OUR half of the dead chain, so their next
                                //      message is treated as a fresh session;
                                //   3. tell the peer out-of-band. That signal is sealed
                                //      with an HKDF key from the X25519 shared secret,
                                //      NOT with either ratchet, so it lands precisely
                                //      when the ratchet is the broken thing. Its own
                                //      60 s per-peer cooldown keeps a burst of dead
                                //      envelopes from becoming a burst of signals.
                                // The v2 loop got this treatment today
                                // (`__V2_RATCHET_DEAD_END_2026_08_16__`); the legacy path
                                // is the other half of the same conversation.
                                let deadPeer = message.senderPublicKey
                                let deadId = message.id
                                await MainActor.run { [weak self] in
                                    guard let self,
                                          let identityManager = MessageManager.sharedIdentityManager else { return }
                                    if var parked = self.pendingDecryption[deadPeer] {
                                        parked.removeAll { $0.id == deadId }
                                        if parked.isEmpty {
                                            self.pendingDecryption.removeValue(forKey: deadPeer)
                                        } else {
                                            self.pendingDecryption[deadPeer] = parked
                                        }
                                        self.savePendingDecryption()
                                    }
                                    DoubleRatchetSessionManager.shared.resetSession(
                                        with: deadPeer,
                                        ourPublicKey: identityManager.publicKey
                                    )
                                    OshiLog.messaging.info("🔄 Dead legacy chain with \(deadPeer.prefix(10))… reset locally; asking the peer to reset too")
                                    CallFileLogger.shared.log("DIAG_RATCHET_DEAD_END | peer=\(deadPeer.prefix(12)) | id=\(deadId.prefix(8)) | action=reset_local+signal_peer")
                                    Task { [weak self] in
                                        await self?.sendSessionResetNotification(to: deadPeer, identityManager: identityManager)
                                    }
                                }
                            } else {
                                OshiLog.messaging.info("   ⏳ Decrypt pending (ratchet re-sync) — KEEPING on VPS, attempt \(attempts)/\(maxUndecryptableFetches)")
                                CallFileLogger.shared.log("DIAG_MSG_KEEP_ON_SERVER | id=\(message.id.prefix(8)) | from=\(message.senderPublicKey.prefix(12)) | attempt=\(attempts)/\(maxUndecryptableFetches)")
                            }
                            OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        } else {
                            undecryptableFetchCount.removeValue(forKey: hash)
                            hashesToAck.append(hash)
                            OshiLog.messaging.info("   ✅ Queued VPS ack (batched after loop)")

                            // ✅ CLEANUP: Trigger Pinata deletion for received message
                            PinataCleanupService.shared.trackReceivedMessage(
                                ipfsHash: hash,
                                senderPublicKey: message.senderPublicKey
                            )
                            OshiLog.messaging.info("   🗑️ Triggered Pinata cleanup for received message")

                            OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            OshiLog.messaging.info("✅ VPS MESSAGE SUCCESSFULLY RECEIVED!")
                            OshiLog.messaging.info("   From: \(message.senderPublicKey.prefix(8))...")
                            OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        }

                    } catch {
                        let nsError = error as NSError
                        let domain = nsError.domain

                        // Bot/Group messages are processed inside processIPFSData but "throw" to exit early
                        // We still need to mark them as received to clear the queue
                        // __BOT_INLINE_KEYBOARD_2026_09_16__ "BotCallbackAnswer" joins
                        // the list for the same reason: it is HANDLED (surfaced as a
                        // toast) and then thrown to exit early. Leaving it off would
                        // keep it on the queue, and the same toast would pop every
                        // two seconds forever.
                        if domain == "BotMessage" || domain == "BotCallbackAnswer" || domain == "Group" || domain == "Receipt" || domain == "GroupMessage" || domain == "RatchetReset" {
                            // 🔧 2026-06-09: include RatchetReset — the new ratchet-
                            // reset side-channel signal (isRatchetReset envelope) is
                            // processed inside processIPFSData and throws this domain
                            // so we mark+purge it from the server queue. Otherwise it
                            // would be re-fetched every poll cycle.
                            OshiLog.messaging.info("   ✅ \(domain) processed successfully, marking as received")
                            hashesToAck.append(hash)
                        } else if domain == "Envelope" || domain == "Payload" || domain == "IPFS" {
                            // 🔧 FIX (1.0.10 v6): Permanent processing failures — mark as received
                            // anyway so the message doesn't get re-fetched on every subsequent poll.
                            // Without this, an undecryptable envelope (sender key gone, group key
                            // missing, malformed payload, invalid hash) accumulated in the VPS queue
                            // and the recipient kept re-fetching it on every poll cycle (every 2 s),
                            // burying actual new messages under tens of permanent-failure round-trips
                            // and making real messages take 30 s–1 min to surface. The encrypted
                            // bytes are still on the device's pendingDecryption queue if they're
                            // recoverable later (ratchet state advance, contact added, etc.).
                            OshiLog.messaging.info("   ⚠️ Permanent processing failure (\(domain) code=\(nsError.code)) — marking as received to drain queue")
                            hashesToAck.append(hash)
                        } else {
                            // Network errors, gateway timeouts, etc. — leave in queue, will retry next poll.
                            OshiLog.messaging.info("   ❌ Transient fetch error (\(domain)): \(error.localizedDescription) — keeping in queue for retry")
                        }
                    }
                }
                
                // Fire all collected VPS acks in parallel. URLSession's
                // per-host connection pool (~6) throttles naturally. A failed
                // DELETE just leaves the hash on the queue — the next poll
                // re-fetches it, the local duplicate check short-circuits, and
                // the ack is retried. Awaiting here keeps isPollingInProgress
                // true so the 1 s poll timer can't re-fetch un-acked hashes
                // mid-flight.
                if !hashesToAck.isEmpty {
                    OshiLog.messaging.info("🧹 Acking \(hashesToAck.count) message(s) on VPS in parallel...")
                    let ackStart = Date()
                    await withTaskGroup(of: Void.self) { group in
                        for ackHash in hashesToAck {
                            group.addTask { [weak self] in
                                try? await self?.markAsReceived(ipfsHash: ackHash, publicKey: publicKey)
                            }
                        }
                    }
                    OshiLog.messaging.info("🧹 Acks done in \(String(format: "%.1f", Date().timeIntervalSince(ackStart)))s")
                }

            } catch {
                OshiLog.messaging.info("⏭️ IPFS poll error: \(error.localizedDescription)")
            }
    }

    // MARK: - IPFS Fetch with VPS Proxy (IP Protection)

    // MARK: - 🤖 Bot Message Processing
    
    /// Process a bot envelope from the VPS pending queue.
    /// Format: "bot:messageId:base64payload" where payload is JSON with bot message fields.
    private func processBotEnvelope(_ envelope: String, publicKey: String) async {
        msgLog("🤖 BOT ENVELOPE: \(envelope.prefix(60))...")
        
        let parts = envelope.split(separator: ":", maxSplits: 2).map(String.init)
        msgLog("🤖 Split parts count: \(parts.count)")
        
        guard parts.count == 3 else {
            msgLog("❌ [Bot] Expected 3 parts, got \(parts.count)")
            await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
            return
        }

        // 🔧 LAG FIX: skip the expensive payload/media decode if we've already
        // handled this bot messageId this session (it lingers in the queue because
        // a large inline-media envelope can't be ack'd via the URL-path DELETE).
        // `parts[1]` is the messageId — available WITHOUT decoding the big payload.
        let envelopeMessageId = parts[1]
        if seenBotMessageIds.contains(envelopeMessageId) {
            await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
            return
        }
        seenBotMessageIds.insert(envelopeMessageId)
        if seenBotMessageIds.count > 1000 { seenBotMessageIds.removeAll() }

        guard let payloadData = Data(base64Encoded: parts[2]) else {
            msgLog("❌ [Bot] Base64 decode failed for payload (\(parts[2].count) chars, prefix: \(parts[2].prefix(20)))")
            await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
            return
        }
        
        guard let outerJSON = try? JSONSerialization.jsonObject(with: payloadData) as? [String: Any] else {
            msgLog("❌ [Bot] JSON parse failed for payload (\(payloadData.count) bytes)")
            await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
            return
        }

        // 🔒 __BOT_E2E_2026_09_23__ bot-seal-v1 (ServerPatches/bot_e2e/BOT_SEAL_SPEC.md §2).
        // The server now queues bot messages as ciphertext sealed to our identity
        // X25519 key. Open it and hand the INNER legacy JSON to the unchanged
        // code below (bot_message AND bot_callback_answer). On any failure: drop
        // + ack, log the reason only — the outer JSON carries a placeholder
        // ("update OSHI") that this build must never display.
        let json: [String: Any]
        let sealKind: BotSealedEnvelope.SealKind
        if BotSealedEnvelope.isSealed(outerJSON) {
            guard let identityKey = MessageManager.sharedIdentityManager?.privateKey else {
                // Identity not loaded yet: do NOT ack — the next poll retries.
                seenBotMessageIds.remove(envelopeMessageId)
                msgLog("⏸️ [Bot] sealed envelope \(envelopeMessageId.prefix(8)) but identity key not loaded — retry later")
                return
            }
            do {
                json = try BotSealedEnvelope.open(outer: outerJSON, messageId: envelopeMessageId, privateKey: identityKey)
                sealKind = BotSealedEnvelope.label(inner: json, outer: outerJSON)
                msgLog("🔒 [Bot] sealed envelope \(envelopeMessageId.prefix(8)) opened (\(sealKind.rawValue))")
            } catch {
                msgLog("❌ [Bot] sealed envelope \(envelopeMessageId.prefix(8)) dropped: \(error)")
                await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
                return
            }
        } else {
            json = outerJSON
            sealKind = .legacyPlain
        }

        // 🤖 __BOT_INLINE_KEYBOARD_2026_09_16__ A `bot_callback_answer` shares the
        // bot queue but is NOT a message: it is the bot's reply to a button the
        // user just pressed, and it belongs in a toast, not in the thread. Read
        // the type BEFORE anything builds a `GroupMessage` out of it — otherwise
        // an answer lands in the conversation as a bot message with no text.
        if let queuedType = json["type"] as? String, queuedType == "bot_callback_answer" {
            let answerText = json["text"] as? String ?? ""
            let showAlert = (json["showAlert"] as? Bool) ?? ((json["showAlert"] as? NSNumber)?.boolValue ?? false)
            let answerBotName = json["botName"] as? String ?? "Bot"
            // __BOTS_PHASE1_2026_11_24__ S7 client obligation: a toast for a group
            // or channel this device is not in is dropped (rosters given to
            // addToGroup are not verifiable server-side).
            if let gid = (json["groupId"] as? String).flatMap(UUID.init(uuidString:)) {
                let isMine = await MainActor.run { MessageGroupManager.shared.groups.contains { $0.id == gid } }
                if !isMine {
                    msgLog("⚠️ [Bot] callback answer for a group we are not in — dropped")
                    await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
                    return
                }
            }
            msgLog("🤖 [Bot] callback answer: alert=\(showAlert), text=\(answerText.prefix(60))")
            await MainActor.run {
                BotManager.shared.presentCallbackAnswer(text: answerText,
                                                        showAlert: showAlert,
                                                        botName: answerBotName)
            }
            await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
            return
        }

        // __BOTS_PHASE1_2026_11_24__ The bot edited, deleted or (un)pinned one of
        // ITS OWN posts (editMessageText / editMessageReplyMarkup / deleteMessage
        // / pinChatMessage): the post changes in place, nothing new lands.
        // Contract S5: outer AND inner `type:"bot_message_action"`; the inner
        // botId must be the outer one. Anything malformed is dropped + acked.
        if (json["type"] as? String) == BotPostUpdate.type || (outerJSON["type"] as? String) == BotPostUpdate.type {
            if let update = BotPostUpdate.parse(json, outerBotId: outerJSON["botId"] as? String) {
                msgLog("🤖 [Bot] post action \(update.messageId.prefix(8)) in \(update.groupId.uuidString.prefix(8))")
                await MainActor.run { _ = MessageGroupManager.shared.applyBotPostUpdate(update) }
            } else {
                msgLog("⚠️ [Bot] malformed bot_message_action dropped")
            }
            await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
            return
        }

        let messageId = parts[1]
        let botName = json["botName"] as? String ?? "Bot"
        let content = json["content"] as? String ?? ""
        let groupId = json["groupId"] as? String ?? ""
        let timestamp: Date = {
            guard let ts = json["timestamp"] as? String, !ts.isEmpty else { return Date() }
            // Try with fractional seconds first, then without
            let f1 = ISO8601DateFormatter()
            f1.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let d = f1.date(from: ts) { return d }
            let f2 = ISO8601DateFormatter()
            f2.formatOptions = [.withInternetDateTime]
            if let d = f2.date(from: ts) { return d }
            return Date()
        }()
        let botToken = json["botToken"] as? String ?? ""
        
        msgLog("🤖 BOT MSG: name=\(botName), group=\(groupId.prefix(8)), msg=\(content.prefix(60))")
        
        // Parse optional media (accept both iOS "photo" and Android "image" naming)
        // Format 1: Top-level mediaType/mediaData fields (from /api/bot/send)
        var mediaTypeStr = json["mediaType"] as? String
        var mediaType: MediaType? = mediaTypeStr.flatMap {
            if let direct = MediaType(rawValue: $0) { return direct }
            if $0.lowercased() == "image" { return .photo }
            return nil
        }
        var mediaFileName = json["mediaFileName"] as? String
        var mediaData: Data? = nil
        var displayContent = content  // Text to show (may be overridden by caption)

        if let mediaB64 = json["mediaData"] as? String, !mediaB64.isEmpty {
            mediaData = Data(base64Encoded: mediaB64)
            msgLog("🤖 [Bot] Top-level media (inline): \(mediaTypeStr ?? "?") (\(mediaData?.count ?? 0) bytes)")
        }

        // Format 1b: mediaURL — server saved media to disk, provides relative path
        // e.g. mediaURL = "/api/media/uuid.jpg"
        if mediaData == nil, let mediaURL = json["mediaURL"] as? String, !mediaURL.isEmpty {
            let fullURL = "https://oshi-messenger.com\(mediaURL)"
            msgLog("🤖 [Bot] Downloading media from URL: \(fullURL)")
            if let url = URL(string: fullURL),
               let downloaded = try? Data(contentsOf: url), downloaded.count > 100 {
                mediaData = downloaded
                msgLog("🤖 [Bot] Downloaded media: \(downloaded.count / 1024) KB")
            } else {
                msgLog("⚠️ [Bot] Failed to download media from \(fullURL)")
            }
        }

        // Format 2: Embedded media JSON in content field (matches Android parsing)
        // e.g. content = '{"type":"media","mediaType":"photo","content":"base64...","caption":"text"}'
        if mediaType == nil,
           content.trimmingCharacters(in: .whitespaces).hasPrefix("{"),
           content.contains("\"type\":\"media\"") || content.contains("\"type\": \"media\""),
           let contentData = content.data(using: .utf8),
           let mediaJson = try? JSONSerialization.jsonObject(with: contentData) as? [String: Any] {
            let embeddedTypeStr = mediaJson["mediaType"] as? String ?? ""
            mediaType = {
                if let direct = MediaType(rawValue: embeddedTypeStr) { return direct }
                if embeddedTypeStr.lowercased() == "image" { return .photo }
                return nil
            }()
            mediaTypeStr = embeddedTypeStr
            if let b64 = mediaJson["content"] as? String, !b64.isEmpty {
                mediaData = Data(base64Encoded: b64)
            }
            if let fn = mediaJson["fileName"] as? String { mediaFileName = fn }
            // Use caption as display text instead of the raw JSON
            displayContent = mediaJson["caption"] as? String ?? ""
            msgLog("🤖 [Bot] Embedded media JSON: type=\(embeddedTypeStr) (\(mediaData?.count ?? 0) bytes) caption=\(displayContent.prefix(40))")
        }

        // Format 3: Image URL in content — download and display
        // Check if content contains an image URL (common for news bots)
        if mediaType == nil {
            let urlPattern = try? NSRegularExpression(pattern: "https?://[^\\s\"]+\\.(?:jpg|jpeg|png|gif|webp)(\\?[^\\s\"]*)?", options: .caseInsensitive)
            if let match = urlPattern?.firstMatch(in: content, range: NSRange(content.startIndex..., in: content)),
               let range = Range(match.range, in: content) {
                let imageURL = String(content[range])
                msgLog("🤖 [Bot] Found image URL in content: \(imageURL.prefix(60))")
                if let url = URL(string: imageURL),
                   let imageData = try? Data(contentsOf: url), imageData.count > 100 {
                    mediaType = .photo
                    mediaData = imageData
                    // Remove URL from display text
                    displayContent = content.replacingOccurrences(of: imageURL, with: "").trimmingCharacters(in: .whitespacesAndNewlines)
                    msgLog("🤖 [Bot] Downloaded image: \(imageData.count / 1024) KB")
                }
            }
        }

        // Build sender key with bot identity
        let senderKey = "bot:" + botToken.replacingOccurrences(of: "...", with: "")

        guard let groupUUID = UUID(uuidString: groupId) else {
            msgLog("❌ [Bot] Invalid groupId UUID: \(groupId)")
            await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
            return
        }

        // Create GroupMessage — use displayContent (caption/cleaned text) for plaintext
        let encryptedContentData: Data
        if mediaType != nil && mediaData != nil {
            // For media messages, store the display text (caption) in encryptedContent,
            // NOT the raw JSON or full content with URL
            encryptedContentData = Data(displayContent.utf8)
        } else {
            encryptedContentData = Data(content.utf8)
        }

        var groupMessage = GroupMessage(
            // __BOTS_PHASE1_2026_11_24__ stable for a non-UUID server id, so a
            // later edit/delete of this post finds it (was a random UUID).
            id: BotPostUpdate.localId(forServerId: messageId),
            groupId: groupUUID,
            senderPublicKey: senderKey,
            encryptedContent: encryptedContentData,
            timestamp: timestamp,
            isRead: false,
            messageChainIndex: 0,
            mediaType: mediaType,
            mediaFileName: mediaFileName,
            plaintextContent: "[\(botName)] \(displayContent)"
        )
        groupMessage.mediaData = mediaData
        let finalGroupMessage = groupMessage

        msgLog("🤖 [Bot] FINAL: mediaType=\(mediaType?.rawValue ?? "nil"), mediaData=\(mediaData?.count ?? 0) bytes, displayContent=\(displayContent.prefix(50))")

        // 🤖 __BOT_INLINE_KEYBOARD_2026_09_16__ The keyboard is stored BEFORE the
        // message so the bubble draws its buttons on the first render rather than
        // appearing bare and then growing them a frame later.
        //
        // Keyed by the LOCAL message UUID because that is what the bubble knows;
        // the server's own `messageId` travels inside the record, since
        // `UUID(uuidString:) ?? UUID()` above turns a non-UUID server id into a
        // random one and posting THAT back to /api/bot/dispatch would name a
        // message the server has never heard of.
        if let keyboard = BotInlineKeyboard.parse(json["replyMarkup"]) {
            // __BOT_PUBLIC_ID_2026_09_16__ `botId` is the public, one-way id the
            // server publishes on every bot envelope. It travels with the keyboard
            // because it is what the button will present when it is pressed —
            // possibly days later, from a record read back off disk.
            let botId = (json["botId"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let record = BotReplyMarkupRecord(
                keyboard: keyboard,
                sourceMessageId: messageId,
                groupId: groupUUID.uuidString,
                botTokenPrefix: botToken.isEmpty ? nil : botToken,
                botName: botName,
                botId: (botId?.isEmpty == false) ? botId : nil
            )
            let localId = finalGroupMessage.id.uuidString
            msgLog("🤖 [Bot] replyMarkup: \(keyboard.rows.count) row(s) for \(localId.prefix(8))")
            await MainActor.run {
                BotManager.shared.storeReplyMarkup(record, forMessageId: localId)
            }
        }

        // Store via GroupManager
        let stored = await MainActor.run { () -> Bool in
            let mgr = MessageGroupManager.shared

            // Check if we have this group locally
            guard mgr.groups.contains(where: { $0.id == groupUUID }) else {
                let knownIds = mgr.groups.map { "\($0.name)(\($0.id.uuidString.prefix(8)))" }.joined(separator: ", ")
                self.msgLog("⚠️ [Bot] Group \(groupId.prefix(8)) NOT FOUND locally. Known groups: [\(knownIds)]")
                // 🔒 REMOVED (2026-08-04): this used to POST the user's group
                // NAMES and IDs to /api/bot/diagnostic to help debug a mismatch.
                //
                // The whole group stack is built so the server never learns
                // group metadata — names and membership stay on device — and
                // this quietly shipped exactly that to the server in cleartext,
                // for every user who hit the mismatch, forever. A debugging
                // convenience is not worth contradicting the product's central
                // claim, and the local log line above already records everything
                // needed to diagnose it from a device log.
                return false
            }

            // 🔒 __BOT_E2E_2026_09_23__ Remember how this group's bot messages
            // arrive, for the honest caption under bot bubbles (spec §3).
            BotSealedEnvelope.recordKind(sealKind, groupId: groupUUID)
            // __BOTS_PHASE1_2026_11_24__ this bot serves this group: its commands
            // join the `/` menu and a typed `/cmd` is dispatched to it.
            if let botId = json["botId"] as? String, !botId.isEmpty {
                GroupBotDirectory.shared.note(botId: botId, name: botName, groupId: groupUUID)
                BotPostAuthors.record(botId: botId, localId: finalGroupMessage.id)
            }

            let existingMessages = mgr.groupMessages[groupUUID] ?? []
            if existingMessages.contains(where: { $0.id == finalGroupMessage.id }) {
                msgLog("⏭️ [Bot] Duplicate message \(messageId.prefix(8)), skipping")
                return true // already stored, safe to clear
            }
            mgr.receiveGroupMessage(finalGroupMessage)
            let newCount = mgr.groupMessages[groupUUID]?.count ?? -1
            msgLog("🤖 BOT STORED: id=\(messageId.prefix(8)), group=\(groupId.prefix(8)), total=\(newCount)")
            // Note: receiveGroupMessage() already posts NewGroupMessage notification + objectWillChange
            return true
        }

        // ✅ FIX: Always clear bot messages from queue to prevent infinite cycling
        // If group doesn't exist locally, the message can't be stored anyway —
        // keeping it in the queue just causes endless reprocessing every 2 seconds
        await clearBotFromQueue(envelope: envelope, publicKey: publicKey)
        if stored {
            msgLog("✅ [Bot] Message processed and cleared from queue")
        } else {
            msgLog("⚠️ [Bot] Group not found — message cleared to prevent queue cycling")
        }
    }
    
    /// Remove a bot envelope from the VPS pending queue.
    /// 🔧 FIX (2026-06-02): Bot envelopes are `bot:<messageId>:<base64-payload>`
    /// and when the payload inlines media (e.g. a 600 KB photo) the envelope is
    /// hundreds of KB — too long for the URL-path DELETE, which silently failed
    /// and let the envelope re-stream every poll (8+ MB downloads). Now uses the
    /// short-id endpoint POST /api/bot-received/{key} {messageId, deviceId}.
    /// Falls back to the legacy DELETE for non-bot envelopes (and as a safety
    /// net if the new endpoint isn't deployed yet — gracefully degrades).
    private func clearBotFromQueue(envelope: String, publicKey: String) async {
        let encodedKey = base64urlEncode(publicKey)
        let deviceId = VoIPPushManager.getDeviceId()

        // Bot envelope → use the short-id endpoint (safe for any envelope size).
        if envelope.hasPrefix("bot:") {
            let parts = envelope.split(separator: ":", maxSplits: 2).map(String.init)
            if parts.count >= 2, !parts[1].isEmpty {
                let messageId = parts[1]
                let fullURL = "\(currentVPSURL)/api/bot-received/\(encodedKey)"
                if let url = URL(string: fullURL) {
                    var req = URLRequest(url: url)
                    req.httpMethod = "POST"
                    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    let body: [String: String] = ["messageId": messageId, "deviceId": deviceId]
                    req.httpBody = try? JSONSerialization.data(withJSONObject: body)
                    do {
                        let (_, resp) = try await URLSession.shared.data(for: req)
                        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
                        msgLog("🤖 [Bot] bot-received POST status=\(status) for messageId=\(messageId.prefix(8))")
                        if status >= 200 && status < 300 { return }   // success → done
                        // Non-2xx → fall through to the legacy DELETE as a best-effort fallback.
                        msgLog("⚠️ [Bot] bot-received non-2xx — falling back to legacy DELETE")
                    } catch {
                        msgLog("⚠️ [Bot] bot-received failed (\(error.localizedDescription)) — falling back to legacy DELETE")
                    }
                }
            }
        }

        // Legacy DELETE path (non-bot envelopes, OR bot fallback when new endpoint unavailable).
        guard let encodedHash = envelope.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) else {
            msgLog("❌ [Bot] Failed to percent-encode envelope for markAsReceived")
            return
        }
        let fullURL = "\(currentVPSURL)/api/received/\(encodedKey)/\(encodedHash)?deviceId=\(deviceId)"
        guard let url = URL(string: fullURL) else {
            msgLog("❌ [Bot] Invalid URL for markAsReceived: \(fullURL.prefix(80))")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse {
                msgLog("🤖 [Bot] markAsReceived HTTP \(http.statusCode)")
            }
        } catch {
            msgLog("⚠️ [Bot] markAsReceived failed: \(error.localizedDescription)")
        }
    }
    
    /// 🔧 2026-06-11 (b54): bytes-only IPFS fetcher used by the parallel
    /// pre-fetch loop. Tries VPS proxy first (private), then public
    /// gateways. Returns nil on any failure — the caller will fall back to
    /// the legacy sequential `fetchFromIPFS` path for that hash.
    /// No decryption, no cache writes, no side effects.
    private func fetchIPFSBlobBytes(ipfsHash: String) async -> Data? {
        // Skip non-IPFS sentinels.
        guard !ipfsHash.hasPrefix("call_") else { return nil }
        guard (ipfsHash.hasPrefix("Qm") && ipfsHash.count >= 46) ||
              (ipfsHash.hasPrefix("bafy") && ipfsHash.count >= 59) else { return nil }

        if useVPSProxyForIPFS {
            let proxyURLString = "\(vpsIPFSProxyURL)\(ipfsHash)"
            if let url = URL(string: proxyURLString) {
                var req = URLRequest(url: url)
                req.timeoutInterval = 5
                if let (data, resp) = try? await URLSession.shared.data(for: req),
                   let http = resp as? HTTPURLResponse,
                   http.statusCode == 200,
                   !data.isEmpty {
                    return data
                }
            }
        }
        // No gateway fallback: our CIDs exist only on the VPS, never on the IPFS DHT.
        return nil
    }

        private func fetchFromIPFS(ipfsHash: String) async throws -> SecureMessage {
        // Skip fake call signal IDs (not real IPFS hashes)
        // Real IPFS hashes start with "Qm" (CIDv0) or "bafy" (CIDv1)
        if ipfsHash.hasPrefix("call_") {
            OshiLog.messaging.info("⚠️ Skipping fake call signal ID: \(ipfsHash) - not a real IPFS hash")
            // Mark as received to remove from queue
            if let identityManager = MessageManager.sharedIdentityManager {
                try? await markAsReceived(ipfsHash: ipfsHash, publicKey: identityManager.publicKey)
            }
            throw NSError(domain: "IPFS", code: 400, userInfo: [NSLocalizedDescriptionKey: "Invalid IPFS hash format"])
        }

        // Validate IPFS hash format
        let isValidHash = ipfsHash.hasPrefix("Qm") && ipfsHash.count >= 46 ||
                          ipfsHash.hasPrefix("bafy") && ipfsHash.count >= 59

        if !isValidHash {
            OshiLog.messaging.info("⚠️ Invalid IPFS hash format: \(ipfsHash.prefix(20))...")
            // Mark as received to remove from queue
            if let identityManager = MessageManager.sharedIdentityManager {
                try? await markAsReceived(ipfsHash: ipfsHash, publicKey: identityManager.publicKey)
            }
            throw NSError(domain: "IPFS", code: 400, userInfo: [NSLocalizedDescriptionKey: "Invalid IPFS hash format"])
        }

        if let cached = ipfsCache[ipfsHash],
           let cacheTime = ipfsCacheTimestamps[ipfsHash],
           Date().timeIntervalSince(cacheTime) < ipfsCacheTimeout {
            OshiLog.messaging.info("✅ Using cached IPFS data for \(ipfsHash.prefix(20))...")
            return cached
        }

        var lastError: Error?

        // 🔧 FIX (1.0.10 v5): VPS proxy ONLY (reverted from the v4 race that
        // also called Pinata directly — that doubled Pinata traffic for every
        // received message and would burn through the gateway quota). Kept the
        // tighter 5 s timeout so a sluggish VPS path falls back to direct
        // gateways quickly instead of blocking the chat for 30 s.
        if useVPSProxyForIPFS {
            let proxyFullURL = "\(vpsIPFSProxyURL)\(ipfsHash)"
            OshiLog.messaging.info("🔐 Fetching IPFS via VPS proxy (IP protected)...")
            OshiLog.messaging.info("🔐 VPS Proxy URL: \(proxyFullURL)")

            if let proxyURL = URL(string: proxyFullURL) {
                do {
                    var request = URLRequest(url: proxyURL)
                    request.timeoutInterval = 3  // 🔧 FIX (1.0.10 v6): tightened from 5 → 3.
                    // VPS proxy is the primary path (no Pinata client traffic on the
                    // happy path), but if it doesn't return data in 3 s, fall through
                    // to direct gateways. Previous 5 s left the chat blank for ~5 s
                    // any time the VPS path was sluggish (Pinata cold cache behind
                    // the proxy, VPS-side queue, etc.). 3 s covers a healthy VPS
                    // round-trip with margin.

                    let (data, response) = try await URLSession.shared.data(for: request)

                    if let httpResponse = response as? HTTPURLResponse {
                        OshiLog.messaging.info("🔐 VPS Proxy HTTP Status: \(httpResponse.statusCode)")
                        if httpResponse.statusCode == 200 && !data.isEmpty {
                            OshiLog.messaging.info("✅ Downloaded \(data.count) bytes via VPS proxy (IP hidden)")
                            return try await processIPFSData(data, ipfsHash: ipfsHash)
                        } else if httpResponse.statusCode == 404 {
                            OshiLog.messaging.info("⚠️ VPS proxy returned 404 - route may not be configured")
                        } else {
                            OshiLog.messaging.info("⚠️ VPS proxy returned \(httpResponse.statusCode), falling back to direct gateways")
                        }
                    } else {
                        OshiLog.messaging.info("⚠️ VPS proxy returned non-HTTP response")
                    }
                } catch {
                    OshiLog.messaging.info("⚠️ VPS proxy failed: \(error.localizedDescription)")
                    lastError = error
                }
            } else {
                OshiLog.messaging.info("⚠️ Invalid VPS proxy URL: \(proxyFullURL)")
                lastError = NSError(domain: "IPFS", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid proxy URL"])
            }
        }

        // No public-gateway fallback (removed 2026-07-17): our blobs live only on the
        // VPS and are never published to the IPFS DHT, so every public gateway was a
        // guaranteed miss that cost ~30 s of timeouts and leaked the CID + user IP.
        OshiLog.messaging.info("❌ VPS IPFS proxy could not serve \(ipfsHash)")
        throw lastError ?? NSError(domain: "IPFS", code: -1)
    }
    
    // 🔍 DEBUG: File logger for message processing
    private func msgLog(_ msg: String) {
        // 🔧 LOG SPAM FIX: file-write removed. Earlier this method appended
        // every bot/message event to `msg_debug.log` in the Documents folder,
        // which grew without bound (25K+ lines per session observed in the
        // wild) and saturated device storage / RAM. System log via OshiLog
        // is sufficient — Console.app and Xcode logs keep history.
        OshiLog.messaging.info("\(msg)")
        return
        #if false
        // Original file-append path kept as dead code for reference; remove
        // once we're confident OshiLog covers the diagnostic needs.
        let ts = ISO8601DateFormatter().string(from: Date())
        let line = "[\(ts)] \(msg)\n"
        if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let logFile = docs.appendingPathComponent("msg_debug.log")
            if let data = line.data(using: .utf8) {
                if FileManager.default.fileExists(atPath: logFile.path) {
                    if let handle = try? FileHandle(forWritingTo: logFile) {
                        handle.seekToEndOfFile()
                        handle.write(data)
                        handle.closeFile()
                    }
                } else {
                    try? data.write(to: logFile)
                }
            }
        }
        #endif
    }

    private func processIPFSData(_ data: Data, ipfsHash: String) async throws -> SecureMessage {
        msgLog("📦 processIPFSData: \(data.count) bytes, hash=\(ipfsHash.prefix(12))")

        guard !data.isEmpty else {
            throw NSError(domain: "IPFS", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Empty data"])
        }

        // ✅ Check for simple group message format first
        if let messageDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let messageType = messageDict["type"] as? String,
           messageType == "group_message" {
            msgLog("👥 Detected simple group message format")
            try await processSimpleGroupMessage(messageDict, ipfsHash: ipfsHash)
            throw NSError(domain: "Group", code: 0, userInfo: [NSLocalizedDescriptionKey: "Group message processed"])
        }

        // ✅ FIX: Handle member_added/member_removed from IPFS (lightweight join notifications)
        if let messageDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let messageType = messageDict["type"] as? String,
           (messageType == "member_added" || messageType == "member_removed" || messageType == "member_sync_request") {
            msgLog("📥 Detected \(messageType) from IPFS — forwarding to GroupManager")
            await MainActor.run {
                NotificationCenter.default.post(
                    name: NSNotification.Name("CloudGroupUpdateReceived"),
                    object: nil,
                    userInfo: ["groupData": data]
                )
            }
            throw NSError(domain: "Group", code: 0, userInfo: [NSLocalizedDescriptionKey: "\(messageType) processed"])
        }

        // ✅ FIX: Handle group_update from IPFS (member list changes, name changes, etc.)
        if let messageDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let messageType = messageDict["type"] as? String,
           messageType == "group_update",
           let base64Data = messageDict["data"] as? String,
           let groupData = Data(base64Encoded: base64Data) {
            msgLog("📥 Detected group_update from IPFS (\(groupData.count) bytes)")
            await MainActor.run {
                NotificationCenter.default.post(
                    name: NSNotification.Name("CloudGroupUpdateReceived"),
                    object: nil,
                    userInfo: ["groupData": groupData]
                )
            }
            throw NSError(domain: "Group", code: 0, userInfo: [NSLocalizedDescriptionKey: "Group update processed"])
        }

        // CV-002 — group envelope v3, which has a different SHAPE (`wraps`, no
        // `envelope`) and so must be recognised before the v2 decode below.
        //
        // IPFS stays as the fallback when the relay is down, so this path is not
        // going away and the old derived-from-groupId key had to be closed here
        // too — a pinned object is public and durable, which is what made that
        // scheme worth fixing in the first place.
        //
        // Unlike the mesh there is no transport-level sender here, and the
        // envelope does not name one; `openWithoutKnownSender` uses the sessionId
        // inside our own wrap instead.
        if let sealed = GroupEnvelopeV3.fromPinned(data), sealed.v == GroupEnvelopeV3.version {
            let myKey = MessageManager.sharedIdentityManager?.publicKey ?? ""
            do {
                if let payloadData = try GroupEnvelopeV3.openWithoutKnownSender(sealed, asMember: myKey) {
                    let payload = try JSONDecoder().decode(MessagePayload.self, from: payloadData)
                    msgLog("✅ group v3 envelope opened (\(sealed.wraps.count) wrap(s))")
                    return try await processDecryptedPayload(payload, ipfsHash: ipfsHash)
                }
                // Not addressed to us: a group we are not in. Ordinary, and it
                // must stay quiet — the enclosing function signals "nothing to
                // hand back" by throwing, as the group-update branch above does.
                msgLog("📡 group v3: not addressed to us")
                throw NSError(domain: "Group", code: 0,
                              userInfo: [NSLocalizedDescriptionKey: "Group v3 envelope not for us"])
            } catch {
                // Addressed to us but unopenable — a drifted 1:1 ratchet. The
                // existing heal path owns the reset; retrying these bytes against
                // the same state would only burn the same budget.
                msgLog("⚠️ group v3: wrap undecryptable — 1:1 heal required")
                throw NSError(domain: "Group", code: 0,
                              userInfo: [NSLocalizedDescriptionKey: "Group v3 wrap undecryptable"])
            }
        }

        // ✅ NEW: Check for encrypted envelope (v2)
        if let messageDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let version = messageDict["v"] as? Int, version >= 2 {
            msgLog("🔐 Detected encrypted envelope v\(version), hint=\(messageDict["hint"] as? String ?? "none")")

            // 🔍 DEBUG: Try decryption with detailed logging
            let groupsSnapshot = await MainActor.run { MessageGroupManager.shared.groups }
            msgLog("🔐 Groups available: \(groupsSnapshot.count) — \(groupsSnapshot.map { "\($0.id.uuidString.prefix(8)):\($0.name)" })")

            let envelope = EncryptedEnvelope(
                v: version,
                envelope: messageDict["envelope"] as? String ?? "",
                hint: messageDict["hint"] as? String,
                nonce: messageDict["nonce"] as? String ?? ""
            )

            for group in groupsSnapshot {
                let groupKey = deriveGroupKey(groupId: group.id.uuidString)
                msgLog("🔐 Trying group \(group.name) (id=\(group.id.uuidString.prefix(8)), keyHash=\(groupKey.prefix(4).map { String(format: "%02x", $0) }.joined()))")
                do {
                    let payload = try EnvelopeCrypto.decryptGroup(envelope: envelope, groupKey: groupKey)
                    msgLog("✅ DECRYPTED! type=\(payload.type), sender=\(payload.senderKey.prefix(12)), groupId=\(payload.groupId ?? "nil")")
                    return try await processDecryptedPayload(payload, ipfsHash: ipfsHash)
                } catch {
                    msgLog("❌ Decrypt failed for \(group.name): \(error.localizedDescription)")
                }
            }

            msgLog("⚠️ No group key matched, trying 1-on-1 decryption...")
            return try await processEncryptedEnvelope(messageDict, ipfsHash: ipfsHash)
        }

        // Legacy format (v1) - process as before
        msgLog("📦 Processing legacy format message")
        return try await processLegacyIPFSData(data, ipfsHash: ipfsHash)
    }
    
    // MARK: - Process Simple Group Message (from relay broadcast)
    
    private func processSimpleGroupMessage(_ dict: [String: Any], ipfsHash: String) async throws {
        guard let groupIdString = dict["groupId"] as? String,
              let groupId = UUID(uuidString: groupIdString),
              let messageIdString = dict["messageId"] as? String,
              let messageId = UUID(uuidString: messageIdString),
              let senderPublicKey = dict["senderPublicKey"] as? String,
              let contentBase64 = dict["content"] as? String,
              let contentData = Data(base64Encoded: contentBase64),
              let timestampString = dict["timestamp"] as? String else {
            OshiLog.messaging.info("❌ Invalid group message format")
            throw NSError(domain: "GroupMessage", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid format"])
        }
        
        // Check if we're a member of this group
        let groupExists = await MainActor.run {
            MessageGroupManager.shared.groups.contains { $0.id == groupId }
        }
        
        if !groupExists {
            OshiLog.messaging.info("⚠️ Received message for unknown group: \(groupIdString.prefix(8))... — will retry after group sync")
            // Don't silently discard — the group may load later (identity restore scenario)
            return
        }
        
        // Check if this is from ourselves
        if let myKey = MessageManager.sharedIdentityManager?.publicKey,
           senderPublicKey == myKey {
            OshiLog.messaging.info("⏭️ Skipping own group message")
            return
        }
        
        // Parse timestamp
        let formatter = ISO8601DateFormatter()
        let timestamp = formatter.date(from: timestampString) ?? Date()
        
        // Parse optional media info
        let mediaTypeRaw = dict["mediaType"] as? String
        let mediaType: MediaType? = mediaTypeRaw?.isEmpty == false ? MediaType(rawValue: mediaTypeRaw!) : nil
        let mediaFileName = dict["mediaFileName"] as? String
        
        // Create group message — derive plaintextContent from encryptedContent for display
        let plaintextFromContent = String(data: contentData, encoding: .utf8)
        let groupMessage = GroupMessage(
            id: messageId,
            groupId: groupId,
            senderPublicKey: senderPublicKey,
            encryptedContent: contentData,
            timestamp: timestamp,
            isRead: false,
            messageChainIndex: 0,
            mediaType: mediaType,
            mediaFileName: mediaFileName,
            plaintextContent: plaintextFromContent
        )
        
        OshiLog.messaging.info("👥 Received group message:")
        OshiLog.messaging.info("   Group: \(groupIdString.prefix(8))...")
        OshiLog.messaging.info("   From: \(senderPublicKey.prefix(8))...")
        OshiLog.messaging.info("   Media: \(mediaType?.rawValue ?? "none")")
        
        // Forward to GroupManager
        await MainActor.run {
            // Check for duplicate
            let existingMessages = MessageGroupManager.shared.groupMessages[groupId] ?? []
            if existingMessages.contains(where: { $0.id == messageId }) {
                OshiLog.messaging.info("⏭️ Group message already exists, skipping")
                return
            }
            
            // Add message to group
            MessageGroupManager.shared.receiveGroupMessage(groupMessage)
            
            // Post notification for UI update
            NotificationCenter.default.post(
                name: NSNotification.Name("NewGroupMessage"),
                object: groupMessage
            )
        }
        
        // ✅ CLEANUP: Trigger Pinata deletion for received group message
        PinataCleanupService.shared.trackReceivedMessage(
            ipfsHash: ipfsHash,
            senderPublicKey: senderPublicKey
        )
        OshiLog.messaging.info("🗑️ Triggered Pinata cleanup for group message")
        
        OshiLog.messaging.info("✅ Group message processed and stored")
    }
    
    // MARK: - Process Encrypted Envelope (v2)
    
    private func processEncryptedEnvelope(_ dict: [String: Any], ipfsHash: String) async throws -> SecureMessage {
        guard let envelopeStr = dict["envelope"] as? String,
              let nonceStr = dict["nonce"] as? String,
              let version = dict["v"] as? Int else {
            throw NSError(domain: "Envelope", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid envelope structure"])
        }
        
        let envelope = EncryptedEnvelope(
            v: version,
            envelope: envelopeStr,
            hint: dict["hint"] as? String,
            nonce: nonceStr
        )
        
        // Try to decrypt with known group keys first
        // Get groups on main actor, then decrypt
        let groupsSnapshot = await MainActor.run { MessageGroupManager.shared.groups }
        if let payload = try? decryptEnvelopeForGroups(envelope, groups: groupsSnapshot) {
            OshiLog.messaging.info("✅ Envelope decrypted for group: \(payload.groupId?.prefix(8) ?? "unknown")...")
            return try await processDecryptedPayload(payload, ipfsHash: ipfsHash)
        }
        
        // Try to decrypt with shared secret (1-on-1)
        if let identityManager = MessageManager.sharedIdentityManager {
            // Get sender info from envelope
            let senderHint = dict["from"] as? String
            let fullSenderKey = dict["senderPublicKey"] as? String  // v3+: full sender key

            OshiLog.messaging.info("🔐 ENVELOPE DECRYPTION ATTEMPT")
            OshiLog.messaging.info("🔐 Sender hint: \(senderHint ?? "none")")
            OshiLog.messaging.info("🔐 Known contacts: \(knownContactKeys.count)")

            // 🔧 FIX: Build list of contacts to try, including potential new senders
            var contactsToTry = knownContactKeys

            // 🔧 v3 FIX: If we have the full sender key, add it to the list to try FIRST
            // This enables first-message decryption from unknown senders
            if let senderKey = fullSenderKey, !senderKey.isEmpty {
                // Add sender key at the front if not already in list
                if !contactsToTry.contains(senderKey) {
                    contactsToTry.insert(senderKey, at: 0)
                    OshiLog.messaging.info("🔑 Using full sender key from envelope: \(senderKey.prefix(8))...")
                }
            }

            // For older v2 messages, try to match by hint
            if let hint = senderHint, fullSenderKey == nil {
                // Move matching contacts to front
                contactsToTry.sort { contact1, contact2 in
                    let matches1 = contact1.hasPrefix(hint)
                    let matches2 = contact2.hasPrefix(hint)
                    if matches1 && !matches2 { return true }
                    if !matches1 && matches2 { return false }
                    return false
                }

                let hasMatchingContact = contactsToTry.contains { $0.hasPrefix(hint) }
                if !hasMatchingContact {
                    OshiLog.messaging.info("⚠️ v2 envelope from unknown sender (hint: \(hint)), will queue for later")
                }
            }

            OshiLog.messaging.info("🔐 Trying \(contactsToTry.count) contacts for decryption...")
            for (index, contact) in contactsToTry.enumerated() {
                OshiLog.messaging.info("🔐 [\(index + 1)/\(contactsToTry.count)] Trying contact: \(contact.prefix(8))...")
                if let sharedSecret = try? identityManager.computeSharedSecret(with: contact) {
                    // Try to decrypt using our IPFS envelope format
                    if let decrypted = try? decryptIPFSEnvelope(envelope, sharedSecret: sharedSecret, senderKey: contact) {
                        OshiLog.messaging.info("✅ Envelope decrypted (IPFS format) from contact: \(contact.prefix(8))...")
                        return try await processDecryptedIPFSPayload(decrypted, ipfsHash: ipfsHash)
                    }
                    // Also try the existing EnvelopeCrypto format for backwards compatibility
                    if let payload = try? EnvelopeCrypto.decrypt(envelope: envelope, sharedSecret: sharedSecret) {
                        OshiLog.messaging.info("✅ Envelope decrypted (legacy format) from contact: \(contact.prefix(8))...")
                        return try await processDecryptedPayload(payload, ipfsHash: ipfsHash)
                    }
                    OshiLog.messaging.info("   ❌ Decryption failed with this contact")
                } else {
                    OshiLog.messaging.info("   ❌ Could not compute shared secret with this contact")
                }
            }
            OshiLog.messaging.info("🔐 ═══════════════════════════════════════════════════════════════")

            // 🔧 NEW: Store undecrytable envelope for later retry when contact is added
            if let hint = senderHint {
                await storeUnknownSenderEnvelope(envelope: envelope, senderHint: hint, ipfsHash: ipfsHash)
            }
        }
        
        // Check if it might be a delivery receipt
        if let hint = dict["hint"] as? String, hint == "rcpt" {
            OshiLog.messaging.info("📬 Detected delivery receipt envelope")
            try await processDeliveryReceiptEnvelope(envelope, ipfsHash: ipfsHash)
            // Return placeholder - receipts don't create messages
            throw NSError(domain: "Receipt", code: 0, userInfo: [NSLocalizedDescriptionKey: "Delivery receipt processed"])
        }
        
        // 📱 MULTI-DEVICE: Store envelope for retry when groups are synced from another device
        await MainActor.run {
            if pendingGroupEnvelopes.count < maxPendingGroupEnvelopes {
                pendingGroupEnvelopes.append((envelope: envelope, dict: dict, ipfsHash: ipfsHash))
                OshiLog.messaging.info("📦 Stored envelope for group retry (\(pendingGroupEnvelopes.count) pending)")
            }
            // __GROUP_SELF_HEAL_2026_10_01__ We parked a group envelope we cannot decrypt —
            // typically because our group list was wiped, so decryptEnvelopeForGroups has no
            // key to match (it returns early when `groups` is empty). The device is still a
            // member server-side (that is why the envelope arrived), and its identity + 1:1
            // ratchets survived, so ask every 1:1 peer we know for EVERY group we share. One
            // answer (handleGroupSyncRequest) restores them all and the parked envelopes then
            // decrypt on the next retry tick. Throttled globally.
            let envSender = (dict["senderPublicKey"] as? String) ?? (dict["sender"] as? String) ?? (dict["from"] as? String)
            requestAllGroupsRecovery(alsoAsk: envSender)
        }

        OshiLog.messaging.info("❌ Could not decrypt envelope - unknown sender/group")
        throw NSError(domain: "Envelope", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot decrypt envelope"])
    }
    
    // 📱 MULTI-DEVICE: Retry pending group envelopes after groups are synced from another device
    // __GROUP_SELF_HEAL_2026_10_01__ Ask all known 1:1 peers for every group we belong to.
    @MainActor func requestAllGroupsRecovery(alsoAsk extra: String? = nil, alsoAskPeers: [String] = []) {
        let now = Date()
        if now.timeIntervalSince(_lastGroupRecoverySyncAt) < 30 { return }
        guard let myKey = MessageManager.sharedIdentityManager?.publicKey, !myKey.isEmpty,
              let framed = GroupWireV2.syncRequest(requester: myKey) else { return }
        // The message's own sender is, by definition, a member of the group we lost — ask
        // them directly, plus every 1:1 peer we know (they recreate as messages arrive).
        var peerSet = Set(conversations.map { $0.participantPublicKey }.filter { !$0.isEmpty })
        if let extra, !extra.isEmpty { peerSet.insert(extra) }
        for p in alsoAskPeers where !p.isEmpty { peerSet.insert(p) }
        let peers = Array(peerSet)
        guard !peers.isEmpty else { return }
        _lastGroupRecoverySyncAt = now
        OshiLog.messaging.info("🩹 [self-heal] undecryptable group envelope(s) — asking \(peers.count) peer(s) for ALL my groups")
        Task { _ = await self.v2SendGroupState(framed, to: peers) }
    }

    func retryPendingGroupEnvelopes() {
        guard !pendingGroupEnvelopes.isEmpty else { return }

        // Groups access must happen on MainActor
        DispatchQueue.main.async { [self] in
            let groups = MessageGroupManager.shared.groups
            guard !groups.isEmpty else { return }

            OshiLog.messaging.info("🔄 Retrying \(self.pendingGroupEnvelopes.count) pending group envelope(s) with \(groups.count) groups...")
            let envelopes = self.pendingGroupEnvelopes
            self.pendingGroupEnvelopes.removeAll()

        Task {
            var decryptedCount = 0
            var remaining: [(envelope: EncryptedEnvelope, dict: [String: Any], ipfsHash: String)] = []

            for item in envelopes {
                if let payload = try? decryptEnvelopeForGroups(item.envelope, groups: groups) {
                    _ = try? await processDecryptedPayload(payload, ipfsHash: item.ipfsHash)
                    // Mark as received on VPS
                    if let identityManager = MessageManager.sharedIdentityManager {
                        try? await markAsReceived(ipfsHash: item.ipfsHash, publicKey: identityManager.publicKey)
                    }
                    decryptedCount += 1
                } else {
                    remaining.append(item)
                }
            }

            await MainActor.run {
                pendingGroupEnvelopes = remaining
            }

            OshiLog.messaging.info("✅ Decrypted \(decryptedCount) group envelope(s), \(remaining.count) still pending")
        }
        } // end DispatchQueue.main.async
    }

    // Decrypt envelope for groups we're members of
    private func decryptEnvelopeForGroups(_ envelope: EncryptedEnvelope, groups: [MessageGroup]) throws -> MessagePayload? {
        for group in groups {
            let groupKey = deriveGroupKey(groupId: group.id.uuidString)
            if let payload = try? EnvelopeCrypto.decryptGroup(envelope: envelope, groupKey: groupKey) {
                return payload
            }
        }
        
        return nil
    }
    
    // Derive group envelope key (same as sender)
    private func deriveGroupKey(groupId: String) -> Data {
        let groupIdData = Data(groupId.utf8)
        let salt = Data("oshi-group-envelope-key".utf8)
        var combined = Data()
        combined.append(groupIdData)
        combined.append(salt)

        let hash = SHA256.hash(data: combined)
        return hash.withUnsafeBytes { Data($0) }
    }

    // 🔧 NEW: Store envelope from unknown sender for later decryption
    @MainActor
    private func storeUnknownSenderEnvelope(envelope: EncryptedEnvelope, senderHint: String, ipfsHash: String) {
        OshiLog.messaging.info("📦 Storing envelope from unknown sender (hint: \(senderHint)) for later decryption")

        var envelopes = unknownSenderEnvelopes[senderHint] ?? []

        // Check if we already have this ipfsHash
        if envelopes.contains(where: { $0.ipfsHash == ipfsHash }) {
            OshiLog.messaging.info("   ⏭️ Envelope already stored, skipping")
            return
        }

        // Limit storage per hint
        if envelopes.count >= maxUnknownEnvelopesPerHint {
            envelopes.removeFirst()
            OshiLog.messaging.info("   ⚠️ Queue full, removed oldest envelope")
        }

        envelopes.append((envelope: envelope, ipfsHash: ipfsHash))
        unknownSenderEnvelopes[senderHint] = envelopes
        OshiLog.messaging.info("   ✅ Stored envelope, \(envelopes.count) pending for hint \(senderHint)")
    }

    // 🔧 NEW: Retry decryption when a new contact is added
    func retryDecryptionForNewContact(_ contactKey: String) {
        guard let identityManager = MessageManager.sharedIdentityManager else { return }

        let contactHint = String(contactKey.prefix(8))
        OshiLog.messaging.info("🔄 Retrying decryption for new contact (hint: \(contactHint))...")

        guard let envelopes = unknownSenderEnvelopes[contactHint], !envelopes.isEmpty else {
            OshiLog.messaging.info("   ℹ️ No pending envelopes for this contact")
            return
        }

        Task {
            var decryptedCount = 0
            var remainingEnvelopes: [(envelope: EncryptedEnvelope, ipfsHash: String)] = []

            for (envelope, ipfsHash) in envelopes {
                if let sharedSecret = try? identityManager.computeSharedSecret(with: contactKey) {
                    // Try IPFS envelope format
                    if let decrypted = try? decryptIPFSEnvelope(envelope, sharedSecret: sharedSecret, senderKey: contactKey) {
                        OshiLog.messaging.info("   ✅ Decrypted stored envelope from \(contactKey.prefix(12))...")
                        _ = try? await processDecryptedIPFSPayload(decrypted, ipfsHash: ipfsHash)
                        try? await markAsReceived(ipfsHash: ipfsHash, publicKey: identityManager.publicKey)
                        decryptedCount += 1
                        continue
                    }
                    // Try legacy format
                    if let payload = try? EnvelopeCrypto.decrypt(envelope: envelope, sharedSecret: sharedSecret) {
                        OshiLog.messaging.info("   ✅ Decrypted stored envelope (legacy) from \(contactKey.prefix(12))...")
                        _ = try? await processDecryptedPayload(payload, ipfsHash: ipfsHash)
                        try? await markAsReceived(ipfsHash: ipfsHash, publicKey: identityManager.publicKey)
                        decryptedCount += 1
                        continue
                    }
                }
                // Still can't decrypt, keep for later
                remainingEnvelopes.append((envelope: envelope, ipfsHash: ipfsHash))
            }

            // Fix Swift 6 concurrency: capture value before MainActor.run
            let finalRemainingEnvelopes = remainingEnvelopes
            let finalDecryptedCount = decryptedCount

            await MainActor.run {
                if finalRemainingEnvelopes.isEmpty {
                    unknownSenderEnvelopes.removeValue(forKey: contactHint)
                } else {
                    unknownSenderEnvelopes[contactHint] = finalRemainingEnvelopes
                }
            }

            OshiLog.messaging.info("   📊 Decrypted \(finalDecryptedCount) envelopes, \(finalRemainingEnvelopes.count) remaining")
        }
    }
    
    // Known contact keys for decryption attempts
    private var knownContactKeys: [String] {
        // Get from conversations/contacts
        return conversations.map { $0.participantPublicKey }
    }
    
    // MARK: - Decrypt IPFS Envelope (v2 format)
    
    private func decryptIPFSEnvelope(_ envelope: EncryptedEnvelope, sharedSecret: Data, senderKey: String) throws -> [String: Any] {
        guard let envelopeData = Data(base64Encoded: envelope.envelope),
              let nonceData = Data(base64Encoded: envelope.nonce) else {
            throw PinataError.decryptionFailed
        }
        
        // Derive the same symmetric key as sender using the shared secret
        let symmetricKey = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: sharedSecret),
            salt: "oshi-ipfs-envelope-v2".data(using: .utf8)!,
            info: Data(),
            outputByteCount: 32
        )
        
        // Parse nonce
        guard nonceData.count == 12 else {
            throw PinataError.decryptionFailed
        }
        let nonce = try AES.GCM.Nonce(data: nonceData)
        
        // Split envelope into ciphertext and tag
        guard envelopeData.count > 16 else {
            throw PinataError.decryptionFailed
        }
        let tagStartIndex = envelopeData.count - 16
        let ciphertext = envelopeData[..<tagStartIndex]
        let tag = envelopeData[tagStartIndex...]
        
        // Decrypt
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)
        
        // Parse JSON
        guard let payload = try JSONSerialization.jsonObject(with: decryptedData) as? [String: Any] else {
            throw PinataError.decryptionFailed
        }
        
        return payload
    }
    
    // Process decrypted IPFS payload (our v2 format)
    private func processDecryptedIPFSPayload(_ payload: [String: Any], ipfsHash: String) async throws -> SecureMessage {
        guard let id = payload["id"] as? String,
              let senderAddress = payload["senderAddress"] as? String,
              let recipientAddress = payload["recipientAddress"] as? String,
              let timestamp = payload["timestamp"] as? TimeInterval,
              let encryptedContentBase64 = payload["encryptedContent"] as? String,
              let encryptedContentData = Data(base64Encoded: encryptedContentBase64) else {
            throw NSError(domain: "Payload", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid decrypted payload"])
        }
        
        OshiLog.messaging.info("📦 Processing decrypted IPFS payload:")
        OshiLog.messaging.info("   ID: \(id.prefix(8))...")
        OshiLog.messaging.info("   From: \(senderAddress.prefix(12))...")
        OshiLog.messaging.info("   To: \(recipientAddress.prefix(12))...")
        
        // Decode the EncryptedMessage
        let encryptedMessage = try JSONDecoder().decode(EncryptedMessage.self, from: encryptedContentData)
        
        // Create SecureMessage
        // 🔧 CRITICAL FIX: Set senderPublicKey and recipientPublicKey from the payload
        // In OSHI, senderAddress == senderPublicKey and recipientAddress == recipientPublicKey
        // Without these, checkPendingIPFSMessages rejects the message as "not for us"
        let message = SecureMessage(
            id: id,
            senderAddress: senderAddress,
            recipientAddress: recipientAddress,
            encryptedContent: encryptedMessage,
            timestamp: Date(timeIntervalSince1970: timestamp),
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: senderAddress,
            recipientPublicKey: recipientAddress,
            plaintextContent: nil,
            deliveryMethod: .ipfs
        )
        
        // ✅ Send delivery receipt back to Android sender
        if let identityManager = MessageManager.sharedIdentityManager {
            DeliveryReceiptManager.shared.sendDeliveryReceipt(
                for: id,
                ipfsHash: ipfsHash,
                senderKey: senderAddress,
                recipientKey: identityManager.publicKey,
                identityManager: identityManager
            )
        }

        // Cache it
        ipfsCache[ipfsHash] = message
        ipfsCacheTimestamps[ipfsHash] = Date()
        evictOldCacheEntries()

        return message
    }

    // Process decrypted payload into SecureMessage
    private func processDecryptedPayload(_ payload: MessagePayload, ipfsHash: String) async throws -> SecureMessage {
        OshiLog.messaging.info("📦 Processing decrypted payload:")
        OshiLog.messaging.info("   Type: \(payload.type)")
        OshiLog.messaging.info("   ID: \(payload.id.prefix(8))...")
        OshiLog.messaging.info("   Sender: \(payload.senderKey.prefix(12))...")
        
        // Check for delivery receipt
        if payload.type == "delivery_receipt" {
            OshiLog.messaging.info("📬 Processing delivery receipt from payload")
            if payload.ipfsHash != nil {
                DeliveryReceiptManager.shared.processReceipt(
                    payload.content,
                    from: payload.senderKey,
                    identityManager: MessageManager.sharedIdentityManager!
                )
            }
            throw NSError(domain: "Receipt", code: 0, userInfo: [NSLocalizedDescriptionKey: "Receipt processed"])
        }
        
        // 🤖 __BOT_INLINE_KEYBOARD_2026_09_16__ A callback answer can also reach us
        // through the encrypted envelope path. Same rule as the queue path: it is
        // a toast, never a line in the conversation.
        if payload.type == "bot_callback_answer" {
            let answerData = Data(payload.content.utf8)
            let answer = (try? JSONSerialization.jsonObject(with: answerData)) as? [String: Any]
                ?? (Data(base64Encoded: payload.content).flatMap {
                    (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any]
                } ?? [:])
            let answerText = answer["text"] as? String ?? ""
            let showAlert = (answer["showAlert"] as? Bool) ?? ((answer["showAlert"] as? NSNumber)?.boolValue ?? false)
            let answerBotName = answer["botName"] as? String ?? "Bot"
            await MainActor.run {
                BotManager.shared.presentCallbackAnswer(text: answerText,
                                                        showAlert: showAlert,
                                                        botName: answerBotName)
            }
            throw NSError(domain: "BotCallbackAnswer", code: 0,
                          userInfo: [NSLocalizedDescriptionKey: "Bot callback answer surfaced"])
        }

        // Check for bot message BEFORE base64 decode — bot content is raw JSON, not base64
        if payload.type == "bot_message", let groupId = payload.groupId {
            msgLog("🤖 BOT MESSAGE: group=\(groupId.prefix(8)), sender=\(payload.senderKey), content=\(payload.content.prefix(80))")

            // Bot content is JSON string: {"botName":"...", "text":"...", ...}
            // Try raw UTF-8 first (server sends JSON string), then base64 fallback
            let botContentData = Data(payload.content.utf8)
            // __BOTS_PHASE1_2026_11_24__ an edit/delete of the bot's own post
            // (may carry no `text`: a buttons-only edit), read before the guard.
            if var updateJSON = (try? JSONSerialization.jsonObject(with: botContentData)) as? [String: Any],
               (updateJSON["type"] as? String) == BotPostUpdate.type {
                if updateJSON["groupId"] == nil { updateJSON["groupId"] = groupId }
                if let update = BotPostUpdate.parse(updateJSON) {
                    await MainActor.run { _ = MessageGroupManager.shared.applyBotPostUpdate(update) }
                }
                throw NSError(domain: "BotMessage", code: 0, userInfo: [NSLocalizedDescriptionKey: "Bot post action handled"])
            }
            guard let botContent = try? JSONSerialization.jsonObject(with: botContentData) as? [String: Any],
                  let botText = botContent["text"] as? String else {
                OshiLog.messaging.infoPublic("❌ Invalid bot message content format (\(payload.content.count) chars)")
                throw NSError(domain: "BotMessage", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid bot content"])
            }

            let botName = botContent["botName"] as? String ?? "Bot"

            // Parse optional media fields
            let botMediaTypeStr = botContent["mediaType"] as? String
            let botMediaType: MediaType? = botMediaTypeStr.flatMap { MediaType(rawValue: $0) }
            let botMediaFileName = botContent["mediaFileName"] as? String
            var botMediaData: Data? = nil
            if let mediaB64 = botContent["mediaData"] as? String {
                botMediaData = Data(base64Encoded: mediaB64)
                if botMediaData != nil {
                    OshiLog.messaging.info("🤖 Bot message includes media: \(botMediaTypeStr ?? "unknown") (\(botMediaData!.count) bytes)")
                }
            }

            // Create a GroupMessage with the bot's sender key and plaintext content
            var groupMessage = GroupMessage(
                id: BotPostUpdate.localId(forServerId: payload.id),
                groupId: UUID(uuidString: groupId) ?? UUID(),
                senderPublicKey: payload.senderKey,  // "bot:tokenPrefix"
                encryptedContent: Data(botText.utf8),
                timestamp: payload.timestamp,
                isRead: false,
                messageChainIndex: payload.chainIndex ?? 0,
                mediaType: botMediaType,
                mediaFileName: botMediaFileName,
                plaintextContent: "[\(botName)] \(botText)"
            )
            groupMessage.mediaData = botMediaData
            let finalGroupMessage = groupMessage

            // 🤖 __BOT_INLINE_KEYBOARD_2026_09_16__ Same keyboard, second door.
            // This path carries no `botToken` field, so the record keeps only the
            // name and — when the server puts one here too — the public `botId`.
            if let keyboard = BotInlineKeyboard.parse(botContent["replyMarkup"]) {
                let botId = (botContent["botId"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                let record = BotReplyMarkupRecord(
                    keyboard: keyboard,
                    sourceMessageId: payload.id,
                    groupId: finalGroupMessage.groupId.uuidString,
                    botTokenPrefix: nil,
                    botName: botName,
                    botId: (botId?.isEmpty == false) ? botId : nil
                )
                let localId = finalGroupMessage.id.uuidString
                await MainActor.run {
                    BotManager.shared.storeReplyMarkup(record, forMessageId: localId)
                }
            }

            // Forward to group manager (same as regular group messages)
            msgLog("🤖 Storing bot message: id=\(finalGroupMessage.id), groupId=\(finalGroupMessage.groupId), text=\(finalGroupMessage.plaintextContent?.prefix(40) ?? "nil")")
            let noteBotId = (botContent["botId"] as? String) ?? ""
            await MainActor.run {
                // __BOTS_PHASE1_2026_11_24__ see the queue path above.
                if !noteBotId.isEmpty,
                   MessageGroupManager.shared.groups.contains(where: { $0.id == finalGroupMessage.groupId }) {
                    GroupBotDirectory.shared.note(botId: noteBotId, name: botName, groupId: finalGroupMessage.groupId)
                    BotPostAuthors.record(botId: noteBotId, localId: finalGroupMessage.id)
                }
                let existingMessages = MessageGroupManager.shared.groupMessages[finalGroupMessage.groupId] ?? []
                if existingMessages.contains(where: { $0.id == finalGroupMessage.id }) {
                    OshiLog.messaging.info("⏭️ Bot message already exists, skipping")
                    return
                }
                MessageGroupManager.shared.receiveGroupMessage(finalGroupMessage)
                OshiLog.messaging.info("🤖 receiveGroupMessage called, messages now: \(MessageGroupManager.shared.groupMessages[finalGroupMessage.groupId]?.count ?? -1)")
                NotificationCenter.default.post(
                    name: NSNotification.Name("NewGroupMessage"),
                    object: finalGroupMessage
                )
            }
            msgLog("🤖 Bot message stored successfully")

            throw NSError(domain: "BotMessage", code: 0, userInfo: [NSLocalizedDescriptionKey: "Bot message forwarded to group"])
        }

        // Decode the actual message content (base64 for non-bot messages)
        guard let contentData = Data(base64Encoded: payload.content) else {
            throw NSError(domain: "Payload", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid content"])
        }

        // Check for group message
        if payload.type == "group_message", let groupId = payload.groupId {
            OshiLog.messaging.info("👥 Processing group message for group: \(groupId.prefix(8))...")

            // Decode GroupMessage
            let groupMessage = try JSONDecoder().decode(GroupMessage.self, from: contentData)

            // Forward to group manager — call directly instead of via NotificationCenter
            // to avoid struct boxing issues and async dispatch delays that can lose messages
            await MainActor.run {
                let mgr = MessageGroupManager.shared
                let existingMessages = mgr.groupMessages[UUID(uuidString: groupId) ?? UUID()] ?? []
                if existingMessages.contains(where: { $0.id == groupMessage.id }) {
                    OshiLog.messaging.info("⏭️ Group message already exists (envelope path), skipping")
                } else {
                    mgr.receiveGroupMessage(groupMessage)
                    OshiLog.messaging.info("✅ Group message stored via envelope path")
                }
            }

            // ✅ Send delivery receipt back to sender
            if let identityManager = MessageManager.sharedIdentityManager {
                DeliveryReceiptManager.shared.sendDeliveryReceipt(
                    for: payload.id,
                    ipfsHash: ipfsHash,
                    senderKey: payload.senderKey,
                    recipientKey: identityManager.publicKey,
                    identityManager: identityManager
                )
            }

            // Return placeholder for group messages
            throw NSError(domain: "Group", code: 0, userInfo: [NSLocalizedDescriptionKey: "Group message forwarded"])
        }
        
        // Regular 1-on-1 message
        let recipientKey = MessageManager.sharedIdentityManager?.publicKey ?? ""
        
        let encryptedMessage = try JSONDecoder().decode(EncryptedMessage.self, from: contentData)
        
        let message = SecureMessage(
            id: payload.id,
            senderAddress: payload.senderKey,
            recipientAddress: recipientKey,
            encryptedContent: encryptedMessage,
            timestamp: payload.timestamp,
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: payload.senderKey,
            recipientPublicKey: recipientKey,
            plaintextContent: nil,
            mediaAttachment: nil,
            deliveryMethod: .ipfs,
            mediaType: payload.hasMedia ? MediaManager.MediaType(rawValue: payload.mediaType ?? "image") : nil,
            originalMediaData: nil
        )
        
        // ✅ Send delivery receipt
        if let identityManager = MessageManager.sharedIdentityManager {
            DeliveryReceiptManager.shared.sendDeliveryReceipt(
                for: payload.id,
                ipfsHash: ipfsHash,
                senderKey: payload.senderKey,
                recipientKey: identityManager.publicKey,
                identityManager: identityManager
            )
        }
        
        ipfsCache[ipfsHash] = message
        ipfsCacheTimestamps[ipfsHash] = Date()
        evictOldCacheEntries()

        return message
    }
    
    // Process delivery receipt envelope
    private func processDeliveryReceiptEnvelope(_ envelope: EncryptedEnvelope, ipfsHash: String) async throws {
        guard let identityManager = MessageManager.sharedIdentityManager else { return }
        
        // Try to decrypt with known contacts
        for contact in knownContactKeys {
            if let sharedSecret = try? identityManager.computeSharedSecret(with: contact),
               let payload = try? EnvelopeCrypto.decrypt(envelope: envelope, sharedSecret: sharedSecret) {
                
                if payload.type == "delivery_receipt", let originalHash = payload.ipfsHash {
                    OshiLog.messaging.info("📬 Received delivery receipt for: \(originalHash.prefix(15))...")
                    
                    // Note: Unpin is handled by IPFSEphemeralManager automatically
                    OshiLog.messaging.info("📋 Delivery confirmed - IPFSEphemeralManager will handle cleanup")
                    
                    // Notify UI
                    NotificationCenter.default.post(
                        name: NSNotification.Name("MessageDeliveryConfirmed"),
                        object: nil,
                        userInfo: [
                            "messageId": payload.id,
                            "ipfsHash": originalHash
                        ]
                    )
                }
                break
            }
        }
    }
    
    // MARK: - Legacy IPFS Processing (v1)
    
    private func processLegacyIPFSData(_ data: Data, ipfsHash: String) async throws -> SecureMessage {
        if let messageDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            OshiLog.messaging.info("✅ Decoded JSON structure (legacy)")
            OshiLog.messaging.info("   Keys: \(messageDict.keys.joined(separator: ", "))")
            
            // 🔧 FIX: Support both field name variants (senderAddress OR senderPublicKey)
            // Legacy messages use senderAddress/recipientAddress
            // Newer messages use senderPublicKey/recipientPublicKey
            guard let id = messageDict["id"] as? String,
                  let encryptedContentBase64 = messageDict["encryptedContent"] as? String else {
                OshiLog.messaging.info("❌ Missing required fields (id or encryptedContent)")
                throw NSError(domain: "IPFS", code: -1)
            }

            // Try both field name variants for sender
            let senderAddress = (messageDict["senderAddress"] as? String) ??
                               (messageDict["senderPublicKey"] as? String) ?? ""
            // Try both field name variants for recipient
            let recipientAddress = (messageDict["recipientAddress"] as? String) ??
                                  (messageDict["recipientPublicKey"] as? String) ?? ""

            guard !senderAddress.isEmpty, !recipientAddress.isEmpty else {
                OshiLog.messaging.info("❌ Missing sender/recipient fields")
                OshiLog.messaging.info("   Available keys: \(messageDict.keys.joined(separator: ", "))")
                throw NSError(domain: "IPFS", code: -1)
            }
            
            // 🔧 2026-06-09: CHECK FOR RATCHET RESET SIGNAL
            // Sent by the peer when they reset their Double Ratchet locally
            // (after consistent decryption failures). We must also reset on
            // our side so the next outgoing message uses a fresh chain key
            // that the peer can decrypt. The signal is encrypted with a
            // shared-secret-derived AES key (HKDF salt "OSHI-RatchetReset-v1"),
            // NOT the broken ratchet — so we can always decrypt it.
            let isRatchetResetSignal = messageDict["isRatchetReset"] as? Bool ?? false
            if isRatchetResetSignal {
                OshiLog.messaging.info("🔄 IPFS: Detected ratchet reset signal from \(senderAddress.prefix(10))!")
                guard let encryptedData = Data(base64Encoded: encryptedContentBase64) else {
                    throw NSError(domain: "RatchetReset", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid ciphertext"])
                }
                guard let im = MessageManager.sharedIdentityManager else {
                    OshiLog.messaging.info("❌ Ratchet reset: no shared identity manager — skipping")
                    throw NSError(domain: "RatchetReset", code: 0)
                }
                do {
                    let rawSharedSecret = try im.computeSharedSecret(with: senderAddress)
                    let symmetricKey = HKDF<SHA256>.deriveKey(
                        inputKeyMaterial: SymmetricKey(data: rawSharedSecret),
                        salt: "OSHI-RatchetReset-v1".data(using: .utf8)!,
                        info: "ratchet-reset".data(using: .utf8)!,
                        outputByteCount: 32
                    )
                    let sealedBox = try AES.GCM.SealedBox(combined: encryptedData)
                    let plaintextData = try AES.GCM.open(sealedBox, using: symmetricKey)
                    guard let plaintext = String(data: plaintextData, encoding: .utf8),
                          plaintext.hasPrefix("__SESSION_RESET__") else {
                        OshiLog.messaging.info("⚠️ Ratchet reset signal had unexpected payload, ignoring")
                        throw NSError(domain: "RatchetReset", code: 0)
                    }
                    OshiLog.messaging.info("🔄 Reset signal verified — wiping local ratchet state with \(senderAddress.prefix(10))...")
                    await MainActor.run {
                        // Wipe ratchet state ONLY (don't echo a reset notification
                        // back to the sender — they already reset on their side).
                        DoubleRatchetSessionManager.shared.resetSession(
                            with: senderAddress,
                            ourPublicKey: im.publicKey
                        )
                        self.pendingDecryption.removeValue(forKey: senderAddress)
                        self.failedDecryptionCount.removeValue(forKey: senderAddress)
                        self.sessionResyncAttempts.removeValue(forKey: senderAddress)
                        self.savePendingDecryption()
                    }
                    // __V2_SESSION_RESETS_TOO_2026_08_16__ The v2 ratchet is a
                    // SEPARATE session store, and it was not being wiped here — so a
                    // reset healed the legacy chain and left the v2 one exactly as
                    // broken as before, which is the chain v2-capable peers actually
                    // use. MEASURED on the pair: 176 v2 envelopes undecryptable, each
                    // burning its 8 retries and then being acked away.
                    try? V2SessionStore().deleteSession(peerUserKey: senderAddress)
                    OshiLog.messaging.info("✅ Local ratchet reset (legacy + v2)")
                    // __RATCHET_REHANDSHAKE_2026_10_03__ Don't just wait for a human to send:
                    // we were the sending side whose messages the peer couldn't read, so
                    // proactively start a fresh session now. The peer adopts it and our next
                    // real message decrypts. Single initiator (the signal receiver) → no race.
                    sendRatchetRehandshake(to: senderAddress, identityManager: im)
                } catch {
                    OshiLog.messaging.info("❌ Ratchet reset signal decrypt failed: \(error.localizedDescription) — sender pubkey may be wrong or attacker forging")
                }
                // Always throw — caller's outer try/catch on domain="RatchetReset"
                // marks the hash as received and purges it. Reset signals are
                // NOT displayed in the chat.
                throw NSError(domain: "RatchetReset", code: 0, userInfo: [NSLocalizedDescriptionKey: "Ratchet reset processed"])
            }

            // *** CHECK FOR CALL SIGNAL ***
            let isCallSignal = messageDict["isCallSignal"] as? Bool ?? false
            if isCallSignal {
                OshiLog.messaging.info("📞 IPFS: Detected call signal!")
                // Call signals are ECDH-encrypted (AES-GCM), not Double Ratchet
                guard let encryptedData = Data(base64Encoded: encryptedContentBase64) else {
                    throw NSError(domain: "IPFS", code: -1)
                }
                
                // Route to VoiceCallManager for handling
                await MainActor.run {
                    OshiLog.messaging.info("📞 IPFS: Routing call signal to VoiceCallManager")
                    NotificationCenter.default.post(
                        name: NSNotification.Name("ReceivedCallSignalFromIPFS"),
                        object: nil,
                        userInfo: [
                            "encryptedData": encryptedData,
                            "senderPublicKey": senderAddress
                        ]
                    )
                }
                
                // Return a placeholder message (call signals aren't displayed in chat)
                return SecureMessage(
                    id: id,
                    senderAddress: senderAddress,
                    recipientAddress: recipientAddress,
                    encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: senderAddress, timestamp: Date()),
                    timestamp: Date(),
                    isRead: true,
                    deliveryStatus: .delivered,
                    senderPublicKey: senderAddress,
                    recipientPublicKey: recipientAddress,
                    plaintextContent: "📞CALL_SIGNAL📞",
                    mediaAttachment: nil,
                    deliveryMethod: .ipfs,
                    mediaType: nil,
                    originalMediaData: nil
                )
            }
            
            // Handle timestamp as either TimeInterval or ISO8601 string
            var messageTimestamp: Date
            if let timestampInterval = messageDict["timestamp"] as? TimeInterval {
                messageTimestamp = Date(timeIntervalSince1970: timestampInterval)
            } else if let timestampString = messageDict["timestamp"] as? String {
                if let date = ISO8601DateFormatter().date(from: timestampString) {
                    messageTimestamp = date
                } else {
                    messageTimestamp = Date()
                }
            } else {
                messageTimestamp = Date()
            }
            
            guard let encryptedContentData = Data(base64Encoded: encryptedContentBase64) else {
                OshiLog.messaging.info("❌ Failed to decode base64")
                throw NSError(domain: "IPFS", code: -1)
            }

            // Try to decode as iOS-format EncryptedMessage JSON first
            // This handles both iOS messages and new Android format (DoubleRatchet + EncryptedMessage wrapping)
            let encryptedMessage: EncryptedMessage
            if let decoded = try? JSONDecoder().decode(EncryptedMessage.self, from: encryptedContentData) {
                encryptedMessage = decoded
                OshiLog.messaging.info("✅ Successfully decoded EncryptedMessage JSON")
            } else if let identityManager = MessageManager.sharedIdentityManager {
                // Fallback: Legacy Android format (raw AES-GCM bytes, no EncryptedMessage wrapping)
                // Or direct DoubleRatchetMessage JSON
                OshiLog.messaging.info("📦 Not EncryptedMessage JSON, trying legacy Android format...")

                // Check if it's a direct DoubleRatchetMessage JSON
                if let ratchetDict = try? JSONSerialization.jsonObject(with: encryptedContentData) as? [String: Any],
                   ratchetDict["header"] != nil, ratchetDict["ciphertext"] != nil {
                    OshiLog.messaging.info("📦 Detected direct DoubleRatchetMessage (Android VPS)")
                    // Create a placeholder EncryptedMessage; decryption will handle the ratchet path
                    // Store the ratchet data as base64 in ciphertext for later processing
                    let ratchetBase64 = encryptedContentData.base64EncodedString()
                    encryptedMessage = EncryptedMessage(
                        ciphertext: ratchetBase64,
                        signature: "",
                        senderPublicKey: senderAddress,
                        timestamp: Date()
                    )
                } else {
                    // Raw AES-GCM bytes from legacy Android: decrypt directly
                    OshiLog.messaging.info("📦 Trying raw AES-GCM decryption (legacy Android)...")
                    let sharedSecret = try identityManager.computeSharedSecret(with: senderAddress)
                    let symmetricKey = HKDF<SHA256>.deriveKey(
                        inputKeyMaterial: SymmetricKey(data: sharedSecret),
                        salt: Data(),
                        info: Data(),
                        outputByteCount: 32
                    )
                    let sealedBox = try AES.GCM.SealedBox(combined: encryptedContentData)
                    let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)
                    let plaintext = String(data: decryptedData, encoding: .utf8) ?? ""
                    OshiLog.messaging.info("✅ Legacy Android message decrypted (\(plaintext.count) chars)")

                    let msg = SecureMessage(
                        id: id,
                        senderAddress: senderAddress,
                        recipientAddress: recipientAddress,
                        encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: senderAddress, timestamp: Date()),
                        timestamp: messageTimestamp,
                        isRead: false,
                        deliveryStatus: .delivered,
                        senderPublicKey: senderAddress,
                        recipientPublicKey: recipientAddress,
                        plaintextContent: plaintext,
                        mediaAttachment: nil,
                        deliveryMethod: nil,
                        mediaType: nil,
                        originalMediaData: nil
                    )

                    ipfsCache[ipfsHash] = msg
                    ipfsCacheTimestamps[ipfsHash] = Date()
                    evictOldCacheEntries()
                    return msg
                }
            } else {
                throw NSError(domain: "IPFS", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot decode encrypted content"])
            }

            OshiLog.messaging.info("✅ Processing encrypted message")
            
            let message = SecureMessage(
                id: id,
                senderAddress: senderAddress,
                recipientAddress: recipientAddress,
                encryptedContent: encryptedMessage,
                timestamp: messageTimestamp,
                isRead: false,
                deliveryStatus: .delivered,
                senderPublicKey: senderAddress,
                recipientPublicKey: recipientAddress,
                plaintextContent: nil,
                mediaAttachment: nil,
                deliveryMethod: nil,
                mediaType: nil,
                originalMediaData: nil
            )
            
            if let identityManager = MessageManager.sharedIdentityManager {
                if let decrypted = decryptMessage(message, using: identityManager) {
                    var decryptedMessage = message
                    
                    OshiLog.messaging.info("📋 IPFS Decrypted content (\(decrypted.count) chars)")
                    
                    // Check if decrypted content is a media message
                    if decrypted.starts(with: "{") && Self.isMediaMessage(decrypted) {
                        OshiLog.messaging.info("🎯 Decrypted content is media JSON!")
                        if Self.parseAndStoreMedia(decrypted, for: &decryptedMessage) {
                            OshiLog.messaging.info("✅ Media extracted from IPFS message")
                        } else {
                            decryptedMessage.plaintextContent = decrypted
                            OshiLog.messaging.info("⚠️ Media parsing failed, stored as text")
                        }
                    } else if decrypted.starts(with: "MEDIA|||") {
                        // Handle MEDIA marker format
                        let components = decrypted.components(separatedBy: "|||")
                        if components.count >= 3,
                           let mediaData = Data(base64Encoded: components[2]) {
                            decryptedMessage.mediaAttachment = mediaData
                            decryptedMessage.originalMediaData = mediaData
                            decryptedMessage.mediaType = MediaManager.MediaType(rawValue: components[1])
                            decryptedMessage.plaintextContent = ""
                            OshiLog.messaging.info("✅ Media extracted from marker: \(components[1]), \(mediaData.count) bytes")
                        } else {
                            decryptedMessage.plaintextContent = decrypted
                        }
                    } else if let mediaType = detectRawBase64MediaType(decrypted) {
                        // Handle raw base64 media (old format or from older app version)
                        OshiLog.messaging.info("🎯 Decrypted content is raw base64 media: \(mediaType.rawValue)")
                        if parseRawBase64Media(decrypted, type: mediaType, for: &decryptedMessage) {
                            OshiLog.messaging.info("✅ Raw media extracted from IPFS message")
                        } else {
                            decryptedMessage.plaintextContent = decrypted
                            OshiLog.messaging.info("⚠️ Raw media parsing failed, stored as text")
                        }
                    } else {
                        decryptedMessage.plaintextContent = decrypted
                        OshiLog.messaging.info("✅ Message decrypted (\(decrypted.count) chars)")
                    }
                    
                    ipfsCache[ipfsHash] = decryptedMessage
                    ipfsCacheTimestamps[ipfsHash] = Date()
                    evictOldCacheEntries()
                    OshiLog.messaging.info("💾 Cached decrypted message")
                    
                    return decryptedMessage
                } else {
                    OshiLog.messaging.info("❌ Could not decrypt message, will show encrypted")
                }
            }
            
            ipfsCache[ipfsHash] = message
            ipfsCacheTimestamps[ipfsHash] = Date()
            evictOldCacheEntries()
            OshiLog.messaging.info("💾 Cached encrypted message")
            
            return message
        }
        
        OshiLog.messaging.info("⚠️ Not a JSON dict, trying direct decode...")
        let message = try JSONDecoder().decode(SecureMessage.self, from: data)
        
        ipfsCache[ipfsHash] = message
        ipfsCacheTimestamps[ipfsHash] = Date()
        evictOldCacheEntries()
        OshiLog.messaging.info("💾 Cached message (direct decode)")
        
        return message
    }
    
    private func fetchIPFSData(_ ipfsHash: String) async throws -> Data {
        var lastError: Error?

        // 🔐 STEP 1: Try VPS proxy first (hides user IP)
        if useVPSProxyForIPFS, let proxyURL = URL(string: "\(vpsIPFSProxyURL)\(ipfsHash)") {
            do {
                var request = URLRequest(url: proxyURL)
                request.timeoutInterval = 20

                let (data, response) = try await URLSession.shared.data(for: request)

                if let httpResponse = response as? HTTPURLResponse,
                   httpResponse.statusCode == 200, !data.isEmpty {
                    OshiLog.messaging.info("✅ IPFS data fetched via VPS proxy (IP hidden)")
                    return data
                }
            } catch {
                OshiLog.messaging.info("⚠️ VPS IPFS proxy failed: \(error.localizedDescription)")
                lastError = error
            }
        }

        // No public-gateway fallback (removed 2026-07-17) — see fetchFromIPFS.
        throw lastError ?? NSError(domain: "IPFS", code: -1)
    }
    
    private func markAsReceived(ipfsHash: String, publicKey: String) async throws {
        // 🔧 CRITICAL FIX: Use base64url encoding
        let encodedKey = base64urlEncode(publicKey)
        // Include deviceId for multi-device support
        let deviceId = VoIPPushManager.getDeviceId()
        // Use nginx reverse proxy on port 80 (routes to internal port 8081)
        let fullURL = "\(currentVPSURL)/api/received/\(encodedKey)/\(ipfsHash)?deviceId=\(deviceId)"

        guard let url = URL(string: fullURL) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"

        let (_, _) = try await URLSession.shared.data(for: request)
        OshiLog.messaging.info("✅ Marked message as received on VPS (device: \(deviceId.prefix(8))...)")
    }
    
    private func storeIPFSHash(messageId: String, ipfsHash: String) {
        UserDefaults.standard.set(ipfsHash, forKey: "ipfs_\(messageId)")
    }
    
    // MARK: - Helper: IPFS Fallback
    

    // MARK: - Covert Channel Transport
    
    /// Send message via covert channels (disguised as normal web traffic)
    /// Used when network is restricted and IPFS/VPS are blocked
    private func sendViaCovertChannel(message: SecureMessage, messageId: String, recipientPublicKey: String) async throws {
        guard await self.v2TrySend(message: message, messageId: messageId,
                                  recipientPublicKey: recipientPublicKey,
                                  reveal: self.revealContext(forMessageId: messageId)) else {
            throw CovertError.channelFailed(.webMimicry, "Obfuscated relay unavailable; message kept for retry")
        }
    }
    
    /// Best-effort guess at whether the recipient's phone is currently online.
    ///
    /// Deliberately CONSERVATIVE — it returns true whenever it does not know.
    /// A false "offline" pushes a message onto a 1-2 s-per-frame radio that may
    /// have nobody in range, so the cost of guessing wrong in that direction is
    /// a message that goes nowhere slowly. Guessing wrong the other way just
    /// means the relay handles it, which is the behaviour that already existed.
    ///
    /// The signal is presence: `ContactPresenceManager` carries a last-seen
    /// timestamp maintained from delivery receipts and presence pings. Anything
    /// inside the window counts as online. No last-seen at all — a contact we
    /// have never exchanged with — also counts as online, because "unknown" is
    /// not evidence of absence.
    private func isPeerLikelyOnline(_ publicKey: String) async -> Bool {
        await MainActor.run {
            guard let seen = ContactPresenceManager.shared.lastSeen(publicKey: publicKey) else {
                return true          // unknown ≠ offline
            }
            return Date().timeIntervalSince(seen) < Self.peerOnlineWindow
        }
    }

    /// How stale a last-seen may be before the peer is treated as unreachable.
    ///
    /// Five minutes rather than seconds: presence updates arrive on receipts and
    /// pings, which are themselves sparse, so a short window would call an
    /// actively-chatting peer offline between messages.
    private static let peerOnlineWindow: TimeInterval = 300

    /// Hand the envelope to the paired LoRa radio and do the delivery
    /// bookkeeping. Returns false if there is no usable radio, the message is
    /// too big, or it carries media — LoRa is text-sized only.
    ///
    /// Extracted so the off-grid shortcut and the last-resort catch below
    /// record delivery identically; they used to be one inline copy.
    private func deliverViaLoRa(message: SecureMessage, messageId: String) async -> Bool {
        // The media guard now lives inside `queueOrSendSecureMessage`, so that
        // refusal is recorded and shown like every other one instead of being a
        // silent `false` here.
        // Every LoRa radio this phone holds: a Meshtastic/OSHI Mesh radio and/or a MeshCore radio. The same
        // OMP frames go out on each, so the message reaches whichever network the recipient's radio is on.
        let sent = await MainActor.run { () -> Bool in
            let viaMeshCore = MeshCoreManager.shared.isReady && MeshCoreManager.shared.sendSecureMessage(message)
            let viaMeshtastic = (MeshtasticManager.isRadioPaired || !viaMeshCore)
                && MeshtasticManager.shared.queueOrSendSecureMessage(message)
            return viaMeshtastic || viaMeshCore
        }
        guard sent else { return false }
        await MainActor.run {
            self.uploadStatus[messageId] = .completed
            self.uploadProgress[messageId] = 1.0
            guard let manager = MessageManager.shared,
                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
            manager.messages[index].deliveryStatus = .sent
            manager.messages[index].deliveryMethod = .lora   // radio, not Bluetooth mesh
            manager.saveMessages()
            manager.removeFromRetryQueue(messageId: messageId)
        }
        return true
    }

    // MARK: LoRa held-queue callbacks (__LORA_HELD_DURABLE_2026_11_18__)

    /// The radio's held queue asks before spending airtime: false when the message is gone or was
    /// delivered some other way. Main queue (MeshtasticManager's).
    func loRaHeldStillWanted(messageId: String) -> Bool {
        guard let m = messages.first(where: { $0.id == messageId }) else { return false }
        return m.deliveryStatus == .pending || m.deliveryStatus == .failed
    }

    /// A held message's frames reached the radio: the same bookkeeping as `deliverViaLoRa`.
    func loRaHeldSent(messageId: String) {
        uploadStatus[messageId] = .completed
        uploadProgress[messageId] = 1.0
        guard let index = messages.firstIndex(where: { $0.id == messageId }) else { return }
        messages[index].deliveryStatus = .sent
        messages[index].deliveryMethod = .lora
        saveMessages()
        removeFromRetryQueue(messageId: messageId)
    }

    /// It will never go out over this radio (too large for it, evicted): show it failed, never lose it
    /// in silence. The retry queue keeps it for the other transports.
    func loRaHeldUndeliverable(messageId: String) {
        guard let index = messages.firstIndex(where: { $0.id == messageId }),
              messages[index].deliveryStatus == .pending || messages[index].deliveryStatus == .failed else { return }
        messages[index].deliveryStatus = .failed
        saveMessages()
    }

    /// - Parameter excludeRadio: take the radio out of this attempt entirely.
    ///   Set ONLY by `escalateAfterLoRa`, where the radio has already had its
    ///   turn and did not reach the peer. Without it, an escalation re-enters
    ///   the same two LoRa branches it was escalating out of and ping-pongs:
    ///   radio → no ACK → escalate → radio → no ACK, forever, on a transport
    ///   that costs seconds of airtime per frame.
    ///   __LORA_THEN_INTERNET_2026_10_27__
    private func fallbackToIPFS(message: SecureMessage, messageId: String, recipientPublicKey: String,
                                excludeRadio: Bool = false) async throws {
        OshiLog.messaging.info("🔄 fallbackToIPFS called for message: \(messageId)\(excludeRadio ? " (radio excluded — already tried)" : "")")

        // 📡 Off-grid shortcut. LoRa used to be reachable only from the catch at
        // the bottom, which meant a phone with no network at all still had to
        // walk the v2 relay timeout and the whole IPFS attempt — tens of seconds
        // of waiting on transports that cannot possibly succeed — before the
        // radio was even offered the message. Exactly the situation someone
        // bought a LoRa node for.
        //
        // This does NOT reorder anything: when a network path exists we still
        // prefer it, because LoRa is ~1-2 s per frame. It only skips attempts
        // already known to be impossible.
        //
        // Gated on `hasPhysicalPath`, not `isConnected`: a VPN keeps the path
        // `.satisfied` from a tunnel route alone once the link under it is
        // gone, so `isConnected` stays true off-grid and this shortcut — the
        // only route to the radio — could never fire for a VPN user.
        if !excludeRadio, !NetworkReachabilityManager.shared.hasPhysicalPath {
            if await deliverViaLoRa(message: message, messageId: messageId) {
                OshiLog.messaging.info("📡 No physical network path — sent via LoRa without waiting on internet timeouts")
                return
            }
        }

        // 📡 LoRa as a PEER-REACHABILITY transport, not just an off-grid one.
        //
        // 🔧 (2026-08-06) The shortcut above asks "am *I* off-grid?". Nothing
        // asked "is the RECIPIENT reachable?" — so with the sender online and
        // the peer dark, two paired radios sat unused while the ciphertext
        // parked on the relay waiting for a phone that was not coming.
        //
        // LoRa now sits where mesh sits: tried when the peer looks unreachable
        // by network, BEFORE committing to store-and-forward. Gated on a radio
        // actually being paired and ready — with no radio this branch does not
        // exist and the chain is exactly what it was.
        //
        // Deliberately AFTER the relay decides it cannot reach the peer live,
        // not before: LoRa is ~1-2 s per frame and text-only, so it must not
        // displace a working network path. Order is unchanged for every user
        // without a radio.
        // For MEDIA specifically, take this peer-reachability LoRa branch only
        // when there is no physical path at all. With internet available the
        // relay/IPFS carries media far better; LoRa media is a true last resort
        // (opt-in, minutes on the air) and the off-grid branch at the top already
        // covers the no-path case. Text is unchanged — it may still ride LoRa
        // here when the peer looks offline. [Media-over-LoRa Phase 1 2026-09-11]
        // __ROUTE_BY_POLICY_2026_10_20__ The three conditions below used to be
        // spelled out inline here, and the "which comes first" judgement lived
        // in this `if`. Both now come from `TransportPolicy`, so the order the
        // router takes is the order the policy publishes — see
        // `__PREFERENCE_RANK_WAS_A_LIE_2026_10_20__`.
        //
        // `peerOnMesh: false` is not a guess: this function IS the post-mesh
        // chain. By the time we are here the mesh either was not available for
        // this recipient or has already failed, 1,200 lines up at
        // `meshAvailableNow`.
        let branchCarriesMedia = message.mediaAttachment != nil || message.originalMediaData != nil
        let routing = TransportPolicy.Conditions(
            physicalPath: NetworkReachabilityManager.shared.hasPhysicalPath,
            peerOnMesh: false,
            // __MESHCORE_INTEROP_2026_11_25__ A MeshCore radio is a radio: a phone holding only one used to
            // be routed as radio-less, so LoRa was never tried ahead of (or instead of) the relay.
            radioReady: await MainActor.run(resultType: Bool.self, body: {
                MeshCoreInterop.radioReady(meshtasticReady: MeshtasticManager.shared.isReady,
                                           meshCoreReady: MeshCoreManager.shared.isReady)
            }),
            peerLikelyOnline: await isPeerLikelyOnline(recipientPublicKey),
            carriesMedia: branchCarriesMedia,
            // __ANTI_JAMMING_PHASE1_2026_11_13__ A jammed channel (and no "send
            // anyway" from the user) takes LoRa out of the order. The off-grid
            // shortcut above is covered too: `queueOrSendSecureMessage` holds the
            // message and returns false while the channel is excluded.
            radioJammed: await MainActor.run(resultType: Bool.self, body: {
                MeshCoreInterop.radioJammed(meshtasticJammed: MeshtasticManager.shared.excludesLoRaForJamming,
                                            meshCoreReady: MeshCoreManager.shared.isReady)
            }))

        if !excludeRadio, TransportPolicy.radioComesBeforeRelay(routing) {
            if await deliverViaLoRa(message: message, messageId: messageId) {
                OshiLog.messaging.info("📡 Radio ahead of the relay by policy — delivered over LoRa")
                return
            }
            OshiLog.messaging.info("📡 LoRa refused or unavailable — falling through")
        }

        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) The radio and the mesh have
        // had their turn. If the user forbade internet sending, STOP here: the
        // message stays `.pending` in the retry queue and goes out when a radio
        // or a peer is in range. `v2TrySend` and `uploadToIPFS` refuse on their
        // own too — this branch exists so the refusal is recorded against the
        // message instead of looking like a failed upload.
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Per-conversation as well as
        // app-wide: the person who wants ONE conversation kept off the internet
        // does not want the other forty to stop working.
        if !(await TransportPolicy.internetAllowedForSending(inConversation: recipientPublicKey)) {
            OshiLog.messaging.info("🚫 Internet sending is off for this conversation — keeping \(messageId.prefix(8))… for an offline route")
            // __NEVER_DROP_HELD_2026_11_18__ Android parity: when the policy left the radio out
            // (jammed, not connected) hand the envelope to the radio's durable held queue too, so it
            // goes out the moment the radio is back or the channel clears — not only on the next
            // network-reachability retry, which never comes for someone who forbade the internet.
            if !excludeRadio, !branchCarriesMedia {
                _ = await MainActor.run { MeshtasticManager.shared.holdForRadio(message) }
            }
            await MainActor.run {
                // No status at all rather than `.failed`: there is no error to
                // show. `UploadStatus` has no idle case, so the key goes away.
                self.uploadStatus.removeValue(forKey: messageId)
                self.uploadProgress.removeValue(forKey: messageId)
                guard let manager = MessageManager.shared,
                      let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                manager.messages[index].deliveryStatus = .pending
                manager.saveMessages()
            }
            throw TransportPolicyError.internetSendingDisabled
        }

        // [V2] Server-first transport: try the OSHI v2 relay/blob server before
        // the legacy Pinata/IPFS path. Covers every caller (direct, mesh-timeout,
        // covert fallback, retries). Falls through untouched when the peer has
        // no v2 bundle (previous app versions) or on any v2 error.
        if await self.v2TrySend(message: message, messageId: messageId,
                                recipientPublicKey: recipientPublicKey,
                                reveal: self.revealContext(forMessageId: messageId)) {
            return
        }

        if CoverTransport.mode != nil {
            throw CovertError.channelFailed(.webMimicry, "Obfuscated relay unavailable; direct transport was not used")
        }

        // Update upload status
        await MainActor.run {
            self.uploadStatus[messageId] = .preparing
            self.uploadProgress[messageId] = 0.0
        }
        
        do {
            OshiLog.messaging.info("   📤 Uploading to IPFS...")
            
            // Update to uploading status
            await MainActor.run {
                self.uploadStatus[messageId] = .uploading(progress: 0.1)
                self.uploadProgress[messageId] = 0.1
            }
            
            let ipfsHash = try await self.uploadToIPFS(
                message: message,
                messageId: messageId,
                recipientPublicKey: recipientPublicKey
            )
            OshiLog.messaging.info("   ✅ IPFS upload complete: \(ipfsHash.prefix(20))...")
            
            await MainActor.run {
                // Mark as completed
                self.uploadStatus[messageId] = .completed
                self.uploadProgress[messageId] = 1.0
                
                guard let manager = MessageManager.shared,
                      let index = manager.messages.firstIndex(where: { $0.id == messageId }) else {
                    OshiLog.messaging.info("   ⚠️ Message not found in messages array")
                    return
                }
                
                manager.messages[index].deliveryStatus = .sent
                manager.messages[index].deliveryMethod = .ipfs
                manager.saveMessages()
                // __LORA_TOO_LARGE_WAS_NOT_A_FAILURE_2026_11_05__ The radio was one
                // lane and this message took another. A banner still saying the
                // radio would not carry it is true about the radio and false about
                // the message, and the user reads only the second half.
                MeshtasticManager.shared.clearRefusalIfMessageDeliveredElsewhere()
                manager.storeIPFSHash(messageId: messageId, ipfsHash: ipfsHash)
                
                // ✅ CLEANUP: Track sent message for eventual cleanup after delivery
                PinataCleanupService.shared.trackSentMessage(
                    ipfsHash: ipfsHash,
                    messageId: messageId,
                    recipientPublicKey: recipientPublicKey,
                    isGroupMessage: false
                )
                OshiLog.messaging.info("   📝 Tracking sent message for Pinata cleanup")
                
                // 🔧 FIX (false "Mesh" badge): only queue for mesh-retry if
                // the recipient is plausibly mesh-reachable now (i.e. their
                // pubkey has been seen on Multipeer or CrossPlatformMesh).
                // Previously we queued every IPFS-sent message
                // unconditionally — combined with a global isMeshAvailable
                // check in the retry loop, a stranger walking into BLE
                // range was enough to trigger a false "mesh delivered"
                // badge flip for an already-delivered IPFS message.
                let cpReachable = CrossPlatformMesh.shared.isPeerReachable(recipientPublicKey)
                let mpReachable = MessageManager.sharedMeshManager?.isPeerConnected(publicKey: recipientPublicKey) ?? false
                if cpReachable || mpReachable {
                    manager.pendingMeshMessages.append((message, recipientPublicKey))
                    OshiLog.messaging.info("   📋 Added to pendingMeshMessages (\(manager.pendingMeshMessages.count) total)")
                } else {
                    OshiLog.messaging.info("   ⏭️ Not queuing for mesh retry — recipient \(recipientPublicKey.prefix(12))… is not a current mesh peer")
                }
                
                OshiLog.messaging.info("✅ Sent via IPFS (Cloud)")
                
                // Clean up progress after a delay
                let messageIdCopy = messageId
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                    self?.uploadStatus.removeValue(forKey: messageIdCopy)
                    self?.uploadProgress.removeValue(forKey: messageIdCopy)
                }
                
                // ✅ NEW: Trigger immediate check on receiver side (if polling)
                OshiLog.messaging.info("🔔 Triggering check for new messages in 2 seconds...")
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    // This helps if the sender is also checking their own queue
                    manager.checkPendingIPFSMessages()
                }
            }
        } catch {
            OshiLog.messaging.info("❌ IPFS upload failed: \(error.localizedDescription)")
            OshiLog.messaging.info("   Error details: \(error)")

            // 📡 LAST-RESORT LANE (Meshtastic M2): every internet transport failed.
            // If a long-range LoRa radio is paired and ready, hand the envelope to
            // it (text-size only — the manager rejects >2 KB). The recipient's
            // OSHI decrypts it like any mesh message; non-recipients drop it.
            // __LORA_THEN_INTERNET_2026_10_27__ ...unless the radio is what we
            // are escalating OUT of. Offering it again here is what would close
            // the loop: radio → no ACK → internet → fails → radio → no ACK.
            if !excludeRadio, await deliverViaLoRa(message: message, messageId: messageId) {
                OshiLog.messaging.info("📡 Sent via LoRa long-range radio (last resort)")
                return
            }

            await MainActor.run {
                self.uploadStatus[messageId] = .failed(message: error.localizedDescription)
                self.uploadProgress[messageId] = 0.0

                guard let manager = MessageManager.shared,
                      let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                manager.messages[index].deliveryStatus = .failed
                manager.saveMessages()

                // 📶 Add to retry queue for automatic retry when network becomes available
                let originalMessage = manager.messages[index]
                manager.addToRetryQueue(
                    messageId: messageId,
                    content: originalMessage.plaintextContent ?? "",
                    recipientKey: recipientPublicKey,
                    mediaData: originalMessage.mediaAttachment,
                    mediaType: originalMessage.mediaType,
                    mediaFileName: originalMessage.mediaFileName
                )

                // Clean up progress after a delay
                let messageIdCopy = messageId
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                    self?.uploadStatus.removeValue(forKey: messageIdCopy)
                    self?.uploadProgress.removeValue(forKey: messageIdCopy)
                }
            }
            throw error
        }
    }

    private func uploadToIPFS(message: SecureMessage, messageId: String, recipientPublicKey: String) async throws -> String {
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) One of the doors to the
        // internet. Every one of them is closed by the same line, because the
        // promise is "nothing of mine leaves over the network", and a door
        // somebody forgot is the whole of the broken promise.
        //
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ The line now asks about THIS
        // conversation as well as about the app. Same reasoning, one scope
        // narrower: a door that only knows the global switch is a door left
        // open for the conversation somebody deliberately took off the relay.
        guard await TransportPolicy.internetAllowedForSending(inConversation: recipientPublicKey) else {
            throw TransportPolicyError.internetSendingDisabled
        }
        let encryptedData = try JSONEncoder().encode(message.encryptedContent)
        
        // 🔐 Create inner payload (will be encrypted)
        let innerPayload: [String: Any] = [
            "id": message.id,
            "senderAddress": message.senderAddress,
            "recipientAddress": message.recipientAddress,
            "timestamp": message.timestamp.timeIntervalSince1970,
            "encryptedContent": encryptedData.base64EncodedString()
        ]
        
        // 🔐 Encrypt the entire payload to hide metadata
        let encryptedEnvelope = try encryptPayloadForIPFS(
            payload: innerPayload,
            recipientPublicKey: recipientPublicKey,
            senderPrivateKey: MessageManager.sharedIdentityManager?.privateKey?.rawRepresentation
        )
        
        // 🔐 Upload encrypted envelope with sender info for decryption
        // Include FULL sender public key so recipient can compute shared secret
        let senderPublicKey = MessageManager.sharedIdentityManager?.publicKey ?? ""
        let messageDict: [String: Any] = [
            "v": 3,  // Version 3: includes full sender key for first-message support
            "envelope": encryptedEnvelope.envelope,
            "nonce": encryptedEnvelope.nonce,
            "hint": String(recipientPublicKey.prefix(4)),  // Recipient hint (4 chars)
            "from": String(senderPublicKey.prefix(8)),  // Sender hint (8 chars) for quick matching
            "senderPublicKey": senderPublicKey  // FULL sender key for decryption
        ]
        
        let estimatedSize = encryptedEnvelope.envelope.count
        let sizeMB = Double(estimatedSize) / 1_000_000.0
        OshiLog.messaging.info("📦 Estimated upload size: \(String(format: "%.2f", sizeMB)) MB")
        OshiLog.messaging.info("🔐 Metadata encrypted - only envelope visible on IPFS")
        
        // Update progress - encoding complete
        await MainActor.run {
            self.uploadStatus[messageId] = .uploading(progress: 0.2)
            self.uploadProgress[messageId] = 0.2
        }
        
        if estimatedSize > 10_000_000 {
            OshiLog.messaging.info("⚠️ Large file detected (\(String(format: "%.2f", sizeMB)) MB)")
            OshiLog.messaging.info("   Upload may take longer...")
        }
        
        // Ceiling is on the encrypted base64 ENVELOPE. ⚠️ The "~1.37×" this comment
        // used to claim is wrong: the envelope is the tail of SIX nested base64
        // layers (see the TODO(wire-format) in sendMessage), measured at 5.62× the
        // raw file. So 75 MB of envelope carries only ~13 MB of user file, not 50 MB
        // — the check below is far tighter in practice than it reads. Envelopes above
        // the ~45 MB resumable threshold are chunked (see below) so they clear the
        // 50 MB single-POST nginx wall AND resume if interrupted. (Raising this
        // toward 200 MB is gated on streaming encryption to avoid the whole-file
        // Data → ~2× RAM jetsam risk measured in the transport audit.)
        let maxSize = 75_000_000  // ≈ 13 MB raw file after the nested base64 envelope
        if estimatedSize > maxSize {
            OshiLog.messaging.info("❌ File too large for IPFS (\(String(format: "%.2f", sizeMB)) MB)")
            OshiLog.messaging.info("   Maximum size: 50 MB")
            OshiLog.messaging.info("   Please use mesh network for larger files")
            throw PinataError.fileTooLarge
        }
        
        // Update progress - starting upload
        await MainActor.run {
            self.uploadStatus[messageId] = .uploading(progress: 0.3)
            self.uploadProgress[messageId] = 0.3
        }

        // 🔧 DIAG (2026-08-11, "10 s before the bar moves, 1 min for 800 KB"):
        // nothing timed this path, so the minute could only be guessed at. The bar
        // sits at exactly 0.30 from here until the first byte-progress callback,
        // which makes "when did the transfer actually start" the single most
        // useful number — and it was unrecorded.
        let uploadStartedAt = Date()
        let firstProgressBox = FirstProgressBox()
        
        // 🚀 PERF FIX: previously this called pinJSONWithProgress, which posts
        // to Pinata's /pinning/pinJSONToIPFS. That endpoint forces JSON
        // shape, so the encrypted envelope (already base64) gets wrapped in
        // another JSON object — and the JSON endpoint also applies stricter
        // rate limits and longer-than-needed timeouts. The file endpoint
        // (/pinning/pinFileToIPFS, multipart) streams binary payloads
        // directly, gives better progress events, and tolerates flaky
        // uploads better. We serialize the existing messageDict to JSON
        // bytes and ship as a file — recipient-side fetch is unchanged
        // (gateway returns the bytes, we parse as JSON exactly as before).
        // Wrapped in withPinataRetry: 3 attempts with 2s/4s/8s backoff on
        // transient errors (5xx, 429, network drop). Fast-fails on 4xx.
        let messageJSONData = try JSONSerialization.data(withJSONObject: messageDict)
        let pinName = "oshi_\(UUID().uuidString.prefix(8))"
        CallFileLogger.shared.log("DIAG_UPLOAD | phase=begin | id=\(messageId.prefix(8)) | envelopeBytes=\(messageJSONData.count) | background=\(messageJSONData.count >= PinataService.backgroundUploadThreshold)")
        let progressHandler: (Double) -> Void = { progress in
            // First callback tells us when bytes actually started moving — the gap
            // between `phase=begin` and this is time the user spends watching a bar
            // frozen at 30 %, and it was completely unmeasured.
            if firstProgressBox.stamp() {
                let dt = Date().timeIntervalSince(uploadStartedAt)
                CallFileLogger.shared.log("DIAG_UPLOAD | phase=first_byte_progress | id=\(messageId.prefix(8)) | afterMs=\(Int(dt * 1000))")
            }
            Task { @MainActor in
                if progress >= 0.99 {
                    // 🔧 UX FIX: all bytes uploaded — the server is now pinning to
                    // IPFS, which emits NO further progress events (often 30–90 s).
                    // Show an indeterminate "Processing…" state instead of freezing
                    // the bar at ~89%, which read as a stall to users.
                    self.uploadStatus[messageId] = .processing
                    self.uploadProgress[messageId] = 0.9
                } else {
                    // Map 0-1 byte progress to the 0.3-0.9 upload band.
                    let mappedProgress = 0.3 + (progress * 0.6)
                    self.uploadStatus[messageId] = .uploading(progress: mappedProgress)
                    self.uploadProgress[messageId] = mappedProgress
                }
            }
        }
        // 🔧 FIX (1.0.15): FOREGROUND upload wrapped in a UIBackgroundTask
        // assertion — NOT a background URLSession. The VPS→Pinata proxy pins in
        // ~3 s (measured), so a deferred background session was the wrong tool: it
        // added the system's start/response latency to every send, producing a
        // "hangs at ~90%, fails, works on manual retry" pattern on nearly every
        // upload. A plain foreground upload is immediate; the background-task
        // assertion below buys ~30 s of execution time (10× a typical upload) so
        // a quick app-switch mid-send doesn't kill it — which is the realistic
        // "survive backgrounding" case. withPinataRetry keeps transient-error
        // resilience. (BackgroundMediaUploader remains for future large transfers
        // but is no longer on the message-send hot path.)
        let bgTaskId = await MainActor.run {
            UIApplication.shared.beginBackgroundTask(withName: "OSHI-MediaUpload")
        }
        defer {
            if bgTaskId != .invalid {
                Task { @MainActor in UIApplication.shared.endBackgroundTask(bgTaskId) }
            }
        }
        // 🔧 MEDIA FIX: route large payloads (videos, multi-MB) through the
        // BACKGROUND URLSession and an idempotent, content-tagged retry. Previously
        // this used the 180 s foreground request, which — once the VPS→Pinata pin
        // started routing over a slow/jittery Tor circuit (~335 KB/s measured) —
        // would stall mid-transfer on multi-MB files, trip the idle timeout, and
        // re-upload from 0%, so "a few MB" videos failed while text/small images
        // went through. pinMediaResilient keeps small sends on the immediate
        // foreground path and only escalates large media to the background session;
        // its by-content retry reuses a pin that completed server-side instead of
        // restarting the whole transfer. (The bgTask assertion above still covers
        // the foreground small-upload sub-path.)
        // 🧩 LARGE-FILE FIX: once the base64-JSON payload approaches the 50 MB
        // nginx wall, a single POST would 413. Route it through the resumable
        // chunked uploader instead — each chunk stays under the wall, and an
        // interrupted transfer resumes from the last acknowledged byte (surviving
        // a network drop, backgrounding, or an app relaunch) rather than
        // restarting from zero. The finalized CID is identical to the single-POST
        // path, so recipient-side fetch is unchanged. Small payloads keep the
        // battle-tested foreground/background path below.
        let ipfsHash: String
        if messageJSONData.count > kResumableUploadThreshold {
            let payloadMB = Double(messageJSONData.count) / 1_000_000.0
            OshiLog.messaging.info("🧩 Large payload (\(String(format: "%.1f", payloadMB)) MB) → resumable chunked upload")
            ipfsHash = try await ResumableUploader.shared.upload(
                messageJSONData,
                contentTag: messageId,
                progress: progressHandler
            )
        } else {
            ipfsHash = try await PinataService.shared.pinMediaResilient(
                messageJSONData,
                pinName: pinName,
                messageId: messageId,
                recipientPublicKey: recipientPublicKey,
                senderPublicKey: senderPublicKey,
                maxAttempts: 3,
                label: "uploadToIPFS(\(messageId.prefix(8)))",
                progressHandler: progressHandler
            )
        }
        
        // 🔧 FIX (1.0.10 v3): Flip the local bubble to .sent the instant Pinata
        // returns the hash — BEFORE notifyVPS. The message is now safely on IPFS;
        // notifyVPS only enqueues the hash on our relay and fires the wake push.
        // Previously we waited for notifyVPS to finish before updating UI, which
        // meant if iOS suspended the app between Pinata-success and notifyVPS-
        // completion (background tap → swipe race), the bubble stayed on the
        // clock forever even though the recipient had received the message.
        // We also flush to disk here so a later force-kill of the app preserves
        // the correct state.
        await MainActor.run {
            self.uploadStatus[messageId] = .processing
            self.uploadProgress[messageId] = 0.95

            if let manager = MessageManager.shared,
               let index = manager.messages.firstIndex(where: { $0.id == messageId }) {
                manager.messages[index].deliveryStatus = .sent
                manager.messages[index].deliveryMethod = .ipfs
                manager.saveMessages()
                // The send is durable now — drop the pre-flight retry entry.
                manager.removeFromRetryQueue(messageId: messageId)
                OshiLog.messaging.info("✅ Bubble marked .sent immediately after Pinata upload (pre-notifyVPS)")
            }
        }

        // 🔕 2026-06-11 (b56): classify silent control traffic — protocol
        // payloads the peer must process but should never be alerted about.
        // Visible events (messages, media, missed calls, check-ins, location
        // shares) keep their pushes.
        //
        // __PUSH_LISTS_DRIFTED_2026_09_02__ Ce tableau de 8 préfixes vivait ici,
        // en double avec `v2SilentControlPrefixes`, contre 10 `.silent` au
        // catalogue: `🔧ACTION🔧`, `📢GROUP_UPDATE📢` et `__SESSION_RESET__` n'y
        // étaient dans NI l'un NI l'autre. Une neuvième copie partielle d'une
        // liste que `OSHIControlPayload` existe pour tenir à un seul endroit.
        // La décision « ça réveille ou pas » y est maintenant déclarée, avec ses
        // deux exceptions écrites (le signal d'appel sonne, l'avatar non).
        let isSilentControl = message.plaintextContent.map { pt in
            OSHIControlPayload.suppressesPush(pt)
            // Live-location UPDATE/STOP pings are silent too (only the initial
            // share notifies) — stops the every-10s duplicate banners when both
            // parties share their live location simultaneously.
            || LocationMessage.isSilentPushContent(pt)
        } ?? false

        try await notifyVPS(
            ipfsHash: ipfsHash,
            recipientPublicKey: recipientPublicKey,
            senderPublicKey: senderPublicKey,
            messageId: messageId,
            suppressPush: isSilentControl
        )

        return ipfsHash
    }

    // MARK: - Background Upload Completion (app killed + relaunched mid-transfer)

    /// Called by `BackgroundMediaUploader` when an IPFS upload finishes while the
    /// original `uploadToIPFS` continuation no longer exists (the app was killed
    /// mid-transfer and iOS relaunched us in the background to deliver the
    /// completion). Replicates the in-app post-upload steps: flip the bubble to
    /// .sent, persist, and notify the VPS so the recipient gets the hash + push.
    @MainActor
    func completeBackgroundUpload(messageId: String, recipientPublicKey: String, senderPublicKey: String, ipfsHash: String) {
        OshiLog.messaging.info("📤 [bg] completeBackgroundUpload id=\(messageId.prefix(8)) hash=\(ipfsHash.prefix(12)) (relaunch path)")
        uploadStatus[messageId] = .processing
        uploadProgress[messageId] = 0.95
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            messages[index].deliveryStatus = .sent
            messages[index].deliveryMethod = .ipfs
            saveMessages()
        }
        Task {
            do {
                try await notifyVPS(
                    ipfsHash: ipfsHash,
                    recipientPublicKey: recipientPublicKey,
                    senderPublicKey: senderPublicKey,
                    messageId: messageId
                )
                await MainActor.run {
                    self.uploadProgress[messageId] = 1.0
                    self.uploadStatus[messageId] = .completed
                }
                OshiLog.messaging.info("✅ [bg] notifyVPS done for relaunch-completed upload \(messageId.prefix(8))")
            } catch {
                OshiLog.messaging.info("⚠️ [bg] notifyVPS failed for relaunch upload \(messageId.prefix(8)): \(error.localizedDescription)")
            }
        }
    }

    /// Called by `BackgroundMediaUploader` when a background upload fails (after
    /// the OS's own transfer retries are exhausted, or a non-2xx response). Mark
    /// the bubble failed so the UI surfaces a retry affordance.
    @MainActor
    func failBackgroundUpload(messageId: String) {
        OshiLog.messaging.info("❌ [bg] failBackgroundUpload id=\(messageId.prefix(8))")
        uploadStatus[messageId] = .failed(message: "upload.status.failed".localized)
        uploadProgress.removeValue(forKey: messageId)
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            messages[index].deliveryStatus = .failed
            saveMessages()
        }
        // 🚧 A failed media upload is a visible failure. Quiet the rating ask.
        RateAppManager.shared.recordFriction(reason: "media-upload-failed")
    }

    // MARK: - Encrypt Payload for IPFS
    
    private struct EncryptedIPFSPayload {
        let envelope: String
        let nonce: String
    }
    
    private func encryptPayloadForIPFS(
        payload: [String: Any],
        recipientPublicKey: String,
        senderPrivateKey: Data?
    ) throws -> EncryptedIPFSPayload {
        // Serialize payload to JSON
        let payloadData = try JSONSerialization.data(withJSONObject: payload)
        
        // Derive shared secret using ECDH
        guard let privateKey = senderPrivateKey,
              let recipientKeyData = Data(base64Encoded: recipientPublicKey) else {
            throw PinataError.encryptionFailed
        }
        
        // Create CryptoKit keys
        let privateKeyObj = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let publicKeyObj = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientKeyData)
        
        // Derive shared secret
        let sharedSecret = try privateKeyObj.sharedSecretFromKeyAgreement(with: publicKeyObj)
        
        // Derive encryption key from shared secret
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: "oshi-ipfs-envelope-v2".data(using: .utf8)!,
            sharedInfo: Data(),
            outputByteCount: 32
        )
        
        // Generate nonce and encrypt with AES-GCM
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(payloadData, using: symmetricKey, nonce: nonce)
        
        // Combine ciphertext + tag
        var combined = Data()
        combined.append(sealed.ciphertext)
        combined.append(sealed.tag)
        
        return EncryptedIPFSPayload(
            envelope: combined.base64EncodedString(),
            nonce: Data(nonce).base64EncodedString()
        )
    }
    
    private func notifyVPS(
        ipfsHash: String,
        recipientPublicKey: String,
        senderPublicKey: String = "",
        messageId: String = "",
        suppressPush: Bool = false
    ) async throws {
        // __COVER_FAIL_CLOSED_2026_11_18__ cover mode: no 1:1 traffic on the undisguised relay.
        guard CoverTransport.legacyOneToOneRelayAllowed else { throw CoverTransport.Failure.legacyRelayRefused }
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) One of the doors to the
        // internet. Every one of them is closed by the same line, because the
        // promise is "nothing of mine leaves over the network", and a door
        // somebody forgot is the whole of the broken promise.
        //
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ The line now asks about THIS
        // conversation as well as about the app. Same reasoning, one scope
        // narrower: a door that only knows the global switch is a door left
        // open for the conversation somebody deliberately took off the relay.
        guard await TransportPolicy.internetAllowedForSending(inConversation: recipientPublicKey) else {
            throw TransportPolicyError.internetSendingDisabled
        }
        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        let encodedKey = base64urlEncode(recipientPublicKey)

        // 🔕 2026-06-11 (b56): silent control traffic (read/delivery receipts,
        // profile sync, wallpaper, typing) queues with ?nopush=1 so the server's
        // queue-fallback doesn't fire a generic "You have a new message" alert.
        // These phantom banners were the "I get a notification when I SEND a
        // message" bug (recipient's auto delivery-receipt pushed the sender)
        // and the "notification but no message in the conversation" reports.
        let fullURL = "\(currentVPSURL)/api/queue/\(encodedKey)/\(ipfsHash)\(suppressPush ? "?nopush=1" : "")"

        OshiLog.messaging.info("📡 NOTIFYING VPS (queueing message for recipient)")
        OshiLog.messaging.info("📡 Recipient: \(recipientPublicKey.prefix(8))... (\(recipientPublicKey.count) chars)")
        OshiLog.messaging.info("📡 IPFS hash: \(ipfsHash)")

        guard let url = URL(string: fullURL) else {
            OshiLog.messaging.info("❌ Invalid VPS URL")
            throw NSError(domain: "VPS", code: -1)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 10

        // 🔧 2026-06-09 (b43): pass `senderPublicKey` in the queue POST body
        // so the server's queue-fallback push carries a real sender. Without
        // this, /queue-fallback fired sendMessagePush with senderPublicKey=''
        // → APNs payload had empty sender → tap on the notification couldn't
        // deep-link to the conversation and dumped the user on the chat list.
        if !senderPublicKey.isEmpty || !messageId.isEmpty {
            var queueBody: [String: String] = [:]
            if !senderPublicKey.isEmpty { queueBody["senderPublicKey"] = senderPublicKey }
            if !messageId.isEmpty { queueBody["messageId"] = messageId }
            if let bodyData = try? JSONSerialization.data(withJSONObject: queueBody) {
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = bodyData
            }
        }

        // 🔧 FIX #4 (2026-06-07, v1.0.19): chain a SECOND background task token
        // for the push leg. The outer sendMessage task already holds one
        // (~30s), but a slow IPFS upload + sync clogging the HTTP/2 pool can
        // burn most of that before we get here. Acquiring a fresh token now
        // gives the push call its own ~30s window even if the user backgrounds
        // the app right after Send. The token is end()'d in defer either way.
        let pushBg = SendBackgroundTaskHolder()
        // UIApplication.shared is MainActor-isolated — acquire the token on main.
        pushBg.id = await MainActor.run {
            UIApplication.shared.beginBackgroundTask(withName: "OshiPushFollowup") { [pushBg] in
                OshiLog.messaging.info("⚠️ OshiPushFollowup bg task expiring — ending now")
                pushBg.end()
            }
        }
        OshiLog.messaging.info("🔋 OshiPushFollowup bg task acquired: id=\(pushBg.id.rawValue)")

        // 🔧 FIX #1 (2026-06-07, v1.0.19): DECOUPLE push from queue success.
        // The previous flow waited for /api/queue to return 200 before firing
        // /send-push. When /api/queue's HTTP/2 stream was held up by a giant
        // MultiDeviceSync upload (~200MB body bombs), the 5s push timeout
        // expired BEFORE we even got to call it → recipient never woken.
        // Now we fire both in parallel: the push wakes the recipient who polls
        // /api/pending and finds the queued hash whenever it lands (≤10s).
        // If the push HTTP call fails (5s timeout / network blip), it lands in
        // PushRetryQueue (Fix #2) and retries on next foregrounding.
        var extra: [String: String] = [:]
        if !senderPublicKey.isEmpty {
            extra["senderPublicKey"] = senderPublicKey
            extra["senderKey"] = senderPublicKey
        }
        if !messageId.isEmpty { extra["messageId"] = messageId }
        // 🔧 2026-06-11 (b54): include ipfsHash so the recipient's NSE can
        // fetch the encrypted blob the moment the push arrives, cache it
        // in the App Group container, and the main app reads it directly
        // (no second HTTP round-trip) when the user opens the chat.
        // Without this, the NSE sees messageId (a UUID) but has no IPFS
        // address to fetch.
        if !ipfsHash.isEmpty { extra["ipfsHash"] = ipfsHash }
        extra["bodyLocKey"] = "notification.push.body.encrypted"
        extra["titleLocKey"] = "notification.push.title.message"
        // Immutable snapshot — the async-let closure below is @Sendable and
        // must not capture the mutable `extra` var (Swift 6 error).
        let extraSnapshot = extra

        // Fire push in parallel — don't await sequentially.
        async let pushFireResult: Void = {
            // 🔕 2026-06-11 (b56): no client push for silent control traffic.
            // The data still queues above; the peer picks it up on its next
            // poll. Without this guard every read receipt / profile update
            // fired a "You have a new encrypted message" banner at the peer.
            if suppressPush {
                OshiLog.messaging.info("🔕 Push suppressed (silent control message)")
                return
            }
            do {
                try await VoIPPushManager.requestPushNotification(
                    to: recipientPublicKey,
                    type: "message",
                    title: "New Message",
                    body: "You have a new encrypted message",
                    additionalData: extraSnapshot
                )
                OshiLog.messaging.info("📲 Push notification sent for VPS message")
            } catch {
                OshiLog.messaging.info("⚠️ Push notification failed: \(error.localizedDescription) — enqueuing for retry")
                // 🔧 FIX #2: persist failed pushes for retry on next foregrounding.
                let extraCopy = extraSnapshot
                await MainActor.run {
                    PushRetryQueue.shared.enqueue(
                        recipientPublicKey: recipientPublicKey,
                        type: "message",
                        title: "New Message",
                        body: "You have a new encrypted message",
                        additionalData: extraCopy
                    )
                }
            }
        }()

        // Now do the queue POST. If it fails we still want the push to fire
        // (it's already racing). The recipient's first poll might find nothing,
        // but subsequent polls or the retry path on our side will surface the
        // hash within the recipient's normal poll cadence.
        var queueError: Error?
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                OshiLog.messaging.info("📡 VPS response: \(httpResponse.statusCode)")
                if httpResponse.statusCode == 200 {
                    let responseString = String(data: data, encoding: .utf8) ?? ""
                    OshiLog.messaging.info("✅ VPS queued: \(responseString)")
                } else {
                    let responseString = String(data: data, encoding: .utf8) ?? "No response"
                    OshiLog.messaging.info("❌ VPS error: \(responseString)")
                    queueError = NSError(domain: "VPS", code: httpResponse.statusCode)
                }
            }
        } catch {
            OshiLog.messaging.info("❌ VPS notification failed: \(error)")
            queueError = error
        }

        // Wait for the parallel push call to finish (or fail and enqueue) so
        // the bg-task end() in defer doesn't yank it mid-flight.
        _ = await pushFireResult

        // Release the push-leg bg task.
        pushBg.end()

        if let queueError = queueError { throw queueError }
        OshiLog.messaging.info("✅ Notified VPS to queue message")
    }
    
    // MARK: - Send Message with Media Support
    
    func sendMediaMessage(
        attachment: MediaManager.MediaAttachment,
        recipientAddress: String,
        identityManager: IdentityManager,
        meshManager: MeshNetworkManager,
        mediaManager: MediaManager
    ) throws {
        OshiLog.messaging.info("📸 [WRAPPER] Preparing to send media message...")
        OshiLog.messaging.info("   Media type: \(attachment.type.rawValue)")
        OshiLog.messaging.info("   Media size: \(attachment.data.count / 1000) KB")
        OshiLog.messaging.info("   View once: \(attachment.isViewOnce)")
        
        sendMessage(
            content: "",
            recipientAddress: recipientAddress,
            identityManager: identityManager,
            meshManager: meshManager,
            mediaAttachment: attachment.data,
            mediaType: attachment.type,
            mediaFileName: attachment.fileName,
            isViewOnce: attachment.isViewOnce  // ✅ Pass view once flag
        )
    }
    
    /// Splitting + rate-limiting gate for 1:1 sends.
    ///
    /// Everything that sends a 1:1 message reaches `sendMessage` — the composer,
    /// the share extension, scheduled messages, forwards, Watch dictation, the
    /// web companion, notification quick-reply — so the two rules are enforced
    /// here once instead of at ~15 call sites that would drift apart.
    ///
    /// Both rules apply ONLY to user prose (`MessageTextSplitter.isUserProse`).
    /// Read receipts, typing, profile sync and live-location updates ride the
    /// same function and are passed through untouched: splitting one would
    /// produce two unparseable halves, and rate-limiting them would break
    /// delivery state on a busy conversation.
    ///
    /// Returns what it did. Existing callers ignore the value
    /// (`@discardableResult`), so no behaviour changed for them; the composer
    /// reads it so a refused send puts the text back in the box.
    @discardableResult
    /// __LORA_INTEROP_REPLY_2026_10_28__ Answer a `lora!<nodehex>` conversation.
    ///
    /// Until today this was refused outright (`__LORA_REPLY_REFUSAL_2026_10_26__`),
    /// on the grounds that such a thread "is not addressed to a key, so there is
    /// nothing to encrypt to". The first half is true and the conclusion was
    /// wrong: the message that OPENED the thread arrived as a raw Meshtastic text
    /// packet on portnum 1, and **that lane runs both ways**. The reply goes back
    /// to the same node number on the same channel, which is exactly how the
    /// stock Meshtastic app answers. Refusing left the user looking at a
    /// conversation the app had itself created and could not reply in.
    ///
    /// **This is PLAINTEXT and the bubble must say so.** The interop channel's
    /// key is the public default one, so every radio in range can read it, and
    /// nothing here is end-to-end encrypted. The stored message carries the same
    /// `lora_interop` signature the inbound ones do, which is the marker the
    /// chat renders from — an outgoing bubble that looked like every other
    /// outgoing bubble would be the app lying about who can read it.
    ///
    /// Three limits of the lane remain, each reported rather than swallowed:
    /// no radio attached, media (LoRa carries short text only), and a text too
    /// long for one frame — interop text is never chunked, because a stock
    /// client has no reassembly for OSHI's chunk header and would show
    /// fragments nobody joins.
    private func sendLoRaInteropReply(
        content: String,
        conversationKey: String,
        identityManager: IdentityManager,
        mediaAttachment: Data?
    ) -> MessageSendOutcome {
        guard mediaAttachment == nil else {
            OshiLog.messaging.info("🚫 LoRa interop: media cannot ride this lane")
            return .loRaInteropMediaUnsupported
        }
        // A key that is not exactly `lora!` + 8 hex digits has no node to answer,
        // so the old refusal is still the honest answer for it.
        guard let node = LoRaNodeIdentityMap.nodeNumber(fromFallbackKey: conversationKey) else {
            OshiLog.messaging.info("🚫 LoRa interop: no node in \(conversationKey.prefix(14))…")
            return .loRaUnidentified
        }
        let radio = MeshtasticManager.shared
        guard radio.isReady else {
            OshiLog.messaging.info("🚫 LoRa interop: no radio attached")
            return .loRaInteropNoRadio
        }
        let bytes = content.utf8.count
        let limit = MeshtasticManager.interopTextLimit
        guard bytes > 0 else { return .loRaUnidentified }
        guard bytes <= limit else {
            OshiLog.messaging.info("🚫 LoRa interop: \(bytes) B over the \(limit) B frame")
            return .loRaInteropTooLong(bytes: bytes, limit: limit)
        }

        radio.sendText(content, to: node)

        let ts = MessageSendClock.next()
        let sent = SecureMessage(
            id: UUID().uuidString,
            senderAddress: identityManager.publicKey,
            recipientAddress: conversationKey,
            encryptedContent: EncryptedMessage(ciphertext: "",
                                               signature: "lora_interop",
                                               senderPublicKey: identityManager.publicKey,
                                               timestamp: ts),
            timestamp: ts,
            isRead: true,
            deliveryStatus: .sent,
            senderPublicKey: identityManager.publicKey,
            recipientPublicKey: conversationKey,
            plaintextContent: content,
            // __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ This was
            // `.lora`, which the doc comment three screens up already contradicts
            // in as many words: "this is PLAINTEXT and the bubble must say so".
            // `.lora` means an OSHI envelope the ratchet authenticated; a raw
            // interop frame on a public-key channel is the other lane, and the
            // inbound half of this very conversation is stamped `.loraPlaintext`.
            deliveryMethod: .loraPlaintext
        )
        // `.sent` and not `.delivered`: the radio took the frame. Whether the
        // far node received it is what an ACK would say, and a raw interop text
        // sent to a stock client gets no OSHI receipt — claiming delivery here
        // would be the defect `lora-handoff-was-counted-as-delivery` already was.
        DispatchQueue.main.async { [weak self] in
            self?.addMessage(sent)
        }
        OshiLog.messaging.info("📡 LoRa interop reply \(bytes) B → \(String(format: "!%08x", node))")
        return .sent
    }

    /// __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ Speak into a LoRa channel.
    ///
    /// Addressed to the slot the channel occupies on THIS radio, not to a node:
    /// a broadcast is what everyone in range hears, and that is the whole point
    /// of a room. `MeshtasticManager.sendText` defaults to the broadcast
    /// address, so the frame goes out exactly as the stock Meshtastic app sends
    /// one.
    ///
    /// Stamped `.loraPlaintext`, never `.lora`. The channel key is public — on
    /// LongFast it is the published default — so every radio in range can read
    /// this, and `.lora` means an OSHI envelope the ratchet authenticated.
    /// Drawing this one as an authenticated envelope would tell the user their
    /// message was private when it is the opposite.
    ///
    /// `.sent` and not `.delivered`, for the reason the interop reply gives at
    /// length: the radio took the frame, and Meshtastic never acknowledges a
    /// broadcast at all, so there is nothing that could promote this further.
    private func sendToLoRaChannel(
        channelKey: String,
        content: String,
        identityManager: IdentityManager,
        mediaAttachment: Data?
    ) -> MessageSendOutcome {
        guard mediaAttachment == nil else {
            OshiLog.messaging.info("🚫 LoRa channel: media cannot ride this lane")
            return .loRaInteropMediaUnsupported
        }
        let radio = MeshtasticManager.shared
        guard radio.isReady else {
            OshiLog.messaging.info("🚫 LoRa channel: no radio attached")
            return .loRaInteropNoRadio
        }
        let bytes = content.utf8.count
        let limit = MeshtasticManager.interopTextLimit
        guard bytes > 0 else { return .loRaUnidentified }
        guard bytes <= limit else {
            OshiLog.messaging.info("🚫 LoRa channel: \(bytes) B over the \(limit) B frame")
            return .loRaInteropTooLong(bytes: bytes, limit: limit)
        }

        // __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ WHICH channel.
        //
        // This used to be a bare `radio.sendText(content)`, and the doc comment
        // above claims the frame is "addressed to the slot the channel occupies
        // on THIS radio" — it was not. `MeshPacket.channel` (field 3) was never
        // written by the packet builder at all, so every room's text went out
        // on slot 0: the primary, normally LongFast, normally public. Typing in
        // a private channel's room broadcast it on the public one.
        guard let slot = LoRaChannelThread.channelIndex(forKey: channelKey,
                                                        in: radio.nodeChannels) else {
            OshiLog.messaging.info("🚫 LoRa channel: \(channelKey.prefix(20))… is not on this radio")
            return .loRaChannelUnknown
        }
        radio.sendText(content, channel: slot)

        let ts = MessageSendClock.next()
        let sent = SecureMessage(
            id: UUID().uuidString,
            senderAddress: identityManager.publicKey,
            recipientAddress: LoRaChannelThread.displayName(channelKey),
            encryptedContent: EncryptedMessage(ciphertext: "",
                                               signature: "lora_interop",
                                               senderPublicKey: identityManager.publicKey,
                                               timestamp: ts),
            timestamp: ts,
            isRead: true,
            deliveryStatus: .sent,
            senderPublicKey: identityManager.publicKey,
            recipientPublicKey: channelKey,
            plaintextContent: content,
            deliveryMethod: .loraPlaintext
        )
        DispatchQueue.main.async { [weak self] in
            self?.addMessage(sent)
        }
        OshiLog.messaging.info("📡 LoRa channel \(bytes) B → \(LoRaChannelThread.displayName(channelKey))")
        return .sent
    }

    /// __MESHCORE_INTEROP_2026_11_25__ Reply to a MeshCore user, or speak in a MeshCore channel.
    ///
    /// The MeshCore twin of `sendLoRaInteropReply` / `sendToLoRaChannel`, with the same three honesty
    /// rules: stamped `.loraPlaintext` with the `meshcore_interop` signature so the bubble says
    /// "MeshCore · not OSHI end-to-end encrypted"; refused (never parked for the relay) when no MeshCore
    /// radio is connected, since a MeshCore id has no OSHI key to encrypt to; and never `.delivered`
    /// unless the recipient's radio ACKed every part — a channel message stays `.sent`.
    ///
    /// Unlike the Meshtastic lane, long text is split into numbered parts ("(1/3) …"): MeshCore clients
    /// show each part as a message and the numbers make the order readable without any reassembly.
    func sendToMeshCore(
        content: String,
        conversationKey: String,
        identityManager: IdentityManager,
        mediaAttachment: Data?
    ) -> MessageSendOutcome {
        guard mediaAttachment == nil else {
            OshiLog.messaging.info("🚫 MeshCore: media cannot ride this lane")
            return .loRaInteropMediaUnsupported
        }
        let radio = meshCoreRadio
        // MeshCore has no reply envelope: a swipe-to-reply leaves as the typed text, not as OSHI's JSON.
        var content = content
        if content.hasPrefix(MessageWithReply.replyPrefix),
           let parsed = MessageWithReply.fromMessageString(content) { content = parsed.content }
        // No radio right now (out of reach, switched off, reconnecting): what was typed is kept as a failed
        // row with tap-to-retry, as on Android, and the note above the composer says why. It used to be an
        // alert, and a person who walked out of range lost the thread of what they were writing.
        guard radio.isTextReady else {
            OshiLog.messaging.info("🚫 MeshCore: no radio attached — kept as not delivered")
            let budget = MeshCoreInterop.isChannelKey(conversationKey)
                ? MeshCoreInterop.channelTextBytes(nodeName: radio.selfName) : MeshCoreInterop.dmTextBytes
            guard MeshCoreInterop.splitText(content, maxBytes: budget) != nil else {
                let bytes = content.utf8.count
                if bytes == 0 { return .loRaUnidentified }
                return .loRaInteropTooLong(bytes: bytes, limit: MeshCore.maxTextBytes * MeshCoreInterop.maxParts)
            }
            addMessage(meshCoreRow(content: content, conversationKey: conversationKey,
                                   identityManager: identityManager, status: .failed))
            return .sent
        }
        let target: MeshCoreManager.TextTarget
        if MeshCoreInterop.isChannelKey(conversationKey) {
            guard let slot = radio.channelSlot(forKey: conversationKey) else {
                OshiLog.messaging.info("🚫 MeshCore: \(conversationKey.prefix(24)) is not on this radio")
                return .loRaChannelUnknown
            }
            target = .channel(slot)
        } else {
            guard let key = MeshCoreInterop.publicKeyBytes(fromContactKey: conversationKey) else {
                return .loRaUnidentified
            }
            target = .contact(Data(key.prefix(6)))
        }
        let bytes = content.utf8.count
        guard let parts = radio.textParts(content, for: target) else {
            if bytes == 0 { return .loRaUnidentified }
            return .loRaInteropTooLong(bytes: bytes, limit: MeshCore.maxTextBytes * MeshCoreInterop.maxParts)
        }

        let sent = meshCoreRow(content: content, conversationKey: conversationKey,
                               identityManager: identityManager, status: .pending)
        // Stored first, then handed to the radio on the same queue, so the radio's first status report
        // always finds the row.
        DispatchQueue.main.async { [weak self] in
            self?.addMessage(sent)
            if !radio.sendText(parts, to: target, messageId: sent.id) {
                self?.meshCoreTextStatus(messageId: sent.id, status: .failed)
            }
        }
        OshiLog.messaging.info("📡 MeshCore text \(bytes) B in \(parts.count) part(s) → \(conversationKey.prefix(24))")
        return .sent
    }

    private func meshCoreRow(content: String, conversationKey: String, identityManager: IdentityManager,
                             status: DeliveryStatus) -> SecureMessage {
        let ts = MessageSendClock.next()
        return SecureMessage(
            id: UUID().uuidString,
            senderAddress: identityManager.publicKey,
            recipientAddress: MeshCoreInterop.displayName(forKey: conversationKey, contacts: meshCoreRadio.contacts)
                ?? conversationKey,
            encryptedContent: EncryptedMessage(ciphertext: "",
                                               signature: MeshCoreInterop.signature,
                                               senderPublicKey: identityManager.publicKey,
                                               timestamp: ts),
            timestamp: ts,
            isRead: true,
            deliveryStatus: status,
            senderPublicKey: identityManager.publicKey,
            recipientPublicKey: conversationKey,
            plaintextContent: content,
            deliveryMethod: .loraPlaintext
        )
    }

    /// The MeshCore radio this manager talks through (a test radio in tests).
    var meshCoreRadio: MeshCoreManager { meshCoreRadioOverride ?? MeshCoreManager.shared }

    /// Tap-to-retry on a failed MeshCore bubble: the SAME row goes back to the radio (pending → sent →
    /// delivered/failed), so the conversation keeps its order and nothing typed is ever deleted. A refusal
    /// (no radio, channel gone) leaves the row failed and says why.
    @discardableResult
    func retryMeshCoreText(messageId: String) -> MessageSendOutcome {
        guard let i = messages.firstIndex(where: { $0.id == messageId }) else { return .loRaUnidentified }
        let row = messages[i]
        let key = row.recipientPublicKey
        let radio = meshCoreRadio
        guard radio.isTextReady else { return .loRaInteropNoRadio }
        let target: MeshCoreManager.TextTarget
        if MeshCoreInterop.isChannelKey(key) {
            guard let slot = radio.channelSlot(forKey: key) else { return .loRaChannelUnknown }
            target = .channel(slot)
        } else {
            guard let bytes = MeshCoreInterop.publicKeyBytes(fromContactKey: key) else { return .loRaUnidentified }
            target = .contact(Data(bytes.prefix(6)))
        }
        guard let parts = radio.textParts(row.plaintextContent ?? "", for: target) else { return .loRaUnidentified }
        messages[i].deliveryStatus = .pending
        saveMessages()
        objectWillChange.send()
        if !radio.sendText(parts, to: target, messageId: messageId) {
            meshCoreTextStatus(messageId: messageId, status: .failed)
            return .loRaInteropNoRadio
        }
        return .sent
    }

    /// The MeshCore radio's verdict on a text it was handed. Forward moves only; `.failed` never
    /// overrides a message something already confirmed delivered.
    func meshCoreTextStatus(messageId: String, status: DeliveryStatus) {
        guard let i = messages.firstIndex(where: { $0.id == messageId }) else { return }
        let current = messages[i].deliveryStatus
        let next: DeliveryStatus
        if status == .failed {
            guard current.rank < DeliveryStatus.delivered.rank, current != .failed else { return }
            next = .failed
        } else {
            next = current.advanced(to: status)
        }
        guard next != current else { return }
        messages[i].deliveryStatus = next
        saveMessages()
        objectWillChange.send()
    }

    func sendMessage(
        content: String,
        recipientAddress: String,
        identityManager: IdentityManager,
        meshManager: MeshNetworkManager,
        mediaAttachment: Data? = nil,
        mediaType: MediaManager.MediaType? = nil,
        mediaFileName: String? = nil,  // ✅ Original filename for documents
        isViewOnce: Bool = false,      // ✅ NEW: View once (ephemeral) message
        skipLocalMessage: Bool = false, // 📍 For live location updates - don't create new local message
        // __REVEAL_OUTBOX_2026_08_22__ Planification de révélation, quand cet
        // envoi est une COUCHE. nil pour tout le reste — c'est-à-dire pour la
        // quasi-totalité du trafic, dont le chemin ne change pas d'une ligne.
        reveal: RevealSendContext? = nil,
        /// Identifiant IMPOSÉ au message, au lieu de celui qu'on tire au sort.
        ///
        /// Une révélation a besoin que l'appelant connaisse l'identifiant AVANT
        /// l'envoi: c'est lui qui relie les couches suivantes à la bulle créée
        /// par la couche 0, chez le destinataire. Sans ça, l'appelant devrait
        /// deviner un UUID tiré au fond de `sendSingleMessage`.
        forcedMessageId: String? = nil,
        /// __VIDEO_NOTE_2026_09_24__ Non-nil only for a round video message
        /// (docs/VIDEO_NOTE_SPEC.md). Ignored unless `mediaType == .video`.
        videoNote: VideoNoteMetadata? = nil
    ) -> MessageSendOutcome {
        // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ CR / CRLF / U+2028 / U+2029 / VT
        // from a paste become LF, so the recipient's bubble keeps the paragraphs
        // on every platform (Android breaks lines on LF only). Prose only; a
        // reply/forward is re-wrapped around its normalised body.
        let content = mediaAttachment == nil ? MessageTextSplitter.normalizedForSend(content) : content
        let isUserAction = mediaAttachment != nil || MessageTextSplitter.isUserProse(content)

        // 🚫 BLOQUÉ — on refuse ICI, au même point d'étranglement que la limite
        // de débit, et pour la raison écrite juste au-dessus: la composition,
        // l'extension de partage, les messages programmés, les transferts, la
        // Watch, le compagnon web et la réponse rapide depuis une notification
        // passent tous par cette fonction. Bloquer doit vouloir dire « rien ne
        // part vers cette clé », et c'est le seul endroit où ce soit vrai pour
        // tous les appelants. Parité avec Android, `MessageRepository`
        // (`sendTextMessage` / `sendMediaMessage`).
        //
        // Le test porte sur `isUserAction`, donc le trafic de protocole passe
        // intact — accusés, frappe, synchro de profil, position en direct, et
        // l'avis « vous êtes bloqué » lui-même, qui est précisément un message
        // que cet appareil doit pouvoir envoyer à une clé bloquée.
        // __LORA_REPLY_REFUSAL_2026_10_26__ A "lora!<nodehex>" conversation is
        // not addressed to a key: it is the fallback thread opened for a raw,
        // UNAUTHENTICATED Meshtastic text packet whose node has never proved an
        // OSHI identity (`LoRaNodeIdentityMap`). Encrypting to that string
        // cannot work, and the attempt used to fail silently — the user pressed
        // Send and nothing whatsoever happened. Refuse here, at the same choke
        // point as blocking and the rate limiter, so every composer says why.
        // __MESHCORE_INTEROP_2026_11_25__ A MeshCore person or channel: plain MeshCore text through the
        // MeshCore radio, or a refusal saying why. Checked before the LoRa channel branch, whose
        // `isChannelKey` also matches `mcchan!` rooms (for the room behaviour) but whose radio is Meshtastic.
        // Protocol traffic (receipts, typing, profile) never reaches a MeshCore id: there is nobody on the OSHI
        // side to read it, and the radio must not carry it as text. Typed text that merely looks like JSON does.
        if MeshCoreInterop.isContactKey(recipientAddress) || MeshCoreInterop.isChannelKey(recipientAddress) {
            let typed = isUserAction || (mediaAttachment == nil && !content.isEmpty
                && !MessageTextSplitter.controlPrefixes.contains { content.hasPrefix($0) })
            guard typed else { return .loRaUnidentified }
            return sendToMeshCore(content: content,
                                  conversationKey: recipientAddress,
                                  identityManager: identityManager,
                                  mediaAttachment: mediaAttachment)
        }

        if isUserAction, LoRaNodeIdentityMap.isFallbackConversationKey(recipientAddress) {
            return sendLoRaInteropReply(content: content,
                                        conversationKey: recipientAddress,
                                        identityManager: identityManager,
                                        mediaAttachment: mediaAttachment)
        }

        // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ Writing into a channel BROADCASTS
        // on the radio, or is refused outright.
        //
        // Checked at the same choke point and above the ordinary lanes, because a
        // channel has no public key, no ratchet and no relay mailbox: there is
        // nothing to fall back to. A bubble that could never be delivered, parked
        // in a queue addressed to a key that does not exist, would be a lie told
        // to a user who thinks they have spoken to the room.
        if isUserAction, LoRaChannelThread.isChannelKey(recipientAddress) {
            return sendToLoRaChannel(channelKey: recipientAddress,
                                     content: content,
                                     identityManager: identityManager,
                                     mediaAttachment: mediaAttachment)
        }

        if isUserAction, BlockedContactsManager.shared.isBlocked(recipientAddress) {
            OshiLog.messaging.info("🚫 Envoi refusé: destinataire bloqué \(recipientAddress.prefix(8))…")
            return .blocked
        }

        // Decide the split FIRST, and only then charge the rate limiter — a
        // paste we are about to refuse for being too long must not also eat one
        // of the user's 100 tokens, or repeatedly hitting Send on a document
        // would lock them out of the conversation for a minute.
        var plan: MessageTextSplitter.SplitOutcome = .single
        // Media rides its own path (caption only); never split a caption away
        // from its attachment.
        if mediaAttachment == nil {
            // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ Was `loRaPaired:
            // isRadioPaired` — 500 B for every message of anyone who ever paired
            // a radio, so a 2,000-char paste left as 5 messages over the relay.
            // __LORA_BUDGET_FOLLOWS_THE_ROUTE_2026_10_27__ and it is no longer
            // "off-grid" that decides: the four facts below are exactly the ones
            // `TransportPolicy.order(_:)` uses to decide whether the radio is
            // tried, so the text is sized for the transport that will carry it.
            let limit = MessageTextSplitter.maxUTF8Bytes(
                radioPaired: MeshCoreManager.anyLoRaRadioPaired,
                hasPhysicalPath: NetworkReachabilityManager.shared.hasPhysicalPath,
                internetAllowedForSending: TransportPolicy.internetAllowedForSending,
                prefersRadioOverRelay: TransportPolicy.preferRadioOverRelay,
                // __LORA_LONG_TEXT_OMP_2026_11_18__ An OSHI Mesh radio carries the whole envelope as
                // OMP (64 x 182 B): 4,000 B per message instead of 500, so Seeed's 2,000-char text
                // leaves as ONE message instead of five. Remembered per paired radio.
                oshiFirmware: MeshtasticManager.lastKnownOshiFirmware)
            plan = MessageTextSplitter.splitForSend(content, maxUTF8Bytes: limit)
            if case .tooLong(let needed) = plan {
                OshiLog.messaging.info("⛔️ send refused: \(needed) parts needed, cap is \(MessageTextSplitter.maxParts)")
                return .tooLong
            }
        }

        if isUserAction {
            // One token per user action, however many envelopes it takes: the
            // parts of one paste are one action, not N. See MessageSendPolicy.
            if case .denied(let retryAfter) = MessageSendRateLimiter.shared.consume() {
                OshiLog.messaging.info("⛔️ send refused: rate limit (retry in \(retryAfter)s)")
                return .rateLimited(retryAfterSeconds: retryAfter)
            }
        }

        if case .parts(let parts) = plan {
            OshiLog.messaging.info("✂️ splitting \(content.utf8.count) B into \(parts.count) messages")
            for part in parts {
                sendSingleMessage(
                    content: part,
                    recipientAddress: recipientAddress,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    mediaAttachment: nil,
                    mediaType: nil,
                    mediaFileName: nil,
                    isViewOnce: isViewOnce,
                    skipLocalMessage: skipLocalMessage
                )
            }
            return .sentAsParts(parts.count)
        }

        sendSingleMessage(
            content: content,
            recipientAddress: recipientAddress,
            identityManager: identityManager,
            meshManager: meshManager,
            mediaAttachment: mediaAttachment,
            mediaType: mediaType,
            mediaFileName: mediaFileName,
            isViewOnce: isViewOnce,
            skipLocalMessage: skipLocalMessage,
            reveal: reveal,
            forcedMessageId: forcedMessageId,
            videoNote: mediaType == .video ? videoNote : nil
        )
        return .sent
    }

    /// The original, unchanged send pipeline. Called once per message that
    /// actually goes out — once for a normal send, once per part for a split.
    private func sendSingleMessage(
        content: String,
        recipientAddress: String,
        identityManager: IdentityManager,
        meshManager: MeshNetworkManager,
        mediaAttachment: Data? = nil,
        mediaType: MediaManager.MediaType? = nil,
        mediaFileName: String? = nil,
        isViewOnce: Bool = false,
        skipLocalMessage: Bool = false,
        reveal: RevealSendContext? = nil,
        forcedMessageId: String? = nil,
        videoNote: VideoNoteMetadata? = nil
    ) {
        // __IDENTITY_CANONICAL_2026_08_15__ Canonicalise the peer key BEFORE it is
        // used for anything. It becomes three different things below, and each one
        // is an exact-string match somewhere:
        //   * the v2 envelope's `to` (the relay's queue key);
        //   * the `GET /v2/keys/:userKey` path — a raw map read on the server, so a
        //     base64url spelling 404s and `capabilities` reports `.unavailable`,
        //     which drops the send onto the legacy IPFS path without a word;
        //   * `recipientPublicKey` on the local bubble, which is what
        //     `ChatView.computeLoadedMessages` matches the conversation on.
        let recipientAddress = MessageManager.canonicalIdentity(recipientAddress)

        // __REVEAL_OUTBOX_2026_08_22__ Déposer la planification AVANT que l'envoi
        // parte: le transport la relira par identifiant de message, y compris
        // après une reprise.
        if let reveal, let id = forcedMessageId {
            registerRevealContext(reveal, forMessageId: id)
        }

        OshiLog.messaging.info("==================================================")
        OshiLog.messaging.info("🚀 sendMessage CALLED")
        OshiLog.messaging.info("   content: \(content.isEmpty ? "[EMPTY]" : "[\(content.count) chars]")")
        OshiLog.messaging.info("   hasMedia: \(mediaAttachment != nil)")
        if let media = mediaAttachment {
            OshiLog.messaging.info("   mediaSize: \(media.count / 1000) KB")
            OshiLog.messaging.info("   mediaType: \(mediaType?.rawValue ?? "nil")")
            OshiLog.messaging.info("   mediaFileName: \(mediaFileName ?? "nil")")
        }
        
        // 🔧 CRITICAL: Force fresh mesh status check before routing decision
        ensureFreshMeshStatus()
        OshiLog.messaging.info("   Mesh available: \(isMeshAvailable ? "YES ✅" : "NO ❌")")
        
        if let manager = MessageManager.sharedMeshManager {
            OshiLog.messaging.info("   Mesh manager: EXISTS")
            OshiLog.messaging.info("   Connected peers: \(manager.connectedPeers.count)")
            OshiLog.messaging.info("   Is advertising: \(manager.isAdvertising)")
            OshiLog.messaging.info("   Is browsing: \(manager.isBrowsing)")
        } else {
            OshiLog.messaging.info("   Mesh manager: NIL")
        }
        OshiLog.messaging.info("==================================================")
        
        MessageManager.sharedIdentityManager = identityManager
        MessageManager.sharedMeshManager = meshManager

        // 🔧 FIX (1.0.10 v2): Acquire the background task SYNCHRONOUSLY here, BEFORE
        // we hop into Task.detached. The previous version called beginBackgroundTask
        // inside the detached task — but the dispatch from sendMessage to the
        // detached task's first executable line takes long enough that a user
        // swiping up immediately after Send would have iOS start suspending the
        // app before the bg-task token was even acquired. Holding it from the
        // synchronous send-tap moment guarantees iOS gives us ~30 s of foreground-
        // equivalent runtime regardless of how fast the user backgrounds.
        //
        // The holder is a tiny class with a lock so the expiration handler (which
        // iOS invokes synchronously when our time runs out) and the post-pipeline
        // defer can both call end() without double-ending the task — iOS terminates
        // the app if the expiration handler returns without calling endBackgroundTask.
        let bgHolder = SendBackgroundTaskHolder()
        bgHolder.id = UIApplication.shared.beginBackgroundTask(withName: "OshiSend") { [bgHolder] in
            OshiLog.messaging.info("⚠️ OshiSend background task expiring — ending now to avoid termination")
            bgHolder.end()
        }
        OshiLog.messaging.info("🔋 OshiSend background task acquired: id=\(bgHolder.id.rawValue)")

        // 🎟️ Fix multi-media upload order: grab the upload ticket + a send timestamp
        // SYNCHRONOUSLY here, while we're still in the caller's ordered send loop.
        // The detached tasks below race, so deciding order inside them uploaded the
        // bottom item first. The ticket (top→bottom) drives OrderedUploadGate, and
        // the timestamp keeps the bubbles in the same visible order.
        let isMediaSend = mediaAttachment != nil
        let uploadTicket: UInt64 = isMediaSend ? MessageManager.nextMediaUploadTicket() : 0
        // Was `Date()`. The parts of a split are issued in one synchronous loop,
        // so two of them can land on the same millisecond — and the wire only
        // carries milliseconds (`V2SyncModels.ts`), while the lists sort with
        // `sorted(by:)`, which Swift does NOT guarantee to be stable. Equal
        // timestamps therefore display in an arbitrary order. This allocator
        // hands out strictly increasing stamps ≥10 ms apart. See MessageSendClock.
        let sendTimestamp = MessageSendClock.next()

        Task.detached { [weak self, bgHolder] in
            defer {
                Task { @MainActor in
                    bgHolder.end()
                }
            }

            guard let self = self else { return }

            // 📸 Check if this is a new contact (first message)
            let isFirstMessage = await MainActor.run {
                !self.conversations.contains { $0.participantPublicKey == recipientAddress }
            }

            // 📸 If first message, send profile update to new contact
            if isFirstMessage {
                OshiLog.messaging.info("📸 First message to new contact - sending profile...")
                await self.sendProfileToContact(
                    recipientAddress,
                    identityManager: identityManager,
                    initiatedByUser: true
                )
            }

            // 🔒 SAFETY NET: insert a local "pending" bubble BEFORE we try to encrypt.
            // If encrypt or transport fails, the message stays visible with a failure
            // status instead of disappearing entirely (regression after key restore on
            // a new device, where the per-peer ratchet state isn't migrated).
            // __REVEAL_OUTBOX_2026_08_22__ Un identifiant imposé quand une
            // révélation en dépend; sinon, comme toujours, un UUID neuf.
            let placeholderId = forcedMessageId ?? UUID().uuidString
            var pendingMessage = SecureMessage(
                id: placeholderId,
                senderAddress: identityManager.publicKey,
                recipientAddress: recipientAddress,
                encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: identityManager.publicKey, timestamp: sendTimestamp),
                timestamp: sendTimestamp,
                isRead: false,
                deliveryStatus: .pending,
                senderPublicKey: identityManager.publicKey,
                recipientPublicKey: recipientAddress,
                plaintextContent: mediaAttachment != nil ? "" : content,
                mediaAttachment: mediaAttachment,
                deliveryMethod: .pending,
                mediaType: mediaType,
                originalMediaData: mediaAttachment
            )
            pendingMessage.applyVideoNote(videoNote)  // __VIDEO_NOTE_2026_09_24__ round from the first frame
            if !skipLocalMessage {
                await MainActor.run { [pendingMessage] in
                    guard let manager = MessageManager.shared else { return }
                    manager.addMessage(pendingMessage)
                }
            }

            // 🔧 Bound peak memory on multi-media sends. The pending bubble above
            // gives the user immediate feedback for every attachment, but the
            // base64 + AES-GCM + IPFS-envelope work below transiently allocates
            // several× the file size. Three concurrent video sends overran the
            // Jetsam limit and crashed the app, so only one media upload runs that
            // work at a time. Text sends are unaffected (no gate). The ticket makes
            // the gate serve uploads in the user's top→bottom order.
            if isMediaSend { await MessageManager.mediaUploadGate.wait(ticket: uploadTicket) }
            defer { if isMediaSend { Task { await MessageManager.mediaUploadGate.signal() } } }

            // 🔧 DURABILITY FIX: enqueue for retry BEFORE any network work, not only
            // in the failure `catch`. A send killed by suspension (or a jetsam) never
            // reaches that catch, so it left nothing behind to resume and the bubble
            // stayed stuck. The queue is keyed by messageId and `addToRetryQueue`
            // dedupes, so this is idempotent; every success path calls
            // `removeFromRetryQueue`. Media bytes are deliberately NOT passed —
            // `retrySendViaCloud` re-reads the live SecureMessage, and RetryableMessage
            // no longer persists them.
            if !skipLocalMessage {
                await MainActor.run {
                    MessageManager.shared?.addToRetryQueue(
                        messageId: placeholderId,
                        content: content,
                        recipientKey: recipientAddress,
                        mediaType: mediaType,
                        mediaFileName: mediaFileName
                    )
                }
            }

            do {
                let recipientPublicKey = recipientAddress

                OshiLog.messaging.info("📤 [SEND] Preparing message to \(recipientPublicKey.prefix(10))...")

                let sharedSecret = try identityManager.computeSharedSecret(with: recipientPublicKey)

                OshiLog.messaging.info("🔑 [SENDER] Preparing session with recipient \(recipientPublicKey.prefix(8))...")

                let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                    with: recipientPublicKey,
                    ourPublicKey: identityManager.publicKey,
                    sharedSecret: sharedSecret
                )
                
                let messageContent: String
                let finalMediaAttachment: Data?
                let finalMediaType: MediaManager.MediaType?
                let finalMediaFileName: String?
                
                if let mediaData = mediaAttachment, let type = mediaType {
                    // TODO(wire-format): this is base64 layer 1 of SIX. The same
                    // bytes are re-encoded by the ratchet JSONEncoder (Data →
                    // .base64), DoubleRatchet.swift's envelope encode,
                    // IdentityManager's wrap (twice), and uploadToIPFS's
                    // `encryptedContent.base64EncodedString()`. Measured
                    // amplification is 5.62×: a 1.8 MB photo leaves the device as
                    // 10.11 MB, which at a typical ~1.4 Mbps uplink IS the ~60 s
                    // send. It also means the real user-facing ceiling is ~13 MB,
                    // not the 75 MB the `maxSize` check below appears to allow.
                    // Collapsing the layers is a WIRE-FORMAT change (both peers must
                    // ship it) and is deliberately out of scope here; the source-side
                    // byte budget in MediaPickerView.encodeToShareBudget is the
                    // compatible half of the fix.
                    var mediaJson: [String: Any] = [
                        "type": "media",
                        "mediaType": type.rawValue,
                        "content": mediaData.base64EncodedString(),
                        "size": mediaData.count,
                        "isViewOnce": isViewOnce  // ✅ Include view once flag
                    ]
                    
                    // ✅ Include fileName for documents
                    if let fileName = mediaFileName {
                        mediaJson["fileName"] = fileName
                    }
                    // __VIDEO_NOTE_2026_09_24__ spec §2.C — absent on every other media.
                    if type == .video { videoNote?.apply(to: &mediaJson) }
                    
                    if let jsonData = try? JSONSerialization.data(withJSONObject: mediaJson),
                       let jsonString = String(data: jsonData, encoding: .utf8) {
                        messageContent = jsonString
                        finalMediaAttachment = mediaData
                        finalMediaType = type
                        finalMediaFileName = mediaFileName
                        OshiLog.messaging.info("📎 Embedding media in message (\(mediaData.count / 1000) KB)")
                        OshiLog.messaging.info("📎 Media JSON starts with: \(jsonString.prefix(50))...")
                    } else {
                        messageContent = content
                        finalMediaAttachment = nil
                        finalMediaType = nil
                        finalMediaFileName = nil
                        OshiLog.messaging.info("⚠️ Failed to encode media, sending as text")
                    }
                } else {
                    messageContent = content
                    finalMediaAttachment = nil
                    finalMediaType = nil
                    finalMediaFileName = nil
                }
                
                OshiLog.messaging.info("🔐 Encrypting message (\(messageContent.count) chars)")
                
                let ratchetMessage = try session.encrypt(messageContent)
                let ratchetData = try JSONEncoder().encode(ratchetMessage)
                let encryptedMessage = try identityManager.wrapRatchetMessage(ratchetData, for: recipientPublicKey)
                
                // Extract replyToId from the reply-prefix JSON so the field is populated for scroll-to-original + Android parity.
                let extractedReplyToId: String? = MessageWithReply.fromMessageString(content)?.replyTo?.originalMessageId

                // ✅ Reuse the placeholder bubble we inserted before the encrypt block
                // so the user keeps a single message row through pending → sending.
                var message = SecureMessage(
                    id: placeholderId,
                    senderAddress: identityManager.publicKey,
                    recipientAddress: recipientPublicKey,
                    encryptedContent: encryptedMessage,
                    timestamp: Date(),
                    isRead: false,
                    deliveryStatus: .pending,
                    senderPublicKey: identityManager.publicKey,
                    recipientPublicKey: recipientPublicKey,
                    plaintextContent: finalMediaAttachment != nil ? "" : content,
                    mediaAttachment: finalMediaAttachment,
                    deliveryMethod: .pending,
                    mediaType: finalMediaType,
                    originalMediaData: finalMediaAttachment,
                    replyToId: extractedReplyToId
                )
                message.mediaFileName = finalMediaFileName  // ✅ Add fileName
                message.isViewOnce = isViewOnce             // ✅ Add view once flag
                if finalMediaType == .video { message.applyVideoNote(videoNote) }  // __VIDEO_NOTE_2026_09_24__

                let messageId = message.id

                // 📍 Skip local message creation for live location updates (already updated via updateOwnLocationMessage)
                if !skipLocalMessage {
                    await MainActor.run { [message] in
                        guard let manager = MessageManager.shared else { return }
                        if let idx = manager.messages.firstIndex(where: { $0.id == placeholderId }) {
                            manager.messages[idx] = message
                            manager.saveMessages()
                        } else {
                            manager.addMessage(message)
                        }
                        // Sync sent message to web session
                        WebSessionManager.shared.notifyNewMessage(message)
                    }
                } else {
                    OshiLog.messaging.info("📍 Skipping local message creation (live location update)")
                }
                
                // 🔧 IMPROVED: Smart transport selection based on mesh availability
                // Check BOTH MultipeerConnectivity AND CrossPlatformMesh (iOS↔Android)
                var crossPlatformReachable = CrossPlatformMesh.shared.isPeerLiveConnected(recipientPublicKey)

                // If the peer was discovered via BLE/Bonjour but the TCP/identity handshake hasn't
                // landed yet, give it up to ~800 ms to complete before falling back to IPFS.
                // Without this wait the very first message after discovery races the handshake and
                // always loses, forcing an unnecessary IPFS round-trip.
                // 🔧 SPEED FIX: text only. On a media send this 800 ms handshake wait
                // is pure latency in front of an upload that is already the slow part,
                // and when it does succeed the mesh branch it unlocks re-encodes the
                // whole image inside a task that `meshTask.cancel()` cannot actually
                // stop (`meshManager.sendMessage` is synchronous). Media with a
                // live-connected peer still takes the mesh route below — this only
                // skips waiting for a handshake that hasn't landed yet.
                if mediaAttachment == nil,
                   !crossPlatformReachable && CrossPlatformMesh.shared.isPeerDiscovered(recipientPublicKey) {
                    for _ in 0..<4 {
                        try? await Task.sleep(nanoseconds: 200_000_000)
                        if CrossPlatformMesh.shared.isPeerLiveConnected(recipientPublicKey) {
                            crossPlatformReachable = true
                            break
                        }
                    }
                }

                // 🔧 FIX (1.0.10 v6): Per-recipient mesh reachability check (was a
                // global "any peer connected" check that mis-routed cross-network sends
                // through mesh). The mesh broadcast doesn't fail when the recipient is
                // unreachable — it just floods the local mesh hoping someone knows them
                // — so the bubble would show "delivered via mesh" while the message
                // never actually reached the intended phone. Now we route via mesh only
                // when THIS specific recipient pubkey is currently a connected peer
                // (either via CrossPlatformMesh or via MultipeerConnectivity's
                // pubkey→peerID mapping). Everything else goes straight to IPFS.
                let meshAvailableNow: Bool
                if crossPlatformReachable {
                    meshAvailableNow = true
                } else if let mesh = MessageManager.sharedMeshManager,
                          mesh.isPeerConnected(publicKey: recipientPublicKey) {
                    meshAvailableNow = true
                } else {
                    meshAvailableNow = false
                }

                OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                OshiLog.messaging.info("📤 ROUTING DECISION:")
                OshiLog.messaging.info("   Mesh available (fresh check): \(meshAvailableNow)")
                OshiLog.messaging.info("   CrossPlatform reachable: \(crossPlatformReachable)")
                if let mesh = MessageManager.sharedMeshManager {
                    OshiLog.messaging.info("   MultipeerConnectivity peers: \(mesh.connectedPeers.count)")
                }
                OshiLog.messaging.info("   CrossPlatform peers: \(CrossPlatformMesh.shared.connectedPeers.count)")

                // 🕵️ Check if covert channel mode is enabled
                let covertEnabled = UserDefaults.standard.bool(forKey: "covertChannelEnabled")

                // __WIFI_AWARE_2026_11_25__ A paired contact with a live,
                // identity-verified Wi-Fi Aware link gets the envelope (photos and
                // files included) over that link first. `false` — not paired, not
                // nearby, no ACK in time, or no Wi-Fi Aware on this build/device —
                // returns instantly in the common case and leaves the chain below
                // exactly as it was. See WiFiAwareCore.swift.
                let wifiAwareDelivered = await self.trySendViaWiFiAware(
                    message: message, messageId: messageId, recipientPublicKey: recipientPublicKey)

                if wifiAwareDelivered {
                    OshiLog.messaging.info("   📶 Route: WI-FI AWARE (paired contact, acknowledged)")
                } else if covertEnabled && !meshAvailableNow {
                    // Covert channel mode: route through disguised web traffic
                    OshiLog.messaging.info("   🕵️ Route: COVERT CHANNEL (steganographic transport)")
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    
                    do {
                        try await self.sendViaCovertChannel(
                            message: message,
                            messageId: messageId,
                            recipientPublicKey: recipientPublicKey
                        )
                    } catch {
                        OshiLog.messaging.info("⚠️ Covert channel failed: \(error), falling back to IPFS...")
                        try await self.fallbackToIPFS(message: message, messageId: messageId, recipientPublicKey: recipientPublicKey)
                    }
                    
                } else if meshAvailableNow {
                    // Try mesh first since it's available
                    OshiLog.messaging.info("   ✅ Route: MESH (trying mesh first with IPFS fallback)")
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    
                    let meshTask = Task {
                        try meshManager.sendMessage(message, to: recipientPublicKey)
                    }
                    
                    do {
                        try await self.withTimeout(seconds: 3) {
                            try await meshTask.value
                        }
                        
                        await MainActor.run {
                            guard let manager = MessageManager.shared,
                                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
                            
                            manager.messages[index].deliveryMethod = .mesh
                            manager.messages[index].deliveryStatus = .sent
                            manager.saveMessages()
                            manager.removeFromRetryQueue(messageId: messageId)
                            OshiLog.messaging.info("✅ Sent via mesh network (Direct)")
                            // ⭐️ The mesh carried it — no server, no carrier.
                            // This is OSHI's differentiator and the strongest
                            // "it actually works" moment we can observe.
                            RateAppManager.shared.recordMeshDeliverySucceeded()
                        }

                        // __SERVER_IS_NOT_A_FALLBACK_2026_08_15__ A successful mesh
                        // send used to END the story, and that was the whole bug.
                        //
                        // The server path lives inside `fallbackToIPFS`, whose own
                        // comment claims it "covers every caller (direct, mesh-timeout,
                        // covert fallback, retries)" — every caller except this one.
                        // So two phones sitting next to each other always won the mesh
                        // race, the relay was never told anything, and the message
                        // existed only in the two local stores. MEASURED on the user's
                        // pair: 0 `DIAG_V2` lines on EITHER device across a whole test
                        // session, i.e. the server gate was never once consulted; and
                        // the same send between two SIMULATORS — which have no mesh —
                        // arrived, decrypted and rendered with an "Internet" badge.
                        // The transport was never broken; nothing ever reached it.
                        //
                        // `v2TrySend` alone, deliberately, NOT the whole
                        // `fallbackToIPFS` chain: the peer is demonstrably in radio
                        // range, so the LoRa branches above it would spend 1-2 s/frame
                        // re-sending what mesh just delivered. The receiver de-dupes
                        // on message id either way.
                        //
                        // This used to claim the bubble keeps saying "mesh" because
                        // the call touches neither `deliveryMethod` nor
                        // `deliveryStatus`. It does: `v2TrySend` stamps `.ipfs` on
                        // success (MessageManager+V2.swift). Watching a bubble flip
                        // from "Mesh" to "Internet" one second later is exactly how
                        // the offline-only leak was found on 2026-10-23 — so the
                        // badge telling the truth is worth more than the tidier
                        // story, and the sentence is corrected rather than removed.
                        //
                        // Result ignored on purpose: mesh already delivered. A relay
                        // that is down must not turn a delivered message into a failed
                        // one.
                        // __MSG_DUP_COST_2026_10_09__ ...but not on a bearer
                        // the user pays for.
                        //
                        // This line ran unconditionally, so every message mesh
                        // had ALREADY delivered was also posted to the relay —
                        // on cellular, in Low Data Mode, on a constrained
                        // hotspot, every time. The call path has refused to do
                        // that since 2026-07-27 and the message path simply had
                        // no equivalent check; `shouldPreferMesh()` exists in
                        // `NetworkReachabilityManager` and has never had a
                        // caller.
                        //
                        // On Wi-Fi the duplicate is still sent, and should be:
                        // mesh has no app-level ack, so the relay copy is the
                        // store-and-forward insurance for a hop that silently
                        // did not arrive. What changes is that the insurance is
                        // no longer bought with the user's data allowance.
                        let net = NetworkReachabilityManager.shared
                        if MessageTextSplitter.duplicationAffordable(cellular: net.isCellular,
                                                                    expensive: net.isExpensive,
                                                                    constrained: net.isConstrained) {
                            _ = await self.v2TrySend(message: message,
                                                     messageId: messageId,
                                                     recipientPublicKey: recipientPublicKey)
                        } else {
                            OshiLog.messaging.info("💸 Mesh delivered \(messageId.prefix(8))… — skipping the relay copy on a metered path")
                        }

                    } catch is TimeoutError {
                        meshTask.cancel()
                        OshiLog.messaging.info("⏱️ Mesh timeout (3s), trying IPFS fallback...")
                        try await self.fallbackToIPFS(message: message, messageId: messageId, recipientPublicKey: recipientPublicKey)
                        
                    } catch {
                        OshiLog.messaging.info("⚠️ Mesh send failed: \(error)")
                        OshiLog.messaging.info("   🔄 Falling back to IPFS...")
                        try await self.fallbackToIPFS(message: message, messageId: messageId, recipientPublicKey: recipientPublicKey)
                    }
                } else {
                    // Mesh not available, use IPFS directly
                    OshiLog.messaging.info("   ℹ️ Route: IPFS (mesh not available)")
                    OshiLog.messaging.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    try await self.fallbackToIPFS(message: message, messageId: messageId, recipientPublicKey: recipientPublicKey)
                }
                
                // Track successful message send for the App Store review flow.
                // RateAppManager owns the HIG engagement heuristic: it only ARMS a
                // milestone here (never prompts mid-conversation). The legacy
                // AppReviewManager.trackMessageSent() auto-fired the native prompt at
                // message #10 — intentionally removed to avoid a mid-conversation ask.
                Task { @MainActor in RateAppManager.shared.recordMessageSent() }

                // 🔧 MULTI-DEVICE: Push the new sent message to the sync slot now
                // instead of waiting up to 30s for the periodic timer. Without this
                // a message sent from iPhone-A only appears on iPhone-B after the
                // next polling tick — long enough that users perceive sent messages
                // as "not syncing".
                if !skipLocalMessage {
                    // Debounced: firing this immediately put a second, overlapping
                    // upload on the same uplink as the media that was still
                    // transferring. See scheduleUploadMessagesForSync.
                    DispatchQueue.main.async {
                        MultiDeviceSyncManager.shared.scheduleUploadMessagesForSync()
                    }
                }

            } catch is TransportPolicyError {
                // __NEVER_OVER_THE_INTERNET_2026_10_20__ NOT a failure, and it
                // must not be shown as one.
                //
                // Without this branch the `catch` below turns the refusal into
                // `deliveryStatus = .failed` — a red message — and files
                // friction against the rating prompt, for a message the app
                // deliberately held back exactly as the user asked. It also
                // hunts for a "stale ratchet" that is not stale and resets a
                // perfectly good session.
                //
                // `fallbackToIPFS` already set the row to `.pending` and cleared
                // the upload status. There is nothing to do here but say so.
                OshiLog.messaging.info("⏸ Held for an offline route — internet sending is off")

            } catch {
                OshiLog.messaging.info("❌ Send error: \(error.localizedDescription)")
                let errString = String(describing: error).lowercased()
                let looksLikeRatchetFailure = errString.contains("ratchet")
                    || errString.contains("decrypt")
                    || errString.contains("session")
                    || errString.contains("authentication")
                    || errString.contains("hkdf")
                    || errString.contains("crypto")
                await MainActor.run { [placeholderId] in
                    guard let manager = MessageManager.shared else { return }
                    if let index = manager.messages.firstIndex(where: { $0.id == placeholderId }) {
                        manager.messages[index].deliveryStatus = .failed
                        manager.saveMessages()
                    }
                    // 🚧 The user just watched a message fail (possibly a ratchet
                    // /decryption problem — see below). Never ask for a rating
                    // in the minutes that follow.
                    RateAppManager.shared.recordFriction(reason: "send-failed")
                }
                // 🔧 Auto-recovery: if encrypt path threw, the local ratchet session is
                // probably stale (typically after a key restore on a new device — peer
                // session state didn't migrate). Reset it so the user can resend.
                if looksLikeRatchetFailure {
                    OshiLog.messaging.info("🔁 Resetting stale ratchet session for \(recipientAddress.prefix(8))…")
                    DoubleRatchetSessionManager.shared.resetSession(
                        with: recipientAddress,
                        ourPublicKey: identityManager.publicKey
                    )
                }
            }
        }
    }
    
    private func setupMessageListener() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleIncomingMessage(_:)),
            name: .didReceiveMessage,
            object: nil
        )

        // 📞 __CALL_SUMMARY_LOCAL_2026_08_22__ Le résumé d'un appel décroché
        // s'écrit ICI, et nulle part ailleurs. Voir handleCallEndedWriteSummary.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCallEndedWriteSummary(_:)),
            name: NSNotification.Name("CallEnded"),
            object: nil
        )
    }

    /// 📞 __CALL_SUMMARY_LOCAL_2026_08_22__ « À la fin d'un appel j'ai DEUX
    /// messages: appel entrant 2:10 ET appel sortant 2:10. »
    ///
    /// CE QUI SE PASSAIT
    /// `VoiceCallManager` poste `CallEnded` sur les DEUX appareils, chacun avec
    /// SON `wasOutgoing`. `ChatView.handleCallEnded` s'exécutait donc des deux
    /// côtés, fabriquait le résumé de son point de vue et l'ENVOYAIT au pair
    /// avec `sendMessage` — qui l'écrit aussi localement. Chacun gardait le sien
    /// PLUS celui d'en face, et celui d'en face portait la direction de son
    /// expéditeur: un appel reçu s'affichait « appel sortant ».
    ///
    /// CE QUI CHANGE
    /// Plus personne n'ÉMET de résumé d'appel décroché. Chaque appareil écrit le
    /// sien, localement, dans son sens. Il n'y a plus rien à dédoublonner: le
    /// doublon n'est plus produit. Le filtre de `receiveMessage` reste — un pair
    /// resté sur une version antérieure enverra le sien, et il faut savoir
    /// l'ignorer.
    ///
    /// ⚠️ ET IL FALLAIT DÉPLACER L'ÉCRITURE, pas seulement couper l'envoi.
    /// `handleCallEnded` vivait dans `ChatView`: il ne s'exécutait QUE si la
    /// conversation du pair était à l'écran. Un appel pris depuis l'écran
    /// d'accueil, depuis CallKit ou depuis une AUTRE conversation ne produisait
    /// aucun résumé local — et depuis que le filtre jette la copie du pair,
    /// l'appel n'aurait laissé AUCUNE trace. `MessageManager` est un singleton
    /// vivant du lancement à la fermeture: ici, l'appel est toujours consigné.
    ///
    /// Les appels MANQUÉS ne passent pas par ici. Ils gardent leur envoi au pair
    /// (`ChatView.handleMissedCall`): quand l'app d'en face était fermée, le
    /// message de l'appelant est la seule trace que l'appel a existé.
    @objc private func handleCallEndedWriteSummary(_ notification: Notification) {
        guard let info = notification.userInfo,
              let peerKey = info["peerPublicKey"] as? String,
              !peerKey.isEmpty,
              let duration = info["duration"] as? TimeInterval,
              duration > 0,
              let wasOutgoing = info["wasOutgoing"] as? Bool,
              let myKey = MessageManager.sharedIdentityManager?.publicKey,
              !myKey.isEmpty
        else { return }

        let peer = MessageManager.canonicalIdentity(peerKey)
        // __BLOCKED_NO_CALL_TRACE_2026_09_24__ owner decision: no call line with a blocked
        // contact in OSHI's own history (a call can end after the user blocked mid-call).
        guard !BlockedContactsManager.shared.isBlocked(peer) else { return }
        let content = OSHIControlPayload.answeredCallSummary(
            wasOutgoing: wasOutgoing,
            duration: MessageManager.callDurationText(duration)
        )

        // `CallEnded` peut être posté deux fois pour un même appel (fin locale +
        // signal du pair qui arrive juste après). `addMessage` ne dédoublonne que
        // par identifiant, et chaque écriture en fabrique un neuf: la garde doit
        // être ici. Deux résumés identiques pour le même pair à moins de deux
        // minutes d'écart, c'est le même appel — un vrai rappel dans la foulée
        // n'a pas la même durée à la seconde près.
        let now = Date()
        if messages.contains(where: { msg in
            msg.plaintextContent == content
                && (msg.senderPublicKey == peer || msg.recipientPublicKey == peer)
                && abs(msg.timestamp.timeIntervalSince(now)) < 120
        }) {
            OshiLog.messaging.info("📞 Résumé d'appel déjà consigné pour ce pair — pas de second")
            return
        }

        let summary = SecureMessage(
            id: UUID().uuidString,
            senderAddress: myKey,
            recipientAddress: peer,
            encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: myKey, timestamp: now),
            timestamp: now,
            isRead: true,
            deliveryStatus: .sent,
            senderPublicKey: myKey,
            recipientPublicKey: peer,
            plaintextContent: content,
            // Rien n'est envoyé: `deliveryMethod` reste nil plutôt que de
            // prétendre un chemin de livraison qui n'a pas été emprunté.
            deliveryMethod: nil
        )
        addMessage(summary)

        // Le résumé ne part plus vers le pair, mais il doit toujours atteindre
        // les AUTRES appareils de l'utilisateur. `uploadMessagesForSync` téléverse
        // `messages` en bloc et `plaintextContent` fait partie de l'encodage de
        // `SecureMessage`, donc le résumé traverse intact — la synchro périodique
        // l'aurait emporté de toute façon, ceci ne fait que la devancer, comme le
        // fait déjà le chemin d'envoi.
        MultiDeviceSyncManager.shared.scheduleUploadMessagesForSync()

        OshiLog.messaging.info("📞 Résumé d'appel écrit localement (sortant=\(wasOutgoing)) — rien n'est envoyé au pair")
    }

    // MARK: - __REVEAL_OUTBOX_2026_08_22__ Contexte de révélation en vol

    /// Planification associée à un envoi EN COURS, le temps qu'il atteigne le
    /// transport.
    ///
    /// Un registre plutôt qu'un paramètre de plus, et c'est un choix, pas une
    /// facilité: `fallbackToIPFS` est appelé depuis plusieurs chemins — envoi
    /// direct, expiration mesh, repli discret, ET la file de reprise. Un
    /// paramètre se perdrait à la première reprise, et la couche repartirait sans
    /// sa planification: elle arriverait chez le destinataire comme une photo
    /// ordinaire, en clair, au milieu d'une révélation.
    ///
    /// La clé est l'identifiant du message, qui est stable à travers les
    /// reprises. L'entrée est retirée dès qu'elle a servi.
    private var revealContexts: [String: RevealSendContext] = [:]

    func registerRevealContext(_ context: RevealSendContext, forMessageId id: String) {
        revealContexts[id] = context
    }

    /// Lit ET consomme. Un contexte qui traînerait après l'envoi ferait porter la
    /// planification par le message suivant qui réutiliserait l'identifiant.
    func revealContext(forMessageId id: String) -> RevealSendContext? {
        revealContexts.removeValue(forKey: id)
    }

    /// mm:ss, ou h:mm:ss au-delà de l'heure.
    ///
    /// Remonté ici depuis `ChatView.formatCallDuration` pour que le format et son
    /// producteur ne se séparent plus. Une différence assumée au passage:
    /// l'original faisait `Int(duration) / 60` et ne retenait JAMAIS les heures,
    /// donc un appel de deux heures s'affichait « 125:30 ».
    ///
    /// `Int(duration)` PIÈGE sur un `TimeInterval` non fini — et la durée vient
    /// d'une soustraction de dates, pas d'une constante. D'où le garde-fou.
    static func callDurationText(_ duration: TimeInterval) -> String {
        guard duration.isFinite else { return "0:00" }
        let total = max(0, Int(duration.rounded(.towardZero)))
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
    
    @objc private func handleIncomingMessage(_ notification: Notification) {
        guard let message = notification.object as? SecureMessage else { return }
        receiveMessage(message)
    }
    
    // MARK: - Receive Message
    
    /// - Parameter loraNodeNum: the Meshtastic node this envelope came off,
    ///   when it came off a radio at all. Used for one thing only: binding that
    ///   node to the sender's public key once the envelope has decrypted, so a
    ///   later RAW Meshtastic text from the same node reaches this conversation
    ///   instead of opening a "lora!<nodehex>" one. Nil on every other path.
    /// - Parameter suppressNotification: livre le message SANS programmer de
    ///   notification locale.
    ///
    ///   __RESEND_ENVELOPE_2026_08_28__ Un seul appelant s'en sert: le RENVOI
    ///   d'un message que le destinataire n'avait pas pu ouvrir. Il a deja ete
    ///   notifie a l'epoque — c'est meme cette notification-la, suivie d'aucun
    ///   message, qui a fait remonter tout le probleme. Sans ce parametre, un
    ///   rattrapage de 32 messages produirait 32 bannieres d'un coup.
    ///
    ///   Le defaut est `false`: aucun autre chemin ne change de comportement.
    func receiveMessage(_ message: SecureMessage, loraNodeNum: UInt32? = nil,
                        suppressNotification: Bool = false) {
        // __MAIN_THREAD_ONLY_2026_09_06__ Everything below mutates @Published
        // state (`messages`, `conversations`). Doing that off the main thread is
        // the deadlock in the 2026-09-06 crash on build 113: a cooperative thread
        // in updateConversations() held Combine's publisher lock while SwiftUI,
        // notified on that thread, waited for the main thread — which was itself
        // blocked on the same lock inside markConversationAsRead. Watchdog:
        // 0x8BADF00D "stuck (deadlock)", app CPU 0.04 s. Hop instead of holding.
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [self] in
                receiveMessage(message, loraNodeNum: loraNodeNum, suppressNotification: suppressNotification)
            }
            return
        }
        // __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1: strip the invisible
        // "restricted network" suffix at ingestion (every inbound path funnels here),
        // so it never reaches the store, a notification, a quote, copy or search;
        // remember the flag for the bubble's chip.
        let message: SecureMessage = {
            guard let text = message.plaintextContent else { return message }
            let (clean, flagged) = RestrictedNetworkHint.ingest(text)
            guard flagged else { return message }
            var m = message
            m.plaintextContent = clean
            RestrictedNetworkHintStore.insert(m.id)
            return m
        }()
        // 🔧 DIAG (2026-06-02): include first 24 chars of plaintext (sanitized
        // to ASCII letters so it survives the prefix matchers) + mediaType, so
        // when the next checkpoint mark below is missing we know which handler
        // hung. Special-char preservation matters because the prefix matchers
        // use emoji markers like "📞CALL_SIGNAL📞".
        let _preview: String = {
            guard let p = message.plaintextContent else { return "<nil>" }
            return String(p.prefix(24)).replacingOccurrences(of: "\n", with: " ")
        }()
        CallFileLogger.shared.log("DIAG_MSG_RX_ENTRY | id=\(message.id.prefix(8)) | from=\(message.senderPublicKey.prefix(12)) | hasPlaintext=\(message.plaintextContent != nil) | encContent=\(!message.encryptedContent.ciphertext.isEmpty) | mediaType=\(message.mediaType?.rawValue ?? "nil") | preview=\(_preview)")
        OshiLog.messaging.info("📨 Processing received message...")
        OshiLog.messaging.info("   Message ID: \(message.id)")
        OshiLog.messaging.info("   Delivery method from source: \(message.deliveryMethod?.rawValue ?? "nil")")
        OshiLog.messaging.info("   Current message count: \(messages.count)")
        
        // ⚠️ Check if sender is blocked
        if BlockedContactsManager.shared.isBlocked(message.senderPublicKey) {
            OshiLog.messaging.info("🚫 Message rejected: Sender is blocked")
            OshiLog.messaging.info("   Blocked sender: \(message.senderPublicKey.prefix(10))...")
            noticeBlockedSender(message.senderPublicKey, plaintext: message.plaintextContent)
            return
        }

        // 🪦 Check if sender's key is stale (rotated identity).
        // After the user marks the previous key as old (manual or auto via
        // QR re-scan with same alias), messages signed by that key must be
        // silently dropped — the user has stated this key is no longer them.
        // Note: this also short-circuits the call-signal path below, so an
        // incoming call delivered as a plaintext signal-in-message can't ring.
        if StaleIdentityManager.shared.isStale(message.senderPublicKey) {
            OshiLog.messaging.info("🪦 Message rejected: Sender key is stale (rotated identity)")
            OshiLog.messaging.info("   Stale sender: \(message.senderPublicKey.prefix(10))...")
            return
        }

        // 📞 🔴 FIX (2026-08-21) — DEUX RÉSUMÉS POUR UN SEUL APPEL.
        //
        // Signalé par Hugo: à la fin d'un appel, le fil affiche « appel entrant
        // 2:10 » ET « appel sortant 2:10 ». Les deux sont vrais, et c'est le
        // problème.
        //
        // `VoiceCallManager` poste `CallEnded` sur LES DEUX appareils, chacun
        // avec SON `wasOutgoing`. `ChatView.handleCallEnded` s'exécute donc des
        // deux côtés, fabrique le résumé de son point de vue, et l'ENVOIE au pair
        // avec `sendMessage` — qui l'écrit aussi dans le fil de l'expéditeur.
        // Chaque appareil se retrouve avec le sien plus celui d'en face.
        //
        // Le commentaire d'origine dit l'intention: « Both parties should see the
        // call summary ». Elle est juste; la mise en œuvre l'atteint deux fois.
        //
        // Et le doublon n'est pas seulement redondant, il est FAUX: la direction
        // est celle de l'EXPÉDITEUR et n'est jamais retournée à la lecture. Un
        // appel que vous avez REÇU s'affiche donc chez vous comme « appel
        // sortant ». La clé de localisation voyage bien, la direction non.
        //
        // Un appel DÉCROCHÉ est forcément observé par les deux côtés, donc la
        // copie du pair n'apprend rien: on la jette, et chacun garde la sienne,
        // dans son sens.
        //
        // ⚠️ Uniquement les appels décrochés — reconnaissables au `|` qui sépare
        // la clé de la durée. Les « manqué », « refusé » et « sans réponse » ne
        // portent PAS de durée et doivent continuer à passer: quand l'app était
        // fermée, le message du pair est la seule trace que l'appel a existé.
        //
        // Filtrer ICI plutôt qu'à l'affichage: `receiveMessage` est l'entonnoir
        // unique (la v2 y passe aussi, cf. MessageManager+V2), et un message
        // jamais stocké ne peut pas ressurgir dans un aperçu de conversation, une
        // notification ou une recherche.
        if let plaintext = message.plaintextContent,
           OSHIControlPayload.isAnsweredCallSummary(plaintext) {
            OshiLog.messaging.info("📞 Résumé d'appel décroché reçu du pair — ignoré, le nôtre fait foi")
            return
        }

        // 📞 Check for call signal messages first
        if let plaintext = message.plaintextContent, plaintext.starts(with: "📞CALL_SIGNAL📞") {
            OshiLog.messaging.info("📞 Detected call signal message!")
            handleCallSignalMessage(plaintext, from: message.senderPublicKey)
            return  // Don't store call signals as regular messages
        }
        
        guard !messages.contains(where: { $0.id == message.id }) else {
            OshiLog.messaging.info("⏭️ Duplicate message ignored (ID already exists)")
            return
        }
        
        OshiLog.messaging.info("✅ New message, adding to storage...")
        
        var receivedMessage = message
        receivedMessage.deliveryStatus = .delivered
        receivedMessage.isRead = false

        // __IDENTITY_CANONICAL_2026_08_15__ Stamp the canonical spelling on the
        // copy we are about to STORE, once, here — this is the single funnel every
        // transport reaches (mesh, LoRa, legacy IPFS, v2 relay), and the v2 and VPS
        // paths hand us base64url keys. Everything downstream compares with `==`:
        // the conversation filter, `updateConversations`, the notification's
        // conversationId. Left raw, the message lands in the array, fires its
        // notification, and never appears in the thread.
        receivedMessage.senderPublicKey = MessageManager.canonicalIdentity(receivedMessage.senderPublicKey)
        receivedMessage.recipientPublicKey = MessageManager.canonicalIdentity(receivedMessage.recipientPublicKey)
        
        // 🔧 FIX: Also check for .pending — sender sets deliveryMethod = .pending
        // which gets serialized and sent. On receiver side, we must override it
        // to reflect the actual delivery method used.
        // This default is LOAD-BEARING, and now that the transport is shown on
        // every bubble it has to be right rather than merely harmless.
        //
        // The invariant it rests on: every non-mesh path stamps itself before
        // getting here — the relay/v2 paths construct with `.ipfs`, and LoRa
        // stamps `.lora` in `MeshtasticManager.handleOshiChunk`. Only the three
        // Bluetooth/Wi-Fi mesh hand-offs in MeshNetworkManager arrive unstamped,
        // which is exactly what this branch is for.
        //
        // `meshWireSafe()` now strips `deliveryMethod` before transmission, so a
        // mesh message can no longer arrive still wearing the SENDER's label
        // (`.ipfs` from an earlier relay attempt) and be shown as Internet when
        // it in fact crossed a Bluetooth link.
        if receivedMessage.deliveryMethod == nil || receivedMessage.deliveryMethod == .pending {
            receivedMessage.deliveryMethod = .mesh
            OshiLog.messaging.info("   📡 Received via Mesh (direct)")
        } else {
            OshiLog.messaging.info("   ☁️ Received via \(receivedMessage.deliveryMethod!.rawValue)")
        }
        
        // 🔧 FIX: Check for special message types BEFORE decryption block
        // This handles messages that arrive already decrypted (e.g., via mesh direct)
        if let plaintext = receivedMessage.plaintextContent {
            // 📸 Check for profile update
            if plaintext.starts(with: "📸PROFILE_UPDATE📸") {
                OshiLog.messaging.info("📸 Detected profile update in plaintext message!")
                processIncomingProfileUpdate(from: message.senderPublicKey, content: plaintext)
                ContactPresenceManager.shared.updateLastSeen(publicKey: message.senderPublicKey)
                return  // Don't store as regular message
            }
            
            // 📸 Check for profile request
            if plaintext.starts(with: "📸PROFILE_REQUEST📸") {
                OshiLog.messaging.info("📸 Detected profile request in plaintext message from \(message.senderPublicKey.prefix(8))...")
                if let identityManager = MessageManager.sharedIdentityManager {
                    Task {
                        // __PROFILE_NEVER_REACHED_ANDROID_2026_11_03__ (Anatole)
                        // "la photo de profil choisie sur iOS ne s'affiche pas
                        // pour les utilisateurs Android."
                        //
                        // An EXPLICIT request is answered, where the unsolicited
                        // broadcast still is not. Android asks for a photo it
                        // lacks (`profileSweepTargets` → `requestProfileIfNeeded`),
                        // and this handler used to call through WITHOUT
                        // `initiatedByUser`, so the reply-gate below rejected it
                        // silently and the picture could never arrive until the
                        // iOS user happened to send a message first. Nothing
                        // else was wrong: the field names, the base64 and the
                        // sentinel all match across platforms, and Android
                        // consumes `📸PROFILE_UPDATE📸` on all four of its
                        // inbound paths.
                        //
                        // The trade, stated plainly because it is a real one:
                        // somebody who has messaged you but whom you have never
                        // replied to can now obtain your profile picture by
                        // asking for it. The broadcast path keeps the old rule,
                        // so your photo is still never pushed to them
                        // unsolicited — but it is no longer withheld when their
                        // app asks. Owner decision, 3 November 2026.
                        await self.sendProfileToContact(message.senderPublicKey,
                                                        identityManager: identityManager,
                                                        initiatedByUser: true)
                    }
                }
                return  // Don't store as regular message
            }
            
            // 📞 Check for call signal
            if plaintext.starts(with: "📞CALL_SIGNAL📞") {
                OshiLog.messaging.info("📞 Detected call signal in plaintext message!")
                handleCallSignalMessage(plaintext, from: message.senderPublicKey)
                return  // Don't store as regular message
            }
            
            // 🎨 Check for wallpaper update
            if plaintext.starts(with: "🎨WALLPAPER_UPDATE🎨") {
                OshiLog.messaging.info("🎨 Detected wallpaper update in plaintext message!")
                processIncomingWallpaperUpdate(from: message.senderPublicKey, content: plaintext)
                ContactPresenceManager.shared.updateLastSeen(publicKey: message.senderPublicKey)
                return  // Don't store as regular message
            }

            // ⌨️ Typing ping — 1:1. See `handleIncomingTypingPing`.
            if OSHIControlPayload.isTypingPing(plaintext) {
                handleIncomingTypingPing(plaintext, from: message.senderPublicKey)
                return  // Don't store as regular message
            }

            // 📖 Check for read receipt (from Android)
            if plaintext.starts(with: "📖READ_RECEIPT📖") {
                OshiLog.messaging.info("📖 Detected read receipt from \(message.senderPublicKey.prefix(8))...")
                handleIncomingReadReceipt(from: message.senderPublicKey)
                return  // Don't store as regular message
            }

            // 📑 __GROUP_RECEIPTS_2026_11_02__ (N8) Someone telling us what they
            // did with a GROUP message we sent. Silent: it is state about our own
            // message, never a message in its own right.
            if let receipt = GroupReceipts.parse(plaintext) {
                // __GROUP_RECEIPT_IOS_1043_2026_11_13__ it sent one, so it reads them.
                GroupReceipts.Readers.note(message.senderPublicKey)
                GroupReceipts.Store.shared.record(groupId: receipt.groupId,
                                                  msgId: receipt.msgId,
                                                  member: message.senderPublicKey,
                                                  kind: receipt.kind,
                                                  at: receipt.at)
                OshiLog.messaging.info("📑 group receipt \(receipt.kind.rawValue) for \(receipt.msgId.prefix(8))… from \(message.senderPublicKey.prefix(8))…")
                return  // Don't store as regular message
            }

            // 📬 Check for delivery receipt (from Android)
            if plaintext.starts(with: "📬DELIVERY_RECEIPT📬") {
                OshiLog.messaging.info("📬 Detected delivery receipt from \(message.senderPublicKey.prefix(8))...")
                handleIncomingDeliveryReceipt(from: message.senderPublicKey, content: plaintext)
                return  // Don't store as regular message
            }

            // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ A request to take this
            // conversation off the relay, or someone answering ours.
            //
            // The ANSWER is swallowed here and never stored: it is marked
            // `.rendered` like the request it answers, so letting it through
            // would print "this conversation is now offline only" a second time
            // in the thread. The REQUEST falls through and renders, because
            // seeing when a conversation was restricted, and by whom, is the
            // whole point of it being a visible message.
            //
            // Whether it is an answer is decided by parsing, which needs no
            // actor; only applying it does, so the branch is taken here and the
            // work is handed to the main actor after.
            if let offlineOnly = OfflineOnlyRequest.fromMessageString(plaintext) {
                let sender = message.senderPublicKey
                Task { @MainActor in
                    self.handleOfflineOnlyPayload(plaintext, from: sender)
                }
                if offlineOnly.isAck { return }
            }

            // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ A peer turned advanced chat
            // privacy on or off. Applied, then NOT swallowed: the message is the
            // centred notice row in the thread (quiet: no unread, no banner).
            if AdvancedPrivacyChange.isPayload(plaintext) {
                let sender = message.senderPublicKey
                Task { @MainActor in self.handleAdvancedPrivacyPayload(plaintext, from: sender) }
            }

            // 💓 __ALIVE_CHECK_IN_2026_11_04__ A peer's liveness event.
            //
            // NOT swallowed, in any case — unlike the offline-only answer above,
            // which is why this branch has no `return`. A check-in is a statement
            // rather than a request: there is nothing for the other side to
            // answer, so there is no echo to break, and the event has to reach
            // the thread. A confirmation the reader cannot find afterwards is one
            // they have to take on trust from a coloured dot.
            //
            // The confirming party is the sender of the decrypted envelope,
            // authenticated by the ratchet — never a field in the JSON, for the
            // reason a poll has no `voter` field. A check-in that named its own
            // author on the wire would let anyone confirm in anyone's name, which
            // for this feature means switching someone else's alarm off.
            if let alive = AliveCheckInEvent.fromMessageString(plaintext) {
                let sender = message.senderPublicKey
                Task { @MainActor in
                    let applied = AliveCheckInStore.shared.applyIncoming(alive, conversationId: sender)
                    guard applied.accepted else { return }
                    if applied.isRecovery || alive.kind == .disabled {
                        AliveCheckInStore.shared.clearOverdueNotice(sender)
                    }
                }
            }
            // 📊 Bulletin de sondage arrivé en clair (chemin Android).
            if PollVotePayload.isVoteMessage(plaintext) {
                if let vote = PollVotePayload.fromMessageString(plaintext) {
                    let voter = message.senderPublicKey
                    Task { @MainActor in PollStore.shared.record(vote: vote, from: voter) }
                }
                return
            }

            // 🔥 Check for reaction message (both iOS 🔥REACTION🔥 and Android 👍REACTION👍)
            if ReactionPayload.isReactionMessage(plaintext) {
                OshiLog.messaging.info("🔥 Detected reaction in plaintext message from \(message.senderPublicKey.prefix(8))...")
                if let payload = ReactionPayload.fromMessageString(plaintext) {
                    MessageReactionManager.shared.processIncomingReaction(payload)
                }
                return  // Don't store reactions as regular messages
            }

            // 🔧 Check for message action (delete/edit/pin sync)
            if MessageActionPayload.isActionMessage(plaintext) {
                OshiLog.messaging.info("🔧 Detected message action in plaintext from \(message.senderPublicKey.prefix(8))...")
                if let payload = MessageActionPayload.fromMessageString(plaintext) {
                    handleMessageAction(payload, fromSenderKey: message.senderPublicKey)
                }
                return  // Don't store action messages as regular messages
            }

            // 🔑 Check for key rotation message (from Android)
            if plaintext.starts(with: "🔑KEY_ROTATION🔑") {
                OshiLog.messaging.info("🔑 Detected key rotation in plaintext from \(message.senderPublicKey.prefix(8))...")
                return  // Don't store key rotation messages as regular messages
            }

            // 📢 Check for group update message (from Android)
            if plaintext.starts(with: "\u{1F4E2}GROUP_UPDATE\u{1F4E2}") {
                OshiLog.messaging.info("📢 Detected group update in plaintext from \(message.senderPublicKey.prefix(8))...")
                let jsonStr = String(plaintext.dropFirst("\u{1F4E2}GROUP_UPDATE\u{1F4E2}".count))
                if let jsonData = jsonStr.data(using: .utf8) {
                    Task { @MainActor in
                        NotificationCenter.default.post(
                            name: NSNotification.Name("GroupUpdateReceived"),
                            object: nil,
                            // __GROUP_UPDATE_AUTHORIZATION_2026_08_16__ This funnel
                            // KNOWS who sent it — the envelope was decrypted with
                            // this peer's ratchet. Passing it on is what lets the
                            // group authorizer tell an admin from anyone else.
                            userInfo: ["groupData": jsonData,
                                       "updatedBy": message.senderPublicKey]
                        )
                    }
                }
                return  // Don't store group updates as regular messages
            }
        }

        // 🔧 DIAG: reached past every special-prefix handler. If RX_ENTRY appears
        // but this mark doesn't, one of the prefix handlers above is hanging.
        CallFileLogger.shared.log("DIAG_MSG_RX_AFTER_PREFIX | id=\(receivedMessage.id.prefix(8))")

        if let identityManager = MessageManager.sharedIdentityManager {
            if receivedMessage.plaintextContent == nil || receivedMessage.plaintextContent?.starts(with: "eyJ") == true {
                CallFileLogger.shared.log("DIAG_MSG_DECRYPT_TRY | id=\(receivedMessage.id.prefix(8)) | from=\(receivedMessage.senderPublicKey.prefix(12)) | hasEnc=\(!receivedMessage.encryptedContent.ciphertext.isEmpty)")
                if let decrypted = decryptMessage(receivedMessage, using: identityManager) {
                    CallFileLogger.shared.log("DIAG_MSG_DECRYPT_OK | id=\(receivedMessage.id.prefix(8)) | chars=\(decrypted.count) | preview=\(decrypted.prefix(40))")
                    OshiLog.messaging.info("📋 Decrypted content (\(decrypted.count) chars)")

                    // 📡 THE PROOF — and it has to be the RATCHET's word, not
                    // `decryptMessage`'s. Returning non-nil is NOT proof: that
                    // function passes an envelope straight through, with no
                    // cryptography at all, when `mediaAttachment` and `mediaType`
                    // are both set, and both of those come off the wire. Public
                    // keys are shared openly by QR, so trusting a non-nil return
                    // let anyone with a radio in range bind their own node to a
                    // contact's identity and drop plaintext into that contact's
                    // encrypted conversation under their name.
                    //
                    // `lastRatchetVerifiedMessageId` is stamped only where the
                    // ratchet genuinely produced the plaintext, which means the
                    // envelope came from the holder of `senderPublicKey`'s private
                    // half. Compared against THIS message's id so an earlier
                    // success cannot vouch for a later envelope.
                    if let loraNodeNum, lastRatchetVerifiedMessageId == receivedMessage.id {
                        let outcome = LoRaNodeIdentityMap.shared.learn(
                            node: loraNodeNum, publicKey: receivedMessage.senderPublicKey)
                        OshiLog.messaging.info("📡 LoRa node \(String(format: "%08x", loraNodeNum)) → \(receivedMessage.senderPublicKey.prefix(8))…: \(String(describing: outcome))")
                    }


                    // 📞 Check for call signal in decrypted content
                    if decrypted.starts(with: "📞CALL_SIGNAL📞") {
                        OshiLog.messaging.info("📞 Detected call signal in decrypted message!")
                        handleCallSignalMessage(decrypted, from: message.senderPublicKey)
                        return  // Don't store call signals as regular messages
                    }

                    // 📸 Check for profile update in decrypted content
                    if decrypted.starts(with: "📸PROFILE_UPDATE📸") {
                        OshiLog.messaging.info("📸 Detected profile update in decrypted message!")
                        processIncomingProfileUpdate(from: message.senderPublicKey, content: decrypted)
                        // Also update last seen
                        ContactPresenceManager.shared.updateLastSeen(publicKey: message.senderPublicKey)
                        return  // Don't store profile updates as regular messages
                    }

                    // 📸 Check for profile request - someone asking for our profile
                    if decrypted.starts(with: "📸PROFILE_REQUEST📸") {
                        OshiLog.messaging.info("📸 Detected profile request from \(message.senderPublicKey.prefix(8))...")
                        // Send our profile in response
                        if let identityManager = MessageManager.sharedIdentityManager {
                            Task {
                                // __PROFILE_NEVER_REACHED_ANDROID_2026_11_03__ The
                                // SECOND request handler. Fixing only the first
                                // would have reproduced, on iOS, the exact bug
                                // just fixed on Android: a payload consumed on
                                // one inbound path and ignored on another.
                                await self.sendProfileToContact(message.senderPublicKey,
                                                                identityManager: identityManager,
                                                                initiatedByUser: true)
                            }
                        }
                        return  // Don't store profile requests as regular messages
                    }

                    // 🎨 Check for wallpaper update in decrypted content
                    if decrypted.starts(with: "🎨WALLPAPER_UPDATE🎨") {
                        OshiLog.messaging.info("🎨 Detected wallpaper update in decrypted message!")
                        processIncomingWallpaperUpdate(from: message.senderPublicKey, content: decrypted)
                        // Also update last seen
                        ContactPresenceManager.shared.updateLastSeen(publicKey: message.senderPublicKey)
                        return  // Don't store wallpaper updates as regular messages
                    }

                    // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ same as the plaintext
                    // branch: apply, then let the notice row be stored.
                    if AdvancedPrivacyChange.isPayload(decrypted) {
                        let sender = message.senderPublicKey
                        Task { @MainActor in self.handleAdvancedPrivacyPayload(decrypted, from: sender) }
                    }

                    // ⌨️ Typing ping in decrypted content — 1:1.
                    if OSHIControlPayload.isTypingPing(decrypted) {
                        handleIncomingTypingPing(decrypted, from: message.senderPublicKey)
                        return  // Don't store typing pings as regular messages
                    }

                    // 📖 Check for read receipt in decrypted content (from Android)
                    if decrypted.starts(with: "📖READ_RECEIPT📖") {
                        OshiLog.messaging.info("📖 Detected read receipt in decrypted message from \(message.senderPublicKey.prefix(8))...")
                        handleIncomingReadReceipt(from: message.senderPublicKey)
                        return  // Don't store read receipts as regular messages
                    }

                    // 📬 Check for delivery receipt in decrypted content (from Android)
                    if decrypted.starts(with: "📬DELIVERY_RECEIPT📬") {
                        OshiLog.messaging.info("📬 Detected delivery receipt in decrypted message from \(message.senderPublicKey.prefix(8))...")
                        handleIncomingDeliveryReceipt(from: message.senderPublicKey, content: decrypted)
                        return  // Don't store delivery receipts as regular messages
                    }

                    // 📊 Un bulletin de sondage. Muet au catalogue: il ne se
                    // range pas dans le fil et il ne réveille personne. Le
                    // VOTANT est l'expéditeur de l'enveloppe déchiffrée — pas
                    // un champ du JSON, qui laisserait voter pour autrui.
                    if PollVotePayload.isVoteMessage(decrypted) {
                        if let vote = PollVotePayload.fromMessageString(decrypted) {
                            let voter = message.senderPublicKey
                            Task { @MainActor in PollStore.shared.record(vote: vote, from: voter) }
                        }
                        return
                    }

                    // 🔥 Check for reaction message in decrypted content (both iOS 🔥REACTION🔥 and Android 👍REACTION👍)
                    if ReactionPayload.isReactionMessage(decrypted) {
                        OshiLog.messaging.info("🔥 Detected reaction in decrypted message from \(message.senderPublicKey.prefix(8))...")
                        if let payload = ReactionPayload.fromMessageString(decrypted) {
                            MessageReactionManager.shared.processIncomingReaction(payload)
                        }
                        return  // Don't store reactions as regular messages
                    }

                    // 🔧 Check for message action in decrypted content (delete/edit/pin sync)
                    if MessageActionPayload.isActionMessage(decrypted) {
                        OshiLog.messaging.info("🔧 Detected message action in decrypted message from \(message.senderPublicKey.prefix(8))...")
                        if let payload = MessageActionPayload.fromMessageString(decrypted) {
                            handleMessageAction(payload, fromSenderKey: message.senderPublicKey)
                        }
                        return  // Don't store action messages as regular messages
                    }

                    // 🔑 Check for key rotation message in decrypted content (from Android)
                    if decrypted.starts(with: "🔑KEY_ROTATION🔑") {
                        OshiLog.messaging.info("🔑 Detected key rotation in decrypted message from \(message.senderPublicKey.prefix(8))...")
                        return  // Don't store key rotation messages as regular messages
                    }

                    // 📢 Check for group update message in decrypted content (from Android)
                    if decrypted.starts(with: "\u{1F4E2}GROUP_UPDATE\u{1F4E2}") {
                        OshiLog.messaging.info("📢 Detected group update in decrypted message from \(message.senderPublicKey.prefix(8))...")
                        let jsonStr = String(decrypted.dropFirst("\u{1F4E2}GROUP_UPDATE\u{1F4E2}".count))
                        if let jsonData = jsonStr.data(using: .utf8) {
                            Task { @MainActor in
                                NotificationCenter.default.post(
                                    name: NSNotification.Name("GroupUpdateReceived"),
                                    object: nil,
                                    // __GROUP_UPDATE_AUTHORIZATION_2026_08_16__
                                    // see the plaintext branch above.
                                    userInfo: ["groupData": jsonData,
                                               "updatedBy": message.senderPublicKey]
                                )
                            }
                        }
                        return  // Don't store group updates as regular messages
                    }

                    if decrypted.starts(with: "MEDIA|||") {
                        OshiLog.messaging.info("🎯 Detected media marker!")
                        let components = decrypted.components(separatedBy: "|||")
                        OshiLog.messaging.info("   Components count: \(components.count)")
                        
                        if components.count >= 3 {
                            OshiLog.messaging.info("   Media type: \(components[1])")
                            OshiLog.messaging.info("   Base64 length: \(components[2].count)")
                            
                            if let mediaData = Data(base64Encoded: components[2]) {
                                receivedMessage.mediaAttachment = mediaData
                                receivedMessage.originalMediaData = mediaData
                                receivedMessage.mediaType = MediaManager.MediaType(rawValue: components[1])
                                receivedMessage.plaintextContent = ""
                                OshiLog.messaging.info("✅ Extracted media from marker: \(components[1]), \(mediaData.count) bytes")
                            } else {
                                OshiLog.messaging.info("❌ Failed to decode media base64")
                            }
                        }
                    } else if decrypted.starts(with: "{") && Self.isMediaMessage(decrypted) {
                        OshiLog.messaging.info("🎯 Detected media JSON!")
                        if Self.parseAndStoreMedia(decrypted, for: &receivedMessage) {
                            OshiLog.messaging.info("✅ Media extracted and stored in message")
                        } else {
                            // If parsing failed, store as text
                            receivedMessage.plaintextContent = decrypted
                            OshiLog.messaging.info("⚠️ Media parsing failed, stored as text")
                        }
                    } else if let mediaType = detectRawBase64MediaType(decrypted) {
                        OshiLog.messaging.info("🎯 Detected raw base64 media: \(mediaType.rawValue)")
                        if parseRawBase64Media(decrypted, type: mediaType, for: &receivedMessage) {
                            OshiLog.messaging.info("✅ Raw media extracted and stored in message")
                        } else {
                            receivedMessage.plaintextContent = decrypted
                            OshiLog.messaging.info("⚠️ Raw media parsing failed, stored as text")
                        }
                    } else {
                        receivedMessage.plaintextContent = decrypted
                    }
                } else {
                    OshiLog.messaging.info("⚠️ Could not decrypt received message")
                    CallFileLogger.shared.log("DIAG_MSG_DECRYPT_FAIL | id=\(receivedMessage.id.prefix(8)) | from=\(receivedMessage.senderPublicKey.prefix(12)) | queued=true")
                    queueForDecryption(receivedMessage)
                    return
                }
            }
        }
        
        // 🔧 FIX (2026-06-04): detect "first message in a fresh conversation"
        // (true new contact OR previously-deleted-then-reconnect) BEFORE
        // addMessage mutates the messages array. Used below to push our own
        // profile back to the sender, so the mutual-delete case re-syncs
        // both sides without waiting for them to ask.
        let _senderKey = receivedMessage.senderPublicKey
        let isFirstMessageFromSender = !messages.contains { msg in
            msg.senderPublicKey == _senderKey || msg.recipientPublicKey == _senderKey
        }

        // __BLOCKED_BY_PEER_2026_09_24__ everything that reaches the thread from a peer
        // says something about "does this peer still take my traffic": its 🚫BLOCKED🚫
        // notice records it, any real message lifts it (BlockedByPeerStore).
        BlockedByPeerStore.shared.observeStoredInbound(from: receivedMessage.senderPublicKey,
                                                       plaintext: receivedMessage.plaintextContent)
        CallFileLogger.shared.log("DIAG_MSG_RX_PRE_ADD | id=\(receivedMessage.id.prefix(8))")
        addMessage(receivedMessage)
        CallFileLogger.shared.log("DIAG_MSG_RX_POST_ADD | id=\(receivedMessage.id.prefix(8))")

        // Sync new message to web session (if connected)
        WebSessionManager.shared.notifyNewMessage(receivedMessage)
        CallFileLogger.shared.log("DIAG_MSG_RX_POST_WEBSYNC | id=\(receivedMessage.id.prefix(8))")

        // __WATCH_PUSH_2026_08_28__ La montre est prevenue ICI, comme la session
        // web juste au-dessus — et pour la meme raison.
        //
        // MESURE: `sendChatMessageToWatch` existait, etait CORRECT, et n'avait
        // AUCUN appelant dans tout le depot. Cote montre, `case "newMessage"`
        // etait donc du code mort. Consequence au poignet: conversation ouverte,
        // le correspondant repond, et l'ecran ne bouge pas — il fallait penser a
        // appuyer sur la fleche de rafraichissement. Seuls les 4 sites d'appel
        // VOIX utilisaient reellement le pont; le messager, jamais.
        //
        // Ce point est le bon parce que c'est le MEME entonnoir que toutes les
        // surfaces compagnons empruntent deja: tous les transports (mesh, LoRa,
        // IPFS, relais v2) y passent, le doublon a ete ecarte en tete de
        // `receiveMessage`, et l'orthographe canonique de la cle est posee.
        // Brancher plus haut aurait pousse des doublons; plus bas, on serait
        // apres l'envoi de la notification.
        //
        // `sendChatMessageToWatch` se garde lui-meme: il sort si la session n'est
        // pas activee, et bascule sur `transferUserInfo` quand la montre n'est
        // pas joignable — donc un message recu telephone au fond de la poche
        // arrive quand meme, en differe, au lieu d'etre perdu.
        // __LOCATION_PING_SPAM_2026_09_12__ La montre n'était PAS couverte par le
        // correctif du 24/08, et c'est pour ça que le symptôme a survécu.
        //
        // Le téléphone tait les pings de position en quatre endroits
        // (FallbackService, MessageManager.notifyVPS, MessageManager+V2,
        // NotificationManager) via `LocationMessage.isSilentPushContent`. Mais
        // CHAQUE message reçu était ensuite transmis tel quel à la montre, et
        // `showMessageNotification` côté watchOS poste une notification avec son
        // `.default` pour tout ce qui arrive, sans exception: le partage de
        // position envoie une mise à jour toutes les 10 s, et comme
        // `transferUserInfo` les livre par paquets quand le système le décide,
        // l'utilisateur reçoit une salve au poignet là où le téléphone, lui,
        // restait muet. « Toutes les trente secondes j'ai un ping que tu partages
        // ta loc ».
        //
        // On filtre ici plutôt que sur la montre: le watchOS n'a pas accès à
        // `LocationMessage`, la règle reste dans le seul fichier qui la possède,
        // et on économise au passage la bande passante WCSession. Le début et la
        // FIN d'un partage passent toujours — `isSilentPushContent` ne tait que
        // les pings intermédiaires.
        let isSilentLocationPing = receivedMessage.plaintextContent
            .map { LocationMessage.isSilentPushContent($0) } ?? false
        // __ADVANCED_CHAT_PRIVACY_2026_11_26__ the Watch posts a sounding
        // notification for everything it is handed: a quiet notice never goes.
        let isQuietNotice = receivedMessage.plaintextContent
            .map(OSHIControlPayload.isQuietNotice) ?? false
        if !isSilentLocationPing && !isQuietNotice {
            WatchConnectivityManager.shared.sendChatMessageToWatch(
                receivedMessage,
                contactAlias: MessageManager.resolveContactAlias(receivedMessage.senderPublicKey))
        }

        // Send notification for new message (only if identity manager available)
        // __BLOCKED_NOTICE_SWITCH_2026_09_23__ The 🚫BLOCKED🚫 row in the thread is
        // enough (rule 3, same as Android/desktop): no banner for it.
        let isBlockedNotice = receivedMessage.plaintextContent?
            .hasPrefix(OSHIControlPayload.blockedNoticePrefix) ?? false
        if MessageManager.sharedIdentityManager != nil, !suppressNotification, !isBlockedNotice, !isQuietNotice {
            // Determine notification text based on message type
            let notificationText: String
            if receivedMessage.mediaType == .audio {
                notificationText = "🎤 " + "notification.audio_message".localized
            } else if receivedMessage.mediaType == .image {
                notificationText = "📷 " + "notification.photo_message".localized
            } else if receivedMessage.mediaType == .video {
                notificationText = "🎬 " + "notification.video_message".localized
            } else if receivedMessage.mediaAttachment != nil {
                notificationText = "📎 " + "notification.media_message".localized
            } else if let plaintext = receivedMessage.plaintextContent,
                      !plaintext.isEmpty,
                      !plaintext.starts(with: "{\"type\":\"media\"") {
                // __INAPP_BANNER_RAW_JSON_2026_08_25__ Passer par le catalogue,
                // comme tous les autres afficheurs.
                //
                // C'était `= plaintext`, le texte BRUT. La bannière interne
                // (Dynamic Island, app au premier plan) affichait donc le
                // sentinel et son JSON en clair — « 📱CALL_SUMMARY📱📞↗️… »,
                // « 📍LOCATION📍{"latitude":… } ». Les notifications APN, elles,
                // étaient correctes: `NotificationManager.formatNotificationText`
                // fait ce travail depuis toujours. Seul ce chemin-ci l'avait
                // manqué, ce qui explique « c'est l'interne, pas l'APN ».
                //
                // `previewText` est le catalogue unique du projet — le même que
                // celui qui alimente la liste des conversations, la montre et
                // l'écran verrouillé. Un résumé d'appel, une position, un
                // check-in ou un rendez-vous y deviennent une phrase; un texte
                // ordinaire en ressort inchangé.
                notificationText = InlineMarkup.stripped(OSHIControlPayload.previewText(for: plaintext))
            } else {
                notificationText = "notification.encrypted_message".localized
            }
            
            // __MESHCORE_INTEROP_2026_11_25__ A channel message belongs to the CHANNEL: the banner is
            // titled with the room, says who spoke, and its tap opens the room. It used to carry the
            // speaker's key, so the tap looked for a one-to-one thread that does not exist (LoRa rooms
            // had the same flaw).
            let banner = MessageManager.radioBanner(sender: receivedMessage.senderPublicKey,
                                                    recipient: receivedMessage.recipientPublicKey,
                                                    text: notificationText)
            NotificationManager.shared.sendMessageNotification(
                from: banner.threadKey,
                messageText: banner.text,
                messageId: receivedMessage.id,
                conversationId: banner.threadKey
            )

            // In-app Dynamic Island banner for a live incoming message (only
            // while the app is foreground — system push covers backgrounded).
            let senderKey = banner.threadKey
            let preview = banner.text
            DispatchQueue.main.async {
                // __ONE_NOTICE_IN_FOREGROUND_2026_11_09__ and not for the thread
                // the user is reading — the message lands right in front of them.
                guard ForegroundNoticePolicy.showsInAppBanner(
                    conversationKey: senderKey,
                    openConversationKey: OpenConversation.oneToOneKey,
                    appActive: UIApplication.shared.applicationState == .active) else { return }
                // __ANATOLE_1311_IOS__ (N8) a muted conversation stays quiet in
                // the foreground too.
                if ConversationPrefsStore.shared.isMuted(senderKey) { return }
                // Suppress the in-app banner during App Store screenshot capture
                // so it doesn't clutter the Dynamic Island in the shots. DEBUG-only
                // (guideline 5.6): a shipping build must not let a launch argument
                // silence incoming-message banners.
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") { return }
                #endif
                let name = MeshCoreInterop.threadTitle(forKey: senderKey)
                    ?? (LoRaChannelThread.isChannelKey(senderKey) ? LoRaChannelThread.displayName(senderKey) : nil)
                    ?? MessageManager.resolveContactAlias(senderKey)
                DynamicIslandNotifier.shared.showIncoming(sender: name, preview: preview)
            }
        }

        // 📸 Request profile from sender if we don't have their profile picture
        // This enables automatic profile picture exchange like WhatsApp
        requestProfileFromContact(receivedMessage.senderPublicKey)

        // 🔧 FIX (2026-06-04): if this is the very first message we're seeing
        // from this sender in our local store, proactively push OUR profile to
        // them too. Covers two cases that previously left the other side stale:
        //   1. We never met before → they get our name/photo on first contact.
        //   2. They deleted us (so their cache of us is gone) → reconnect heals.
        // Their delete state isn't visible to us; treating every "first message"
        // as a fresh handshake costs one extra profile push per new conversation
        // but guarantees both directions are in sync. requestProfileFromContact
        // above handles the symmetric direction (us pulling them).
        if isFirstMessageFromSender, let identityManager = MessageManager.sharedIdentityManager {
            Task { [weak self] in
                await self?.sendProfileToContact(receivedMessage.senderPublicKey, identityManager: identityManager)
            }
        }

        // 📸 Update contact's last seen
        ContactPresenceManager.shared.updateLastSeen(publicKey: receivedMessage.senderPublicKey)
        CallFileLogger.shared.log("DIAG_MSG_RX_EXIT | id=\(receivedMessage.id.prefix(8))")
    }

    /// __IDENTITY_CANONICAL_2026_08_15__
    /// The ONE spelling of an OSHI identity key: standard base64 (`+` `/`), padded.
    ///
    /// The same 32 bytes travel in two spellings. The VPS hands keys back in
    /// base64url (`-` `_`, padding stripped — see `VoiceCallManager.base64urlDecode`,
    /// which exists for exactly this reason and normalises the CALL path in a dozen
    /// places), while the Keychain, the QR code and the v2 relay all use standard
    /// base64. The messaging path had no equivalent, and it compares keys with `==`
    /// in three load-bearing places:
    ///
    ///   * `sendSingleMessage` → the peer key becomes the v2 `to` AND the
    ///     `GET /v2/keys/:userKey` path. That lookup is an exact map read on the
    ///     server, so a base64url spelling 404s, `capabilities` reports
    ///     `.unavailable`, and `routeOutgoing` silently falls back to legacy IPFS.
    ///   * `updateConversations` → the dictionary key, so one contact in two
    ///     spellings is two conversation rows.
    ///   * `ChatView.computeLoadedMessages` → conversation membership. A message
    ///     stored under the other spelling is in the array and NOT in the thread:
    ///     the notification fires (it is posted from `receiveMessage`, keyed on the
    ///     sender key alone) and the conversation stays empty. That is the bug this
    ///     function exists to end.
    ///
    /// Returns the input untouched when it is not a base64 key, so ids, aliases and
    /// test fixtures pass through unharmed rather than acquiring padding.
    static func canonicalIdentity(_ key: String) -> String {
        // Fast path, allocation-free: already canonical. This runs once per message
        // in `updateConversations` and once per message in the conversation filter,
        // i.e. over the whole history on every arrival — the common case must not
        // build three intermediate strings to answer "nothing to do".
        if key.count % 4 == 0, !key.contains("-"), !key.contains("_") { return key }
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return key }
        var s = trimmed
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        while s.count % 4 != 0 { s += "=" }
        guard Data(base64Encoded: s) != nil else { return key }
        return s
    }

    /// Best-effort display name for a public key from the saved contact aliases
    /// (falls back to a short key form). Synchronous UserDefaults read.
    /// Fold base64url/padding variants so key comparison can't silently miss an
    /// alias (same normalization as BlockedContactsManager / MeshtasticManager).
    static func normalizeAliasKey(_ key: String) -> String {
        return key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    /// The saved alias for a key, or nil when the user never named this contact.
    /// Callers that need to fall back to something other than the shortened key
    /// (the CallKit path prefers the name carried by the push) must use this
    /// rather than resolveContactAlias, whose miss value is indistinguishable
    /// from a real alias.
    static func lookupContactAlias(_ publicKey: String) -> String? {
        guard let data = UserDefaults.standard.data(forKey: "contactAliases"),
              let aliases = try? JSONDecoder().decode([String: String].self, from: data) else {
            return nil
        }
        // Exact hit first — the common case, no folding needed.
        if let name = aliases[publicKey], !name.isEmpty { return name }
        // __ALIAS_KEY_FOLD_2026_08_13__ The lookup used to be exact-match only, so a
        // call showed the public key instead of the saved name: the UI saves the alias
        // under the locally stored key (standard base64, `=`-padded) while the caller
        // key arrives from the relay already run through the server's normalizeKey —
        // base64url (`-`,`_`) with padding stripped. Never equal, so every CallKit
        // surface fell back to prefix(8). This drives the incoming call, the OUTGOING
        // call handle (VoIPPushManager:889) and both mid-call updates, which is why
        // the key showed in both directions.
        let wanted = normalizeAliasKey(publicKey)
        guard !wanted.isEmpty else { return nil }
        for (storedKey, name) in aliases where !name.isEmpty {
            if normalizeAliasKey(storedKey) == wanted { return name }
        }
        return nil
    }

    /// __SHARED_NICKNAME_2026_09_22__ Le nom a AFFICHER pour une cle, ou nil:
    /// l'alias local d'abord, sinon le pseudo que le contact a partage
    /// (`SharedNickname`). `lookupContactAlias` reste l'alias SEUL — c'est lui
    /// que lisent les ecrans d'edition d'alias, qui ne doivent jamais prendre le
    /// pseudo du pair pour un nom que l'utilisateur aurait tape.
    static func lookupContactDisplayName(_ publicKey: String) -> String? {
        return SharedNickname.resolve(alias: lookupContactAlias(publicKey),
                                      nickname: SharedNickname.nickname(for: publicKey))
    }

    /// Nom d'affichage avec repli sur le prefixe de cle. Malgre son nom
    /// historique, suit la regle alias > pseudo partage > prefixe depuis le
    /// 22/09 — tous ses appelants sont des surfaces d'AFFICHAGE (en-tetes,
    /// notifications, CallKit, profil).
    /// The thread a radio message's banner belongs to and the text it shows: a channel message is filed under
    /// the channel with "speaker: " in front; everything else under its sender, unchanged.
    static func radioBanner(sender: String, recipient: String, text: String) -> (threadKey: String, text: String) {
        guard LoRaChannelThread.isChannelKey(recipient) else { return (sender, text) }
        let speaker = LoRaChannelThread.speakerName(senderKey: sender, nodes: MeshtasticManager.shared.nodes)
        return (recipient, "\(speaker): \(text)")
    }

    static func resolveContactAlias(_ publicKey: String) -> String {
        return lookupContactDisplayName(publicKey) ?? (String(publicKey.prefix(8)) + "…")
    }

    private func queueForDecryption(_ message: SecureMessage) {
        let senderKey = message.senderPublicKey

        // __PENDING_RACE_RMW_2026_10_20__ This is the writer the crash caught on
        // the cooperative pool (see `_pendingDecryption`). The accessor already
        // makes each access safe, which is what stops the memory corruption, but
        // this function is a read-modify-write: it reads the sender's array,
        // appends, and writes it back. Two of those interleaving would not crash
        // any more — they would silently LOSE a parked message, and a parked
        // message is the only remaining copy of something the user was already
        // told about by a push notification.
        //
        // The whole sequence is therefore taken under the lock. The recursion
        // matters here: `withdrawPhantomBanners` below reads the dictionary again
        // while this still holds it.
        pendingDecryptionLock.lock()

        if pendingDecryption[senderKey] == nil {
            pendingDecryption[senderKey] = []
            failedDecryptionCount[senderKey] = 0
        }

        guard var pending = pendingDecryption[senderKey] else {
            pendingDecryptionLock.unlock()
            return
        }

        // De-dupe: don't queue the same message id twice.
        if pending.contains(where: { $0.id == message.id }) {
            pendingDecryptionLock.unlock()
            return
        }

        if pending.count >= maxPendingPerSender {
            OshiLog.messaging.info("⚠️ Pending queue full for \(senderKey.prefix(10))..., dropping oldest")
            pending.removeFirst()
        }

        pending.append(message)
        pendingDecryption[senderKey] = pending
        pendingDecryptionLock.unlock()

        // 🔒 PERSIST: pendingDecryption used to be RAM-only. If the app got
        // killed (crash, swipe-up, OOM) while a message was awaiting ratchet
        // recovery, the encrypted payload was lost forever — the IPFS CID had
        // already been marked as received on the VPS, and Pinata cleanup
        // unpinned the content. Persisting here means the queue survives
        // restart and the 30s retry timer can keep trying after relaunch.
        savePendingDecryption()

        // __PHANTOM_BANNER_2026_10_11__ Le moment exact ou « une banniere a sonne
        // et il n'y a pas de message »: la poussee est passee par l'extension, le
        // dechiffrement vient d'echouer, rien ne sera affiche. On retire la
        // banniere ici plutot que d'attendre le prochain balayage.
        withdrawPhantomBanners(for: [message])

        OshiLog.messaging.info("📋 Queued message for later decryption (\(pending.count) pending from this sender)")
    }

    private func savePendingDecryption() {
        // 🔧 LAUNCH-FREEZE FIX: strip the decoded media before persisting. These
        // messages are queued because they could NOT be decrypted yet, so
        // `encryptedContent.ciphertext` is the only copy that matters and is kept
        // intact; `mediaAttachment`/`originalMediaData` are decode-side artefacts
        // that only inflate pending_decryption.json — which is read with
        // Data(contentsOf:) + JSONDecoder on the main thread at launch.
        let stripped = pendingDecryption.mapValues { msgs in
            msgs.map { msg -> SecureMessage in
                var m = msg
                m.mediaAttachment = nil
                m.originalMediaData = nil
                return m
            }
        }
        // __PHANTOM_BANNER_2026_10_11__ Hors du thread principal. `FileStorage.save`
        // est synchrone et fait, sous verrou, un passage JSONEncoder PUIS un
        // scellement `AtRestCipher` PUIS une ecriture atomique. Les appelants sont
        // `queueForDecryption` et `tryDecryptPendingMessages`, tous deux sur le
        // thread principal (`receiveMessage` y est confine), donc chaque message
        // indechiffrable faisait payer un chiffrement + une ecriture disque a la
        // frame en cours. L'instantane est deja detache, l'ordre est preserve par
        // la file serie, et personne ne relit ce fichier avant le prochain
        // lancement.
        MessageManager.pendingDecryptionSaveQueue.async {
            FileStorage.shared.save(stripped, forKey: "pending_decryption")
        }
    }

    /// File serie dediee a `pending_decryption.json` (voir `savePendingDecryption`).
    private static let pendingDecryptionSaveQueue =
        DispatchQueue(label: "com.oshi.pendingDecryption.save", qos: .utility)

    // NOTE: the pending-decryption restore lives in `loadDeferredQueues()` — it is
    // read off the main thread together with the retry queue so neither blocks the
    // first frame.

    
    func decryptMessage(_ message: SecureMessage, using identityManager: IdentityManager) -> String? {
        lastRatchetVerifiedMessageId = nil
        // Skip if message already has plaintext content (avoid re-decryption loops)
        if let existingPlaintext = message.plaintextContent,
           !existingPlaintext.isEmpty,
           !existingPlaintext.hasPrefix("eyJ") { // eyJ = base64 JSON, still encrypted
            OshiLog.messaging.info("⏭️ Message already decrypted, returning cached plaintext")
            return existingPlaintext
        }
        
        // Skip if message has media attachment already (audio/image already decoded)
        if message.mediaAttachment != nil && message.mediaType != nil {
            OshiLog.messaging.info("⏭️ Message has media attachment, skipping decryption")
            return message.plaintextContent ?? ""
        }
        
        do {
            let recipientKey = message.recipientPublicKey
            let senderKey = message.senderPublicKey
            
            OshiLog.messaging.info("🔓 [DECRYPT] Message from \(senderKey.prefix(10))... to \(recipientKey.prefix(10))...")
            
            let sharedSecret = try identityManager.computeSharedSecret(with: senderKey)
            
            OshiLog.messaging.info("🔑 [RECEIVER] Decrypting from sender \(senderKey.prefix(8))...")
            
            let ratchetData = try identityManager.unwrapRatchetMessage(message.encryptedContent)
            let ratchetMessage = try JSONDecoder().decode(DoubleRatchetMessage.self, from: ratchetData)
            
            let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                with: senderKey,
                ourPublicKey: identityManager.publicKey,
                sharedSecret: sharedSecret,
                // The responder's half of the KEM lives in this header.
                incomingHeader: ratchetMessage.header
            )
            
            let plaintext = try session.decrypt(ratchetMessage)
            
            OshiLog.messaging.info("✅ Decrypted successfully (\(plaintext.count) chars)")
            
            // Reset failure counter on successful decryption
            if let failedCount = failedDecryptionCount[senderKey], failedCount > 0 {
                OshiLog.messaging.info("✅ Resolved previous decryption issues for \(senderKey.prefix(10))...")
                failedDecryptionCount[senderKey] = 0
                sessionResyncAttempts[senderKey] = 0
            }
            
            lastRatchetVerifiedMessageId = message.id
            return plaintext
            
        } catch let error as RatchetError {
            return handleRatchetDecryptionError(error, message: message, identityManager: identityManager)
            
        } catch let error as DoubleRatchetError {
            return handleDoubleRatchetError(error, message: message, identityManager: identityManager)
            
        } catch {
            // Handle CryptoKit authentication failures
            let errorDescription = error.localizedDescription.lowercased()
            if errorDescription.contains("authentication") ||
               errorDescription.contains("cryptokit") ||
               errorDescription.contains("erreur 3") {
                OshiLog.messaging.info("❌ CryptoKit authentication failure detected")
                return handleSessionDesync(message: message, identityManager: identityManager, reason: "CryptoKit auth failure")
            }
            
            OshiLog.messaging.info("❌ Decryption error: \(error.localizedDescription)")
            return nil
        }
    }
    
    // MARK: - Session Resynchronization
    
    /// Id of the message whose plaintext came out of the DOUBLE RATCHET, as
    /// opposed to one of `decryptMessage`'s passthrough returns.
    ///
    /// 🔧 FIX (2026-08-10, LoRa identity binding could be forged): the binding
    /// used "decryptMessage returned non-nil" as proof that the envelope was
    /// produced by the holder of `senderPublicKey`'s private half. It is not.
    /// `decryptMessage` returns non-nil with ZERO cryptography when
    /// `mediaAttachment` and `mediaType` are both set (see the early return near
    /// the top of it) — and both are decoded straight off the wire. Public keys
    /// are shared openly by QR, so anyone with a radio in range could craft an
    /// envelope claiming a contact's key, bind their own node to that identity,
    /// and from then on have their raw plaintext filed inside that contact's
    /// encrypted conversation under that contact's name.
    ///
    /// Set ONLY where the ratchet actually produced the plaintext. Checked
    /// against the specific message id, so a stale success cannot vouch for a
    /// later envelope. Any future passthrough added to `decryptMessage` fails
    /// closed here instead of silently becoming a new forgery path.
    private var lastRatchetVerifiedMessageId: String?

    /// One-shot latch for the upload timing log. A class so the escaping
    /// progress closure can flip it without capturing `self` or a `var`.
    final class FirstProgressBox: @unchecked Sendable {
        private let lock = NSLock()
        private var fired = false
        func stamp() -> Bool {
            lock.lock(); defer { lock.unlock() }
            if fired { return false }
            fired = true
            return true
        }
    }

    private var sessionResyncAttempts: [String: Int] = [:]
    private var lastSessionReset: [String: Date] = [:]
    private let maxResyncAttempts = 3
    private let sessionResetCooldown: TimeInterval = 60  // 60 seconds cooldown between resets
    
    private func handleRatchetDecryptionError(_ error: RatchetError, message: SecureMessage, identityManager: IdentityManager) -> String? {
        switch error {
        case .messageSkipped:
            OshiLog.messaging.info("⚠️ Out-of-order message, queueing...")
            queueForDecryption(message)
            
        case .tooManySkippedMessages:
            OshiLog.messaging.info("❌ Too many skipped messages")
            return handleSessionDesync(message: message, identityManager: identityManager, reason: "too many skipped")
            
        case .authenticationFailed:
            OshiLog.messaging.info("❌ Authentication failed - session likely desynchronized")
            return handleSessionDesync(message: message, identityManager: identityManager, reason: "auth failed")
            
        case .invalidMessage:
            OshiLog.messaging.info("❌ Invalid message format")
        }
        
        return nil
    }
    
    /// Does this ratchet failure PROVE the session is broken, or only that THIS
    /// envelope cannot be read?
    ///
    /// __REPLAY_IS_NOT_A_DESYNC_2026_10_14__ The distinction is the whole bug
    /// reported on 2026-10-14 (Huawei nova 5T ↔ iPad 9, "delivery works one time
    /// in two"), and it is the rule `V2SyncState` already states for the v2 path:
    /// never act on an ambiguous signature, because erasing a session by mistake
    /// destroys everything the peer writes under it until they have pulled our ack.
    /// The legacy ratchet path never got that taxonomy.
    ///
    /// `.invalidMessageNumber` has ONE throw site (DoubleRatchet.swift, the `else`
    /// after the gap handling) and it is reachable only when
    /// `messageNumber < receivingMessageNumber` on a chain we are already on —
    /// i.e. an envelope we have ALREADY read, arriving a second time. The ratchet
    /// refuses it and rolls its own state back (proved by
    /// `testV3SurvivesReorderingLossAndDuplicates`), so the session is healthy;
    /// it is this layer that used to tear it down.
    ///
    /// And on a mesh a second copy is routine, not exotic: the mesh floods, and
    /// every `CrossPlatformMesh.sendMessage` mints a FRESH frame id, so a resend
    /// of the same ciphertext is invisible to both dedup layers — and iOS then
    /// wraps it in a `SecureMessage` with a fresh UUID, so the duplicate guard in
    /// `receiveMessage` cannot catch it either. The replay reaches the ratchet.
    ///
    /// What that cost: `handleSessionDesync` resets IMMEDIATELY for a mesh message
    /// (no cooldown, by design). So one duplicate destroyed a healthy session, the
    /// peer's next message could not be read by the fresh one, which reset it
    /// again — and only a message that happened to open a new chain got through.
    ///
    /// Pure + static so the rule is stated once and tested (V2DecryptFailureClassTests).
    static func ratchetFailureProvesSessionIsBroken(_ error: DoubleRatchetError) -> Bool {
        switch error {
        case .oldMessage, .invalidMessageNumber:
            // Behind the chain. Says nothing about the session.
            return false
        case .sessionMismatch, .versionMismatch, .tooManySkippedMessages,
             .uninitializedSendingChain, .decryptionFailed, .encryptionFailed,
             .invalidPublicKey, .staleSession:
            return true
        }
    }

    private func handleDoubleRatchetError(_ error: DoubleRatchetError, message: SecureMessage, identityManager: IdentityManager) -> String? {
        guard MessageManager.ratchetFailureProvesSessionIsBroken(error) else {
            OshiLog.messaging.info("⚠️ Enveloppe en retard ou rejouée (\(error)) — ignorée, la session reste intacte")
            return nil
        }

        switch error {
        case .sessionMismatch:
            OshiLog.messaging.info("❌ Session ID mismatch - forcing resync")
            return handleSessionDesync(message: message, identityManager: identityManager, reason: "session mismatch")
            
        case .versionMismatch:
            OshiLog.messaging.info("❌ Session version mismatch - forcing resync")
            return handleSessionDesync(message: message, identityManager: identityManager, reason: "version mismatch")
            
        case .oldMessage, .invalidMessageNumber:
            // Already handled by the guard above; kept so the switch stays total
            // and a new error cannot be added without deciding its class.
            return nil
            
        case .tooManySkippedMessages:
            return handleSessionDesync(message: message, identityManager: identityManager, reason: "too many skipped")
            
        case .uninitializedSendingChain:
            // The session was persisted by a build that left the responder's
            // sending chain at 32 zero bytes. Encrypting on it would have produced
            // a message anyone could read, so `_encrypt` refused. Resync: the
            // rebuilt session gets a real chain. [Audit 2026-09-08]
            OshiLog.messaging.error("🚨 Uninitialized sending chain — forcing resync rather than sending in the clear")
            return handleSessionDesync(message: message, identityManager: identityManager, reason: "uninitialized sending chain")

        case .decryptionFailed, .encryptionFailed, .invalidPublicKey, .staleSession:
            OshiLog.messaging.info("❌ DoubleRatchet error: \(error)")
            return handleSessionDesync(message: message, identityManager: identityManager, reason: "\(error)")
        }
    }
    
    private func handleSessionDesync(message: SecureMessage, identityManager: IdentityManager, reason: String) -> String? {
        let senderKey = message.senderPublicKey

        // 🔧 Mesh messages: immediately reset and retry (can't be re-delivered via VPS)
        // VPS messages: normal flow with queuing + retry timer
        let isMeshMessage = message.deliveryMethod == .mesh
        if isMeshMessage {
            OshiLog.messaging.info("🔄 [Mesh] Session desync for \(senderKey.prefix(10))... reason: \(reason)")
            OshiLog.messaging.info("   Mesh message → immediate session reset + retry")
            lastSessionReset[senderKey] = Date()
            DoubleRatchetSessionManager.shared.resetSession(
                with: senderKey,
                ourPublicKey: identityManager.publicKey
            )
            pendingDecryption.removeValue(forKey: senderKey)
            failedDecryptionCount.removeValue(forKey: senderKey)
            sessionResyncAttempts.removeValue(forKey: senderKey)
            savePendingDecryption()
            if let plaintext = retryDecryptWithFreshSession(message: message, identityManager: identityManager) {
                return plaintext
            }
            OshiLog.messaging.info("❌ [Mesh] Could not decrypt even with fresh session")
            return nil
        }

        // Check cooldown - don't reset session too frequently
        if let lastReset = lastSessionReset[senderKey] {
            let timeSinceReset = Date().timeIntervalSince(lastReset)
            if timeSinceReset < sessionResetCooldown {
                OshiLog.messaging.info("⏳ Session reset on cooldown (\(Int(sessionResetCooldown - timeSinceReset))s remaining)")
                OshiLog.messaging.info("   Message will be queued for later")
                queueForDecryption(message)
                return nil
            }
        }

        let attempts = (sessionResyncAttempts[senderKey] ?? 0) + 1
        sessionResyncAttempts[senderKey] = attempts

        OshiLog.messaging.info("🔄 Session desync detected for \(senderKey.prefix(10))...")
        OshiLog.messaging.info("   Reason: \(reason)")
        OshiLog.messaging.info("   Resync attempt: \(attempts)/\(maxResyncAttempts)")
        
        if attempts >= maxResyncAttempts {
            OshiLog.messaging.info("🔥 Max resync attempts reached - resetting session completely")
            
            // Record reset time for cooldown
            lastSessionReset[senderKey] = Date()
            
            // Reset the session
            DoubleRatchetSessionManager.shared.resetSession(
                with: senderKey,
                ourPublicKey: identityManager.publicKey
            )
            
            // Clear pending messages for this sender
            pendingDecryption.removeValue(forKey: senderKey)
            failedDecryptionCount.removeValue(forKey: senderKey)
            sessionResyncAttempts.removeValue(forKey: senderKey)
            savePendingDecryption()

            // Try to decrypt with fresh session
            OshiLog.messaging.info("🔄 Attempting decryption with fresh session...")
            if let plaintext = retryDecryptWithFreshSession(message: message, identityManager: identityManager) {
                return plaintext
            }
            
            // Notify that session was reset (send a special message)
            Task {
                await sendSessionResetNotification(to: senderKey, identityManager: identityManager)
            }
            
            OshiLog.messaging.info("❌ Could not decrypt even with fresh session")
            return nil
        }
        
        // Queue for later attempt
        queueForDecryption(message)
        return nil
    }
    
    private func retryDecryptWithFreshSession(message: SecureMessage, identityManager: IdentityManager) -> String? {
        do {
            let senderKey = message.senderPublicKey
            let sharedSecret = try identityManager.computeSharedSecret(with: senderKey)
            
            let ratchetData = try identityManager.unwrapRatchetMessage(message.encryptedContent)
            let ratchetMessage = try JSONDecoder().decode(DoubleRatchetMessage.self, from: ratchetData)
            
            // Get fresh session
            let session = DoubleRatchetSessionManager.shared.getOrCreateSessionNegotiatingPQ(
                with: senderKey,
                ourPublicKey: identityManager.publicKey,
                sharedSecret: sharedSecret,
                // The responder's half of the KEM lives in this header.
                incomingHeader: ratchetMessage.header
            )
            
            let plaintext = try session.decrypt(ratchetMessage)
            OshiLog.messaging.info("✅ Decrypted with fresh session (\(plaintext.count) chars)")
            
            // Reset counters on success
            sessionResyncAttempts[senderKey] = 0
            failedDecryptionCount[senderKey] = 0
            
            lastRatchetVerifiedMessageId = message.id
            return plaintext
        } catch {
            OshiLog.messaging.info("❌ Fresh session decryption also failed: \(error.localizedDescription)")
            return nil
        }
    }
    
    // 🔧 2026-06-09: Per-peer rate-limit so a malicious sender can't spam
    // SESSION_RESET signals to wipe our ratchet repeatedly. Max one
    // outbound reset notification per peer per 60s.
    private var lastResetNotificationSentAt: [String: Date] = [:]
    private let resetNotificationCooldownSeconds: TimeInterval = 60

    // __RATCHET_REHANDSHAKE_2026_10_03__ Throttle for the proactive re-handshake.
    private var lastRehandshakeSentAt: [String: Date] = [:]
    private let rehandshakeCooldownSeconds: TimeInterval = 45

    /// __RATCHET_REHANDSHAKE_2026_10_03__ Proactively rebuild the Double Ratchet after a
    /// session reset instead of waiting for a human to send (the old behaviour: "next inbound
    /// message will create a fresh session" — which left a chat silent until someone typed,
    /// and is why a desynced contact needed a manual "Reset Session").
    ///
    /// Called by the side that RECEIVES a reset signal: it has just wiped its session, so a
    /// send here starts a fresh X3DH the peer adopts, healing BOTH directions. Making only the
    /// signal-receiver re-handshake keeps a single initiator, so two freshly-reset peers can't
    /// each start a competing session.
    ///
    /// The nudge is a `⌨️TYPING⌨️` carried on the NORMAL ratchet path (so it actually
    /// re-establishes the session — receipts/typing elsewhere ride ECDH side-channels and would
    /// not). `⌨️TYPING⌨️` is in every shipping build's silent control catalogue, so neither
    /// side displays it and the send path suppresses its push. Dispatched off the decrypt/
    /// transport path (its own MainActor Task) and throttled.
    func sendRatchetRehandshake(to recipientKey: String, identityManager: IdentityManager) {
        guard !recipientKey.isEmpty else { return }
        if let last = lastRehandshakeSentAt[recipientKey],
           Date().timeIntervalSince(last) < rehandshakeCooldownSeconds {
            return
        }
        lastRehandshakeSentAt[recipientKey] = Date()
        Task { @MainActor in
            guard let mesh = MessageManager.sharedMeshManager else {
                OshiLog.messaging.info("🤝 [rehandshake] no mesh manager — skipping")
                return
            }
            _ = self.sendMessage(content: "⌨️TYPING⌨️",
                                 recipientAddress: recipientKey,
                                 identityManager: identityManager,
                                 meshManager: mesh,
                                 skipLocalMessage: true)
            OshiLog.messaging.info("🤝 [rehandshake] sent session-establishing nudge to \(recipientKey.prefix(10))…")
        }
    }

    /// Internal rather than private so the v2 receive loop can ask the peer to
    /// reset too — see `__V2_RATCHET_DEAD_END_2026_08_16__`. The signal rides the
    /// shared-secret side channel, NOT either ratchet, so it still lands when the
    /// v2 session is exactly the thing that is broken.
    func sendSessionResetNotification(to recipientKey: String, identityManager: IdentityManager) async {
        // 🔧 2026-06-09 PROPER IMPLEMENTATION (was previously a no-op stub).
        //
        // When OUR ratchet with a peer is broken, we reset locally — but the
        // peer's ratchet state still thinks it's in sync, so they keep
        // sending messages we can't decrypt. This sends them an
        // OUT-OF-BAND ratchet-reset signal so they also reset their state.
        //
        // Wire format: same IPFS envelope as call signals, with the new
        // `isRatchetReset: true` flag. The encrypted payload is
        // AES-GCM-sealed using an HKDF-derived key from the X25519 shared
        // secret — bypasses the broken Double Ratchet entirely, so the
        // peer CAN decrypt this signal even when normal messages fail.
        OshiLog.messaging.info("📤 Sending session reset notification to \(recipientKey.prefix(10))...")

        // Cooldown — don't spam reset signals.
        if let last = lastResetNotificationSentAt[recipientKey],
           Date().timeIntervalSince(last) < resetNotificationCooldownSeconds {
            OshiLog.messaging.info("⏳ Reset notification cooldown active, skipping")
            return
        }
        lastResetNotificationSentAt[recipientKey] = Date()

        do {
            // 1. Build payload — prefix + timestamp + our pubkey, all
            //    UTF-8. Receiver verifies prefix to reject random ciphertexts.
            let payloadString = "__SESSION_RESET__|\(Int(Date().timeIntervalSince1970))|\(identityManager.publicKey)"
            guard let payloadData = payloadString.data(using: .utf8) else {
                OshiLog.messaging.info("❌ Failed to encode reset payload")
                return
            }

            // 2. Derive a fresh AES key from the ECDH shared secret.
            let rawSharedSecret = try identityManager.computeSharedSecret(with: recipientKey)
            let symmetricKey = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: rawSharedSecret),
                salt: "OSHI-RatchetReset-v1".data(using: .utf8)!,
                info: "ratchet-reset".data(using: .utf8)!,
                outputByteCount: 32
            )

            // 3. AES-GCM seal.
            let sealed = try AES.GCM.seal(payloadData, using: symmetricKey)
            guard let combinedCipher = sealed.combined else {
                OshiLog.messaging.info("❌ AES-GCM seal returned no combined data")
                return
            }

            // 4. Build the IPFS envelope.
            let envelope: [String: Any] = [
                "id": UUID().uuidString,
                "senderAddress": identityManager.publicKey,
                "recipientAddress": recipientKey,
                "encryptedContent": combinedCipher.base64EncodedString(),
                "timestamp": ISO8601DateFormatter().string(from: Date()),
                "isRatchetReset": true
            ]

            // 5. Upload via Pinata + queue. We reuse the same VPS-proxied
            //    Pinata endpoint that regular messages use. Failure here
            //    just means the peer won't auto-reset — the user can fall
            //    back to the manual "Reset session" menu item.
            // __COVER_FAIL_CLOSED_2026_11_18__ the reset rides the legacy relay only.
            guard CoverTransport.legacyOneToOneRelayAllowed else { return }
            guard let payloadJSON = try? JSONSerialization.data(withJSONObject: envelope),
                  let pinataURL = URL(string: "\(PinataConfig.apiURL)/pinning/pinJSONToIPFS") else {
                OshiLog.messaging.info("❌ Could not build Pinata request")
                return
            }
            let pinataBody: [String: Any] = [
                "pinataContent": envelope,
                "pinataMetadata": ["name": "ratchet_reset_\(UUID().uuidString.prefix(8))"]
            ]
            guard let pinataData = try? JSONSerialization.data(withJSONObject: pinataBody) else { return }

            var req = URLRequest(url: pinataURL)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = pinataData
            req.timeoutInterval = 15

            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 || http.statusCode == 201,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let ipfsHash = json["IpfsHash"] as? String else {
                OshiLog.messaging.info("❌ Pinata upload failed for reset signal")
                // __RESET_WAS_UNOBSERVABLE_2026_08_28__ voir la note sur le succès.
                CallFileLogger.shared.log("DIAG_V2_RESET_TX | to=\(recipientKey.prefix(12)) | result=upload_failed")
                _ = payloadJSON  // silence unused warning if we ever bail before consuming
                return
            }

            // 6. Enqueue on VPS so the peer's poll picks it up.
            // 🔕 2026-06-11 (b56): nopush — ratchet-reset is an invisible
            // protocol signal; the generic fallback push produced phantom
            // "new message" banners with nothing in the conversation.
            let encodedRecipient = base64urlEncode(recipientKey)
            let queueURLString = "\(currentVPSURL)/api/queue/\(encodedRecipient)/\(ipfsHash)?nopush=1"
            if let queueURL = URL(string: queueURLString) {
                var qReq = URLRequest(url: queueURL)
                qReq.httpMethod = "POST"
                qReq.timeoutInterval = 10
                _ = try? await URLSession.shared.data(for: qReq)
            }

            OshiLog.messaging.info("✅ Session reset signal uploaded (hash=\(ipfsHash.prefix(8)))")
            // __RESET_WAS_UNOBSERVABLE_2026_08_28__ Le bouton « Réinitialiser la
            // session » ne rendait AUCUN compte. On appuyait, et il ne se passait
            // rien de visible — ni succès, ni échec, ni « c'est parti ». Impossible
            // de distinguer « le signal n'est pas parti » de « il est parti et le
            // pair ne l'a pas encore lu », alors que ces deux cas demandent des
            // actions opposées.
            //
            // Et le second est le cas NORMAL: la file est appelée avec `nopush=1`,
            // donc ce signal — le seul qui puisse réparer la session — est aussi le
            // seul qui ne réveille pas l'appareil d'en face. Il attend que le pair
            // ouvre l'app. Les messages, eux, poussent une notification. D'où le
            // symptôme signalé: « ils reçoivent la notification mais pas le
            // message » — la bannière arrive, la réparation dort dans la file.
            //
            // Passé par `CallFileLogger` et non `OshiLog`: c'est le seul chemin qui
            // survit à l'export redacté (voir
            // `__EXPORT_BLIND_TO_THE_RATCHET_2026_08_28__`). Un diagnostic qui
            // n'atteint pas l'export ne sert que si on a le téléphone en main.
            CallFileLogger.shared.log("DIAG_V2_RESET_TX | to=\(recipientKey.prefix(12)) | result=queued | push=false | hash=\(ipfsHash.prefix(8))")
        } catch {
            OshiLog.messaging.info("❌ sendSessionResetNotification failed: \(error.localizedDescription)")
            CallFileLogger.shared.log("DIAG_V2_RESET_TX | to=\(recipientKey.prefix(12)) | result=error | err=\(error.localizedDescription)")
        }
    }
    
    /// Force reset session with a contact - can be called from UI
    func forceResetSession(with contactKey: String, identityManager: IdentityManager) {
        OshiLog.messaging.info("🔧 Force resetting session with \(contactKey.prefix(10))...")
        CallFileLogger.shared.log("DIAG_V2_RESET_LOCAL | peer=\(contactKey.prefix(12)) | legacy+v2 wiped")

        DoubleRatchetSessionManager.shared.resetSession(
            with: contactKey,
            ourPublicKey: identityManager.publicKey
        )

        pendingDecryption.removeValue(forKey: contactKey)
        failedDecryptionCount.removeValue(forKey: contactKey)
        sessionResyncAttempts.removeValue(forKey: contactKey)
        savePendingDecryption()

        // __V2_SESSION_RESETS_TOO_2026_08_16__ (same reason as the INBOUND reset
        // handler): the v2 ratchet is a separate store. Wiping only the legacy one
        // here left the manual "Reset session" button healing the chain the peer no
        // longer uses, while the v2 chain — the one v2-capable peers actually send
        // on — stayed exactly as broken. The peer wipes both when our signal below
        // lands; this side has to do the same or the reset is one-legged.
        try? V2SessionStore().deleteSession(peerUserKey: contactKey)

        OshiLog.messaging.info("✅ Session reset complete (legacy + v2) - next message will create fresh session")

        // 🔧 2026-06-09: also notify the peer so THEY reset locally.
        // Without this, the peer keeps sending with their old chain key
        // and our fresh ratchet still can't decrypt them. Sent via
        // shared-secret ECDH side-channel — bypasses the ratchet we just
        // wiped. Cooldown inside sendSessionResetNotification prevents spam
        // if the user taps "Reset" repeatedly.
        Task { [weak self] in
            await self?.sendSessionResetNotification(to: contactKey, identityManager: identityManager)
        }
    }
    
    func addMessage(_ message: SecureMessage) {
        guard !messages.contains(where: { $0.id == message.id }) else {
            OshiLog.messaging.info("⏭️ Message already exists, skipping")
            return
        }

        // Back-fill replyToId from the reply-prefix plaintext so both local and inbound messages expose it uniformly.
        var message = message
        if message.replyToId == nil, let plaintext = message.plaintextContent,
           let parsed = MessageWithReply.fromMessageString(plaintext)?.replyTo?.originalMessageId {
            message.replyToId = parsed
        }

        // 🔧 FIX: Double-check that profile updates and profile requests are NOT stored
        // This is a safety net in case they slip through the receiveMessage() checks
        if let content = message.plaintextContent {
            if content.hasPrefix("📸PROFILE_UPDATE📸") {
                OshiLog.messaging.info("⏭️ Profile update message blocked from storage (safety check)")
                processIncomingProfileUpdate(from: message.senderPublicKey, content: content)
                return
            }
            if content.hasPrefix("📸PROFILE_REQUEST📸") {
                OshiLog.messaging.info("⏭️ Profile request message blocked from storage (safety check)")
                return
            }
            // 📊 Filet: un bulletin ne se stocke jamais, par quelque chemin
            // qu'il soit arrivé. Il est quand même COMPTÉ ici, sinon un
            // bulletin qui n'a pris que ce chemin serait perdu en silence.
            if PollVotePayload.isVoteMessage(content) {
                OshiLog.messaging.info("⏭️ Poll ballot blocked from storage (safety check)")
                if let vote = PollVotePayload.fromMessageString(content) {
                    let voter = message.senderPublicKey
                    Task { @MainActor in PollStore.shared.record(vote: vote, from: voter) }
                }
                return
            }
            // 🔥 Safety net: Block reaction messages from storage
            if ReactionPayload.isReactionMessage(content) {
                OshiLog.messaging.info("⏭️ Reaction message blocked from storage (safety check)")
                if let payload = ReactionPayload.fromMessageString(content) {
                    MessageReactionManager.shared.processIncomingReaction(payload)
                }
                return
            }
            // 🔑 Safety net: Block key rotation messages from storage
            if content.hasPrefix("🔑KEY_ROTATION🔑") {
                OshiLog.messaging.info("⏭️ Key rotation message blocked from storage (safety check)")
                return
            }
        }

        // 📍 Check if this is a live location message (initial OR update).
        // 🔧 FIX (2026-06-04, Bug 5): hook the peer-keyed cache used by the
        // sender's own bubble to render the receiver's back-share dot. This
        // block now writes to LocationSharingManager.liveContactLocations
        // for BOTH the initial live message and every update — and clears
        // the entry on isStopped. The existing isUpdate early-return path
        // is preserved unchanged so initial live messages still fall through
        // to messages.append below.
        if let content = message.plaintextContent,
           content.hasPrefix(LocationMessage.locationPrefix),
           let locationMessage = LocationMessage.fromMessageString(content),
           locationMessage.isLive,
           let sessionId = locationMessage.sessionId {

            let peerKey = message.senderPublicKey  // peer's pubkey from our POV

            if locationMessage.isStopped {
                // Peer stopped their (back-)share → drop from both caches.
                LocationSharingManager.shared.clearReceivedLiveLocation(sessionId: sessionId)
                LocationSharingManager.shared.clearPeerLiveLocation(sessionId: sessionId)
            } else if let expiresAt = locationMessage.expiresAt {
                // Live coord (initial OR update).
                LocationSharingManager.shared.updatePeerLiveLocation(
                    peerPublicKey: peerKey,
                    coordinate: locationMessage.coordinate,
                    expiresAt: expiresAt,
                    sessionId: sessionId
                )
            }

            // Existing isUpdate in-place-merge behavior is unchanged.
            if locationMessage.isUpdate {
                // Track latest coordinate per sessionId for OfflineMapView's
                // full-screen live overlay (separate consumer).
                LocationSharingManager.shared.updateReceivedLiveLocation(
                    sessionId: sessionId,
                    coordinate: locationMessage.coordinate
                )

                if updateExistingLocationMessage(sessionId: sessionId, newContent: content, newTimestamp: message.timestamp) {
                    OshiLog.messaging.info("📍 Updated existing live location message (sessionId: \(sessionId.prefix(8))...)")
                    return
                }
                // If no existing message found, fall through to add as new.
                OshiLog.messaging.info("📍 No existing location message found for sessionId, adding as new")
            }
        }

        // 🛡️ Check if this is a Check-In UPDATE - update existing message instead of adding new
        // This prevents flooding the chat with "Location Update" messages every 5 minutes
        if let content = message.plaintextContent,
           content.hasPrefix(CheckInMessage.messagePrefix),
           let checkInMessage = CheckInMessage.fromMessageString(content),
           checkInMessage.type == .update {

            // Find and update existing Check-In message with same sessionId
            if let index = messages.lastIndex(where: { msg in
                guard let msgContent = msg.plaintextContent,
                      msgContent.hasPrefix(CheckInMessage.messagePrefix),
                      let existingCheckIn = CheckInMessage.fromMessageString(msgContent),
                      existingCheckIn.sessionId == checkInMessage.sessionId else {
                    return false
                }
                return true
            }) {
                var updatedMessage = messages[index]
                updatedMessage.plaintextContent = content
                updatedMessage.timestamp = message.timestamp
                messages[index] = updatedMessage
                saveMessages()
                updateConversations()
                OshiLog.messaging.info("🛡️ Updated existing Check-In message (sessionId: \(checkInMessage.sessionId.prefix(8))...)")
                return
            }
            // If no existing message found, fall through to add as new
            OshiLog.messaging.info("🛡️ No existing Check-In message found for sessionId, adding as new")
        }
        
        messages.append(message)
        saveMessages()
        updateConversations()
        
        OshiLog.messaging.info("✅ Message added to storage (total: \(messages.count))")
        
        // 🔐 NEW: Notify sender that message was delivered (for IPFS cleanup)
        // This triggers IPFSEphemeralManager to unpin the message from IPFS
        if message.deliveryMethod == .ipfs {
            NotificationCenter.default.post(
                name: NSNotification.Name("MessageDeliveryConfirmed"),
                object: nil,
                userInfo: [
                    "messageId": message.id,
                    "senderKey": message.senderPublicKey
                ]
            )
            OshiLog.messaging.info("📬 Delivery confirmation posted for IPFS cleanup")
        } else if message.deliveryMethod == .mesh {
            // 📬 M4: iOS↔iOS mesh has no receipt path, so mesh-sent bubbles stay
            // stuck at "sent" forever. Send a per-message delivery receipt back
            // over the same encrypted send pipeline (filtered from the UI by its
            // emoji prefix). Guarded against control/system messages to prevent
            // receipt-for-receipt ping-pong.
            sendMeshDeliveryReceiptIfNeeded(for: message)
        }
    }

    /// M4: acks an inbound mesh message by sending "📬DELIVERY_RECEIPT📬<id>"
    /// back to its sender over the normal encrypted send path (mesh, else IPFS).
    /// No-op for our own messages and for control/system traffic — a receipt
    /// must never trigger another receipt.
    /// Control/system prefixes that must never trigger a delivery receipt
    /// (acking one would cause a receipt-for-receipt loop).
    static let meshReceiptControlPrefixes = [
        "📬DELIVERY_RECEIPT📬", "📖READ_RECEIPT📖", "📞CALL_SIGNAL📞",
        "📸PROFILE_UPDATE📸", "📸PROFILE_REQUEST📸", "🔗LINKS_VISIBILITY🔗",
        "🎨WALLPAPER_UPDATE🎨", "⌨️TYPING⌨️", "✍️TYPING✍️",
        // __GROUP_RECEIPTS_2026_11_02__ (N8) acking a receipt with a receipt is
        // the loop this whole list exists to prevent.
        GroupReceipts.prefix,
        // __BLOCKED_NOTICE_SWITCH_2026_09_23__ a terminal refusal, not a message:
        // acking it back to the person who blocked us serves nothing.
        OSHIControlPayload.blockedNoticePrefix
    ]

    /// Whether an inbound mesh message warrants a delivery receipt back to its
    /// sender: not our own message, a real sender, and NOT control/system
    /// traffic. Pure + static → unit-testable (ReceiptStateMachineTests).
    static func shouldAckInboundMeshMessage(content: String?, senderPublicKey: String, myPublicKey: String) -> Bool {
        guard senderPublicKey != myPublicKey, !senderPublicKey.isEmpty else { return false }
        if let pt = content, meshReceiptControlPrefixes.contains(where: { pt.hasPrefix($0) }) {
            return false
        }
        return true
    }

    private func sendMeshDeliveryReceiptIfNeeded(for message: SecureMessage) {
        let me = MessageManager.sharedIdentityManager?.publicKey ?? ""
        // The inbound decrypt path already intercepts control messages before
        // addMessage, but guard here too in case any other path reaches it.
        guard MessageManager.shouldAckInboundMeshMessage(
            content: message.plaintextContent,
            senderPublicKey: message.senderPublicKey,
            myPublicKey: me) else { return }

        guard let identityManager = MessageManager.sharedIdentityManager,
              let meshManager = MessageManager.sharedMeshManager else { return }

        OshiLog.messaging.info("📬 Sending mesh delivery receipt for \(message.id.prefix(8))… to \(message.senderPublicKey.prefix(8))…")
        self.sendMessage(
            content: "📬DELIVERY_RECEIPT📬\(message.id)",
            recipientAddress: message.senderPublicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            skipLocalMessage: true
        )
    }

    // MARK: - __BLOCKED_NOTICE_2026_09_18__

    /// Quand un avis « vous êtes bloqué » a été envoyé à chaque pair.
    /// `UserDefaults` et non un dictionnaire en mémoire: l'app est tuée entre
    /// deux réveils push, et une limite qui repart à zéro à chaque lancement
    /// n'en est pas une.
    private static let blockedNoticeDefaultsKey = "oshi.blockedNotice.lastSent"

    /// Une fois par pair et par jour. Assez long pour que le trafic de
    /// l'expéditeur ne lui serve pas de balise de présence, assez court pour que
    /// quelqu'un qui revient une semaine plus tard soit prévenu plutôt que laissé
    /// dans le doute.
    private static let blockedNoticeInterval: TimeInterval = 24 * 60 * 60

    /// __BLOCKED_NOTICE_SWITCH_2026_09_23__ Interrupteur produit: le bloqué
    /// apprend-il qu'il l'est ? `true` = demande du propriétaire. Signal et
    /// WhatsApp ne le disent délibérément PAS (l'avis confirme au harceleur que
    /// le compte est vivant et qu'il a été vu). Passer à `false` rend le blocage
    /// muet sur ce build — même interrupteur sur Android
    /// (`MessageRepository.BLOCKED_NOTICE_ENABLED`) et Desktop.
    static let blockedNoticeEnabled = true

    /// Dire à un expéditeur bloqué que plus rien de ce qu'il envoie n'arrive.
    ///
    /// Le blocage était entièrement muet: le message partait, l'expéditeur
    /// regardait deux coches grises qui ne changeaient jamais, et il n'apprenait
    /// rien. Le propriétaire a tranché dans l'autre sens — le dire — et c'est
    /// ici, et nulle part ailleurs, que ça se dit.
    ///
    /// Trois règles, toutes porteuses:
    ///
    ///  1. **Ne jamais répondre à de la mécanique.** Le test est
    ///     `OSHIControlPayload.suppressesPush`, réutilisé plutôt que recopié: du
    ///     trafic qui ne mérite pas de réveiller quelqu'un ne mérite pas non plus
    ///     de réponse. Il couvre les accusés, la frappe, les diffusions
    ///     périodiques de profil et de fond d'écran — et l'avis lui-même, ce qui
    ///     est ce qui empêche deux personnes qui se sont bloquées mutuellement de
    ///     se pinguer sans fin.
    ///
    ///  2. **Une fois par pair et par jour.** Sans limite, chaque message d'un
    ///     indésirable appelle une réponse, et ces réponses sont un battement de
    ///     cœur: elles lui disent exactement quand la personne qui l'a bloqué a
    ///     son téléphone allumé. Un avis suffit à dire la chose; les suivants ne
    ///     font que renseigner.
    ///
    ///  3. **La notification est supprimée** (`renderedButNotWorthWaking`): la
    ///     personne tient son téléphone, elle regarde le fil dans lequel elle
    ///     vient d'écrire — la ligne suffit, et une alerte « vous avez été
    ///     bloqué » ne sert personne.
    ///
    /// Android: `MessageRepository.noticeBlockedSender`, mêmes règles, même
    /// sentinelle, même intervalle.
    func noticeBlockedSender(_ senderPublicKey: String, plaintext: String?) {
        guard MessageManager.blockedNoticeEnabled else { return }
        guard !senderPublicKey.isEmpty else { return }
        let me = MessageManager.sharedIdentityManager?.publicKey ?? ""
        guard senderPublicKey != me else { return }
        if let pt = plaintext, OSHIControlPayload.suppressesPush(pt) { return }
        // Transport BEFORE the stamp: a push-woken cold start can reach here
        // before the managers exist, and stamping first burned the day's only
        // notice without sending anything.
        guard let identityManager = MessageManager.sharedIdentityManager,
              let meshManager = MessageManager.sharedMeshManager else { return }

        let key = MessageManager.canonicalIdentity(senderPublicKey)
        var sent = UserDefaults.standard.dictionary(
            forKey: MessageManager.blockedNoticeDefaultsKey) as? [String: Double] ?? [:]
        let now = Date().timeIntervalSince1970
        if let last = sent[key], now - last < MessageManager.blockedNoticeInterval { return }
        // Écrit AVANT l'envoi: un transport qui pend ou qui jette ne doit pas
        // laisser la porte ouverte à un deuxième avis au message suivant.
        sent[key] = now
        UserDefaults.standard.set(sent, forKey: MessageManager.blockedNoticeDefaultsKey)

        OshiLog.messaging.info("🚫 Avis de blocage envoyé à \(senderPublicKey.prefix(8))…")
        sendMessage(
            content: OSHIControlPayload.blockedNoticePrefix,
            recipientAddress: senderPublicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            // Pas de bulle chez nous: c'est un fil qu'on a justement décidé de
            // ne plus lire.
            skipLocalMessage: true
        )
    }
    
    /// Update an existing live location message by sessionId
    /// Returns true if message was found and updated
    func updateExistingLocationMessage(sessionId: String, newContent: String, newTimestamp: Date) -> Bool {
        // Find the message with matching sessionId in its content
        guard let index = messages.firstIndex(where: { message in
            guard let content = message.plaintextContent,
                  content.hasPrefix(LocationMessage.locationPrefix),
                  let existingLocation = LocationMessage.fromMessageString(content),
                  existingLocation.sessionId == sessionId else {
                return false
            }
            return true
        }) else {
            return false
        }
        
        // Update the message content and timestamp
        var updatedMessage = messages[index]
        updatedMessage.plaintextContent = newContent
        updatedMessage.timestamp = newTimestamp
        messages[index] = updatedMessage
        
        saveMessages()
        updateConversations()
        
        // Check if this is a "stopped" message
        let isStopped = LocationMessage.fromMessageString(newContent)?.isStopped ?? false
        
        // Post notification so UI updates
        DispatchQueue.main.async {
            if isStopped {
                // 📍 Post stopped notification - recipient will stop their timer
                NotificationCenter.default.post(name: NSNotification.Name("LiveLocationStopped"), object: nil, userInfo: ["sessionId": sessionId])
            } else {
                NotificationCenter.default.post(name: NSNotification.Name("LiveLocationUpdated"), object: nil, userInfo: ["sessionId": sessionId])
            }
        }
        
        return true
    }
    
    /// Update own sent live location message (for sender's view)
    func updateOwnLocationMessage(sessionId: String, newLocationMessage: LocationMessage) {
        let newContent = newLocationMessage.toMessageString()

        if updateExistingLocationMessage(sessionId: sessionId, newContent: newContent, newTimestamp: Date()) {
            OshiLog.messaging.info("📍 Updated own live location message")
        }
    }

    /// Update own sent Check-In message (for sender's view)
    /// This prevents flooding the chat with "Location Update" messages
    func updateOwnCheckInMessage(sessionId: String, newCheckInMessage: CheckInMessage) {
        let newContent = newCheckInMessage.toMessageString()

        guard let index = messages.lastIndex(where: { msg in
            guard let content = msg.plaintextContent,
                  content.hasPrefix(CheckInMessage.messagePrefix),
                  let existing = CheckInMessage.fromMessageString(content),
                  existing.sessionId == sessionId else {
                return false
            }
            return true
        }) else {
            OshiLog.messaging.info("🛡️ No existing Check-In message found for sessionId \(sessionId.prefix(8))...")
            return
        }

        var updatedMessage = messages[index]
        updatedMessage.plaintextContent = newContent
        updatedMessage.timestamp = Date()
        messages[index] = updatedMessage

        saveMessages()
        updateConversations()
        OshiLog.messaging.info("🛡️ Updated own Check-In message (sessionId: \(sessionId.prefix(8))...)")
    }
    
    /// __IDENTITY_CANONICAL_2026_08_15__ companion, found 2026-10-27: this
    /// filter compared the keys RAW while `updateConversations` and `ChatView`
    /// both compare them canonically.
    ///
    /// So the three surfaces that use this — the Watch, the web session, and
    /// the conversation row's preview — could disagree with the phone's own
    /// chat screen about which messages belong to a conversation. A contact
    /// whose key reached us base64url on one transport and standard base64 on
    /// another is ONE conversation everywhere else and was two here: the
    /// conversation list showed the message, the Watch thread did not.
    ///
    /// Canonicalising both sides can only ever FOLD two spellings of the same
    /// identity together; it cannot make two different identities match, since
    /// `canonicalIdentity` only normalises base64url and padding.
    func getMessages(for publicKey: String, limit: Int = 50) -> [SecureMessage] {
        let peer = MessageManager.canonicalIdentity(publicKey)
        // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ A channel broadcast is stored with
        // the CHANNEL as recipient and the real author as sender, so this `OR`
        // matches it from BOTH sides: the room, which is right, and the speaker's
        // own private thread, which is not.
        //
        // That second copy is the dangerous one. It puts a message twenty radios
        // also heard into what looks like a one-to-one conversation, and invites a
        // reply the user believes only one person can read. A channel message
        // belongs in the channel and nowhere else.
        let peerIsChannel = LoRaChannelThread.isChannelKey(peer)
        return messages
            .filter { msg in
                let sender = MessageManager.canonicalIdentity(msg.senderPublicKey)
                let recipient = MessageManager.canonicalIdentity(msg.recipientPublicKey)
                guard sender == peer || recipient == peer else { return false }
                if peerIsChannel { return true }
                return !LoRaChannelThread.isChannelKey(recipient)
            }
            .sorted { $0.timestamp > $1.timestamp }
            .prefix(limit)
            .reversed()
    }
    
    /// Check if a JSON string represents a media message
    // static (was private) so `MediaReconstructionTests` can drive the real mesh
    // receive path rebuilds inline media — the invariant that makes routing media
    // over the encrypted mesh branch safe (F-mesh#1). [2026-09-10]
    static func isMediaMessage(_ jsonString: String) -> Bool {
        // Check for various patterns that indicate media
        let patterns = [
            "\"type\":\"media\"",
            "\"type\": \"media\"",
            "\"mediaType\":\"audio\"",
            "\"mediaType\": \"audio\"",
            "\"mediaType\":\"image\"",
            "\"mediaType\": \"image\"",
            "\"mediaType\":\"video\"",
            "\"mediaType\": \"video\"",
            // 🤝 Android-direct-typed envelope: {"type":"IMAGE"/"VIDEO"/"AUDIO"/"DOCUMENT", "data":"…"}
            "\"type\":\"IMAGE\"",
            "\"type\": \"IMAGE\"",
            "\"type\":\"VIDEO\"",
            "\"type\": \"VIDEO\"",
            "\"type\":\"AUDIO\"",
            "\"type\": \"AUDIO\"",
            "\"type\":\"DOCUMENT\"",
            "\"type\": \"DOCUMENT\""
        ]

        for pattern in patterns {
            if jsonString.contains(pattern) {
                return true
            }
        }

        // Also try parsing as JSON and checking
        if let data = jsonString.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let type = json["type"] as? String {
                if type == "media" {
                    return true
                }
                // 🤝 Android direct-typed envelope (uppercase enum names)
                let upper = type.uppercased()
                if (upper == "IMAGE" || upper == "VIDEO" || upper == "AUDIO" || upper == "DOCUMENT")
                    && json["data"] != nil {
                    return true
                }
            }
            if json["mediaType"] != nil {
                return true
            }
        }

        return false
    }
    
    /// Check if content appears to be raw base64 audio data (not wrapped in JSON)
    private func isRawBase64Audio(_ content: String) -> Bool {
        // Skip if it's JSON
        if content.starts(with: "{") || content.starts(with: "MEDIA|||") {
            return false
        }
        
        // Check if it looks like base64 (no spaces, valid characters)
        let base64Regex = "^[A-Za-z0-9+/=]+$"
        guard content.range(of: base64Regex, options: .regularExpression) != nil else {
            return false
        }
        
        // Try to decode and check for audio file signatures
        guard let data = Data(base64Encoded: content), data.count > 12 else {
            return false
        }
        
        // Check for common audio file signatures
        let bytes = [UInt8](data.prefix(12))
        
        // M4A/AAC: starts with 'ftyp' at offset 4
        if bytes.count >= 8 {
            let ftyp = String(bytes: bytes[4..<8], encoding: .ascii)
            if ftyp == "ftyp" {
                OshiLog.messaging.info("🔍 Detected raw M4A/AAC audio (ftyp signature)")
                return true
            }
        }
        
        // MP3: starts with ID3 or 0xFF 0xFB
        if bytes[0] == 0x49 && bytes[1] == 0x44 && bytes[2] == 0x33 { // "ID3"
            OshiLog.messaging.info("🔍 Detected raw MP3 audio (ID3 signature)")
            return true
        }
        if bytes[0] == 0xFF && (bytes[1] & 0xE0) == 0xE0 { // MP3 frame sync
            OshiLog.messaging.info("🔍 Detected raw MP3 audio (frame sync)")
            return true
        }
        
        // WAV: starts with "RIFF"
        if bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 {
            OshiLog.messaging.info("🔍 Detected raw WAV audio (RIFF signature)")
            return true
        }
        
        // OGG: starts with "OggS"
        if bytes[0] == 0x4F && bytes[1] == 0x67 && bytes[2] == 0x67 && bytes[3] == 0x53 {
            OshiLog.messaging.info("🔍 Detected raw OGG audio")
            return true
        }
        
        // CAF: starts with "caff"
        if bytes[0] == 0x63 && bytes[1] == 0x61 && bytes[2] == 0x66 && bytes[3] == 0x66 {
            OshiLog.messaging.info("🔍 Detected raw CAF audio")
            return true
        }
        
        // NOT audio - check for other file types that should NOT be treated as audio
        // PDF: starts with "%PDF"
        if bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46 {
            OshiLog.messaging.info("🔍 Detected PDF document, NOT audio")
            return false
        }
        
        // PNG: starts with 0x89 PNG
        if bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 {
            OshiLog.messaging.info("🔍 Detected PNG image, NOT audio")
            return false
        }
        
        // JPEG: starts with 0xFF 0xD8 0xFF
        if bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF {
            OshiLog.messaging.info("🔍 Detected JPEG image, NOT audio")
            return false
        }
        
        // ZIP/DOCX/XLSX: starts with "PK"
        if bytes[0] == 0x50 && bytes[1] == 0x4B {
            OshiLog.messaging.info("🔍 Detected ZIP/Office document, NOT audio")
            return false
        }
        
        // GIF: starts with "GIF"
        if bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 {
            OshiLog.messaging.info("🔍 Detected GIF image, NOT audio")
            return false
        }
        
        return false
    }
    
    /// Check if content appears to be raw base64 media data (any type)
    private func detectRawBase64MediaType(_ content: String) -> MediaManager.MediaType? {
        // Skip if it's JSON
        if content.starts(with: "{") || content.starts(with: "MEDIA|||") {
            return nil
        }
        
        // Check if it looks like base64
        let base64Regex = "^[A-Za-z0-9+/=]+$"
        guard content.range(of: base64Regex, options: .regularExpression) != nil else {
            return nil
        }
        
        guard let data = Data(base64Encoded: content), data.count > 12 else {
            return nil
        }
        
        let bytes = [UInt8](data.prefix(12))
        
        // Audio signatures
        if bytes.count >= 8 {
            let ftyp = String(bytes: bytes[4..<8], encoding: .ascii)
            if ftyp == "ftyp" { return .audio }
        }
        if bytes[0] == 0x49 && bytes[1] == 0x44 && bytes[2] == 0x33 { return .audio } // ID3
        if bytes[0] == 0xFF && (bytes[1] & 0xE0) == 0xE0 { return .audio } // MP3
        if bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 { return .audio } // RIFF/WAV
        if bytes[0] == 0x4F && bytes[1] == 0x67 && bytes[2] == 0x67 && bytes[3] == 0x53 { return .audio } // OGG
        if bytes[0] == 0x63 && bytes[1] == 0x61 && bytes[2] == 0x66 && bytes[3] == 0x66 { return .audio } // CAF
        
        // Image signatures
        if bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 { return .image } // PNG
        if bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF { return .image } // JPEG
        if bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 { return .image } // GIF
        
        // Document signatures
        if bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46 { return .document } // PDF
        if bytes[0] == 0x50 && bytes[1] == 0x4B { return .document } // ZIP/DOCX/XLSX
        
        // Video signatures (MP4 also uses ftyp but with different brand)
        // For now, we don't auto-detect video from raw base64
        
        return nil
    }
    
    /// Parse raw base64 media into a media message
    private func parseRawBase64Media(_ base64String: String, type: MediaManager.MediaType, for message: inout SecureMessage) -> Bool {
        guard let mediaData = Data(base64Encoded: base64String) else {
            OshiLog.messaging.info("❌ Failed to decode raw base64 media")
            return false
        }
        
        OshiLog.messaging.info("✅ Decoded raw media: \(mediaData.count) bytes, type: \(type.rawValue)")
        
        message.mediaAttachment = mediaData
        message.originalMediaData = mediaData
        message.mediaType = type
        message.plaintextContent = "" // Clear plaintext since this is media
        
        OshiLog.messaging.info("✅ Raw media stored in message: \(type.rawValue), \(mediaData.count) bytes")
        return true
    }
    
    static func parseAndStoreMedia(_ jsonString: String, for message: inout SecureMessage) -> Bool {
        OshiLog.messaging.info("🖼️ Parsing media message...")
        OshiLog.messaging.info("   Raw JSON: \(jsonString.prefix(200))...")

        guard let jsonData = jsonString.data(using: .utf8),
              let mediaJson = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            OshiLog.messaging.info("❌ Failed to parse media JSON")
            return false
        }

        // Support both "content" (iOS) and "data" (Android) keys for the base64 media
        let mediaBase64 = (mediaJson["content"] as? String) ?? (mediaJson["data"] as? String)
        guard let base64String = mediaBase64 else {
            OshiLog.messaging.info("❌ No 'content' or 'data' key found in media JSON")
            OshiLog.messaging.info("   Available keys: \(mediaJson.keys)")
            return false
        }

        // 🤝 Resolve media type. Two conventions are accepted:
        //   1) iOS wrapper:        {"type":"media", "mediaType":"image", "content":...}
        //   2) Android direct:     {"type":"IMAGE",                    "data":...}
        //                          (uppercase enum names: IMAGE/VIDEO/AUDIO/DOCUMENT)
        let topLevelType = mediaJson["type"] as? String
        let isAndroidDirect: Bool = {
            guard let t = topLevelType else { return false }
            let u = t.uppercased()
            return u == "IMAGE" || u == "VIDEO" || u == "AUDIO" || u == "DOCUMENT"
        }()

        let rawMediaTypeStr: String? = {
            if isAndroidDirect {
                return topLevelType   // e.g. "IMAGE"
            }
            return mediaJson["mediaType"] as? String   // iOS wrapper form
        }()

        guard let mediaTypeRaw = rawMediaTypeStr else {
            OshiLog.messaging.info("❌ No 'mediaType' (iOS) or top-level typed 'type' (Android) found in media JSON")
            return false
        }

        // Normalize to lowercase for MediaType lookup (Android sends "IMAGE" → "image").
        // Special-case "photo" alias → MediaType.image (some senders may emit "photo").
        var normalized = mediaTypeRaw.lowercased()
        if normalized == "photo" {
            normalized = "image"
        }

        guard let mediaData = Data(base64Encoded: base64String) else {
            OshiLog.messaging.info("❌ Failed to decode media base64 (length: \(base64String.count))")
            return false
        }

        // ✅ Extract fileName if present (iOS) — Android direct format does not carry fileName
        let fileName = mediaJson["fileName"] as? String

        // 🤝 Capture caption if present (Android direct format) so we don't lose it.
        let caption = mediaJson["caption"] as? String

        OshiLog.messaging.info("✅ Decoded media: \(mediaData.count) bytes, type: \(mediaTypeRaw) (normalized: \(normalized))")
        if let fileName = fileName {
            OshiLog.messaging.info("   fileName: \(fileName)")
        }
        if let caption = caption, !caption.isEmpty {
            OshiLog.messaging.info("   caption: \(caption.prefix(60))")
        }

        // Update message with media data directly
        message.mediaAttachment = mediaData
        message.originalMediaData = mediaData
        message.mediaType = MediaManager.MediaType(rawValue: normalized)
        message.mediaFileName = fileName  // ✅ Store fileName (iOS-only field)
        // __VIDEO_NOTE_2026_09_24__ spec §2.C — same keys in both the iOS wrapper
        // and the Android direct-typed form; tolerant, absent = normal video.
        if message.mediaType == .video {
            message.applyVideoNote(VideoNoteMetadata.parse(mediaJson, isVideo: true))
        }
        // Use caption as display text when present (Android direct format),
        // otherwise clear plaintext since this is media.
        message.plaintextContent = caption ?? ""

        OshiLog.messaging.info("✅ Media stored in message: \(normalized), \(mediaData.count) bytes")
        return true
    }
    
    struct TimeoutError: Error {}
    
    func withTimeout<T>(seconds: TimeInterval, operation: @escaping () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask {
                try await operation()
            }
            
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw TimeoutError()
            }
            
            guard let result = try await group.next() else {
                throw NSError(domain: "Timeout", code: -1, userInfo: [NSLocalizedDescriptionKey: "Operation timed out"])
            }
            group.cancelAll()
            return result
        }
    }
    
    func markAsRead(_ messageId: String) {
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            messages[index].isRead = true
            saveMessages()
            updateConversations()
            syncAppBadge()
        }
    }

    /// Batch mark multiple messages as read (single save + single update)
    func markAllAsRead(_ messageIds: [String]) {
        guard !messageIds.isEmpty else { return }
        let idSet = Set(messageIds)
        var changed = false
        for i in messages.indices {
            if idSet.contains(messages[i].id) && !messages[i].isRead {
                messages[i].isRead = true
                changed = true
            }
        }
        if changed {
            saveMessages()
            updateConversations()
        }
    }

    /// Mark all messages in a conversation as read
    func markConversationAsRead(participantPublicKey: String) {
        guard let rawMyKey = MessageManager.sharedIdentityManager?.publicKey else { return }
        // __IDENTITY_CANONICAL_2026_08_15__ Unread badges cleared only for messages
        // whose stored spelling matched the caller's; anything stored under the
        // other one kept the thread permanently unread.
        let myKey = MessageManager.canonicalIdentity(rawMyKey)
        let participantKey = MessageManager.canonicalIdentity(participantPublicKey)
        // __ONE_PUBLISH_2026_09_06__ Mutate a COPY and assign once. Writing
        // `messages[i].isRead = true` in the loop published the whole array once
        // PER unread message: every open ChatView re-filtered, re-sorted and
        // re-grouped the entire history K times, and saveMessages() re-encoded
        // the store K times, all on the main thread while the chat was opening.
        // On a long thread that is seconds of lag, and it is the shape of the
        // main-thread stacks in the 0x8BADF00D watchdog kills of 08-08/08-21.
        var updated = messages
        var changed = false
        for i in updated.indices {
            if !updated[i].isRead &&
               MessageManager.canonicalIdentity(updated[i].recipientPublicKey) == myKey &&
               MessageManager.canonicalIdentity(updated[i].senderPublicKey) == participantKey {
                updated[i].isRead = true
                changed = true
            }
        }
        if changed {
            messages = updated
            saveMessages()
            updateConversations()
            syncAppBadge()

            // 📖 Telegram-parity: tell the sender their messages were read.
            // Android already sends this; iOS only ever received it. The bare
            // prefix is what handleIncomingReadReceipt on both platforms
            // expects. Rides the normal encrypted send path (v2 first) as
            // silent control traffic — no push, filtered from the UI, and
            // excluded from delivery-receipt acks (no ping-pong). Only fires
            // when something actually flipped to read, so opening an
            // already-read chat sends nothing.
            if let identityManager = MessageManager.sharedIdentityManager,
               let meshManager = MessageManager.sharedMeshManager {
                sendMessage(
                    content: "📖READ_RECEIPT📖",
                    recipientAddress: participantPublicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    skipLocalMessage: true
                )
            }
        }
        // __ANATOLE_1311_IOS__ (N10) Viewing the thread retires its banners even
        // when nothing flipped to read here (a message already marked read on
        // another path, or a banner whose message is not stored yet). This is
        // now the ONLY non-tap way a 1:1 banner leaves Notification Centre.
        NotificationManager.shared.clearDeliveredNotifications(forConversation: participantPublicKey)
    }

    /// Sync the app icon badge with the actual total unread count.
    ///
    /// 🔧 FIX (2026-08-05) — a badge that never cleared.
    ///
    /// A GROUP notification increments the stored `appBadgeCount`
    /// (NotificationManager.scheduleGroupNotification), but this — the only
    /// thing that ever recomputes it — counted 1:1 conversations ONLY. So a
    /// group message pushed the badge to 1 and nothing on earth could bring it
    /// back down: reading every 1:1 recomputed 0 unread, wrote 0… and the next
    /// group notification put it back. Measured on the device: `appBadgeCount`
    /// stuck at 1 with an empty inbox.
    ///
    /// Both halves are now counted, so the number on the icon matches what the
    /// two segments of the chat list add up to.
    func syncAppBadge() {
        let oneToOne = conversations.reduce(0) { $0 + $1.unreadCount }
        // MessageGroupManager is main-actor isolated, so the group half is read
        // on main — where the badge is written anyway.
        DispatchQueue.main.async {
            let groups = MessageGroupManager.shared.getTotalUnreadCount()
            // OSHIMailClient is main-actor isolated (read on main, where the badge is
            // written) — the unread Inbox count is the third contributor to the icon
            // badge, so "3 unread emails" shows on the app icon even before the Email
            // tab is opened, and clears the moment they are read (reading flips `seen`,
            // which mutates `messages`, whose didSet calls back into syncAppBadge).
            let mail = OSHIMailClient.shared.unreadInboxCount
            let totalUnread = oneToOne + groups + mail
            PushNotificationManager.shared.updateBadgeCount(totalUnread)
            NotificationManager.shared.updateBadgeCount(totalUnread)
            // __APPLE_WATCH_2027__ The watch widget's count + three names follow
            // the badge (debounced; a no-op unless "Smartwatch" is on).
            WatchBridgeService.scheduleSnapshotPush()
        }
    }
    
    /// Mark a view-once message as viewed and delete the media data
    func markViewOnceAsViewed(_ messageId: String) {
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            guard messages[index].isViewOnce else { return }

            // 🔧 (2026-08-10) Evict the decrypted copy FIRST. The media cache
            // added for scroll performance would otherwise still hold plaintext
            // bytes for something the user chose to destroy — wiping the stored
            // blob while a decrypted copy survives in memory defeats view-once
            // entirely.
            Task { @MainActor in MediaAttachmentCache.shared.remove(messageId) }

            // R8 FIX: Securely wipe media data from memory before releasing
            if var mediaData = messages[index].mediaAttachment, !mediaData.isEmpty {
                mediaData.resetBytes(in: 0..<mediaData.count)
            }
            if var originalData = messages[index].originalMediaData, !originalData.isEmpty {
                originalData.resetBytes(in: 0..<originalData.count)
            }

            messages[index].hasBeenViewed = true
            // Delete the media data after viewing to save space
            messages[index].mediaAttachment = nil
            messages[index].originalMediaData = nil
            // __VIEW_ONCE_DISK_2026_09_29__ ET le fichier au repos. Seule la copie
            // en mémoire partait: `AudioMessages/<id>.m4a` / `MediaMessages/<id>.*`
            // restaient sur le disque, et au lancement suivant
            // `backfillPersistedMedia` les relisait — le vocal « écouté une fois »
            // était de nouveau en RAM et dans l'export.
            deleteAudioAttachment(for: messageId)
            deleteMediaAttachment(for: messageId)

            #if DEBUG
            OshiLog.messaging.info("👁️ View-once message \(messageId.prefix(8))... marked as viewed, media wiped")
            #endif

            saveMessages()
            updateConversations()
        }
    }
    
    func deleteMessage(_ messageId: String) {
        messages.removeAll { $0.id == messageId }
        saveMessages()
        updateConversations()
    }

    // MARK: - Pin/Unpin Messages (1-on-1)

    func pinMessage(in conversationKey: String, messageId: String) {
        pinnedMessages[conversationKey] = messageId
        savePinnedMessages()
    }

    func unpinMessage(in conversationKey: String) {
        pinnedMessages.removeValue(forKey: conversationKey)
        savePinnedMessages()
    }

    private func savePinnedMessages() {
        if let data = try? JSONEncoder().encode(pinnedMessages) {
            UserDefaults.standard.set(data, forKey: "pinnedMessages")
        }
    }

    private func loadPinnedMessages() {
        if let data = UserDefaults.standard.data(forKey: "pinnedMessages"),
           let loaded = try? JSONDecoder().decode([String: String].self, from: data) {
            pinnedMessages = loaded
        }
    }

    // MARK: - Delete for Everyone (1-on-1)

    func deleteMessageForEveryone(_ messageId: String, recipientKey: String, identityManager: IdentityManager, meshManager: MeshNetworkManager) {
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            messages[index].isDeletedForEveryone = true
            messages[index].plaintextContent = nil
            messages[index].mediaAttachment = nil
            messages[index].originalMediaData = nil
            saveMessages()
            updateConversations()
        }
        let payload = MessageActionPayload(actionType: .deleteForEveryone, targetMessageId: messageId, newContent: nil, timestamp: Date())
        sendMessage(content: payload.toMessageString(), recipientAddress: recipientKey, identityManager: identityManager, meshManager: meshManager, skipLocalMessage: true)
    }

    // MARK: - Edit Message (1-on-1)

    func editMessage(_ messageId: String, newContent: String, recipientKey: String, identityManager: IdentityManager, meshManager: MeshNetworkManager) {
        if let index = messages.firstIndex(where: { $0.id == messageId }) {
            messages[index].editedAt = Date()
            messages[index].editedContent = newContent
            messages[index].plaintextContent = newContent
            saveMessages()
            updateConversations()
        }
        let payload = MessageActionPayload(actionType: .editMessage, targetMessageId: messageId, newContent: newContent, timestamp: Date())
        sendMessage(content: payload.toMessageString(), recipientAddress: recipientKey, identityManager: identityManager, meshManager: meshManager, skipLocalMessage: true)
    }

    // MARK: - Pin Sync (1-on-1)

    func pinMessageAndSync(in conversationKey: String, messageId: String, identityManager: IdentityManager, meshManager: MeshNetworkManager) {
        pinMessage(in: conversationKey, messageId: messageId)
        let payload = MessageActionPayload(actionType: .pinMessage, targetMessageId: messageId, newContent: nil, timestamp: Date())
        sendMessage(content: payload.toMessageString(), recipientAddress: conversationKey, identityManager: identityManager, meshManager: meshManager, skipLocalMessage: true)
    }

    func unpinMessageAndSync(in conversationKey: String, messageId: String, identityManager: IdentityManager, meshManager: MeshNetworkManager) {
        unpinMessage(in: conversationKey)
        let payload = MessageActionPayload(actionType: .unpinMessage, targetMessageId: messageId, newContent: nil, timestamp: Date())
        sendMessage(content: payload.toMessageString(), recipientAddress: conversationKey, identityManager: identityManager, meshManager: meshManager, skipLocalMessage: true)
    }

    // MARK: - Handle Incoming Message Actions

    func handleMessageAction(_ payload: MessageActionPayload, fromSenderKey: String) {
        switch payload.actionType {
        case .deleteForEveryone:
            if let index = messages.firstIndex(where: { $0.id == payload.targetMessageId }) {
                // Security: a sender may only delete-for-everyone a message they themselves
                // sent. Without this ownership check an attacker can remotely delete ANY
                // message (see security review C-MSG-4).
                guard messages[index].senderPublicKey == fromSenderKey else {
                    OshiLog.messaging.warning("⛔️ Rejected delete-for-everyone: sender does not own target message \(payload.targetMessageId.prefix(8))...")
                    return
                }
                messages[index].isDeletedForEveryone = true
                messages[index].plaintextContent = nil
                messages[index].mediaAttachment = nil
                messages[index].originalMediaData = nil
                saveMessages()
                updateConversations()
                OshiLog.messaging.info("🗑️ Message deleted for everyone: \(payload.targetMessageId.prefix(8))...")
            }
        case .editMessage:
            if let index = messages.firstIndex(where: { $0.id == payload.targetMessageId }) {
                // Security: a sender may only edit a message they themselves sent (C-MSG-4).
                guard messages[index].senderPublicKey == fromSenderKey else {
                    OshiLog.messaging.warning("⛔️ Rejected edit: sender does not own target message \(payload.targetMessageId.prefix(8))...")
                    return
                }
                messages[index].editedAt = payload.timestamp
                messages[index].editedContent = payload.newContent
                messages[index].plaintextContent = payload.newContent
                saveMessages()
                updateConversations()
                OshiLog.messaging.info("✏️ Message edited: \(payload.targetMessageId.prefix(8))...")
            }
        case .pinMessage:
            pinnedMessages[fromSenderKey] = payload.targetMessageId
            savePinnedMessages()
            OshiLog.messaging.info("📌 Message pinned by partner: \(payload.targetMessageId.prefix(8))...")
        case .unpinMessage:
            pinnedMessages.removeValue(forKey: fromSenderKey)
            savePinnedMessages()
            OshiLog.messaging.info("📌 Message unpinned by partner")
        }
    }
    
    func deleteConversation(with address: String) {
        messages.removeAll { message in
            message.senderPublicKey == address || message.recipientPublicKey == address
        }
        pendingConversations.removeValue(forKey: address)
        // __ANATOLE_1311_IOS__ (N18) a deleted thread stays deleted.
        ConversationPrefsStore.shared.forgetSaved(address)

        // 🔧 FIX (2026-06-04): also wipe the cached profile picture + presence
        // image for this contact. requestProfileFromContact() at line ~1206
        // early-returns when ContactPhotoManager OR ContactPresenceManager
        // still has a cached image — so if we don't clear them here, the
        // contact's NEXT message after deletion never triggers a fresh
        // profile request and the new conversation row shows stale data
        // (or an address fallback once the alias dict is cleared by the
        // caller). Clearing both makes requestProfileFromContact fire on
        // the first post-delete message and the round-trip re-syncs naturally.
        ContactPhotoManager.shared.deletePhoto(for: address)
        ContactPresenceManager.shared.updateProfileImage(publicKey: address, imageData: nil)

        saveMessages()
        updateConversations()
    }
    
    func clearAllMessages() {
        // 🛟 LAST-WIPE SAFETY NET: dump current state to a single rotating
        // backup before clearing. If a destructive flow runs without first
        // archiving under a known pubkey (e.g. user creates a new identity
        // while keychain was locked at boot), this is the last resort copy.
        // Overwritten on every wipe, so it only holds the most recent loss.
        if !messages.isEmpty {
            let trimmed = messages.map { msg -> SecureMessage in
                var t = msg
                t.mediaAttachment = nil
                return t
            }
            FileStorage.shared.save(trimmed, forKey: "messages_last_wipe")
            if !pinnedMessages.isEmpty {
                FileStorage.shared.save(pinnedMessages, forKey: "messages_last_wipe_pinned")
            }
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "messages_last_wipe_at")
            UserDefaults.standard.set(
                UserDefaults.standard.string(forKey: "publicKey") ?? "",
                forKey: "messages_last_wipe_pubkey"
            )
            OshiLog.messaging.info("🛟 Last-wipe backup written (\(messages.count) messages)")
        }

        messages.removeAll()
        conversations.removeAll()
        pendingConversations.removeAll()
        ConversationPrefsStore.shared.forgetAllSaved()
        saveMessages()
    }

    /// Restore messages from the last-wipe safety net. Returns true if data was restored.
    /// Use this for emergency recovery when a user accidentally creates a new identity
    /// or otherwise triggers an unintended wipe.
    func restoreFromLastWipe() -> Bool {
        guard let restored: [SecureMessage] = FileStorage.shared.load(forKey: "messages_last_wipe"),
              !restored.isEmpty else {
            OshiLog.messaging.info("📭 No last-wipe backup available")
            return false
        }
        messages = restored.map { message in
            var restoredMessage = message
            if let mediaType = message.mediaType, restoredMessage.mediaAttachment == nil {
                if mediaType == .audio {
                    restoredMessage.mediaAttachment = loadAudioAttachment(for: message.id)
                } else {
                    restoredMessage.mediaAttachment = loadMediaAttachment(for: message.id, type: mediaType)
                }
            }
            return restoredMessage
        }
        if let restoredPinned: [String: String] = FileStorage.shared.load(forKey: "messages_last_wipe_pinned") {
            pinnedMessages = restoredPinned
        }
        saveMessages()
        updateConversations()
        OshiLog.messaging.info("🛟 Restored \(messages.count) messages from last-wipe backup")
        return true
    }

    /// True when a last-wipe backup exists and contains messages — gate UI surfacing on this.
    var hasLastWipeBackup: Bool {
        guard let backup: [SecureMessage] = FileStorage.shared.load(forKey: "messages_last_wipe") else {
            return false
        }
        return !backup.isEmpty
    }

    // MARK: - Identity-Scoped Message Archive

    /// Archive current messages for a specific identity (call before clearing)
    func archiveMessagesForIdentity(_ publicKey: String) {
        guard !publicKey.isEmpty else { return }
        let safeKey = publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-")
        // __ANATOLE_1311_IOS__ (N18) saved contacts belong to this identity,
        // even when nobody has written yet (hence before the empty check).
        let savedContacts = ConversationPrefsStore.shared.saved.mapValues { $0.timeIntervalSince1970 }
        // UserDefaults, keyed by `safeKey`, so AccountDeletionView's sweep of
        // keys containing this identity removes it with the rest.
        UserDefaults.standard.set(savedContacts, forKey: "saved_contacts_identity_\(safeKey)")
        guard !messages.isEmpty else { return }
        let archiveKey = "messages_identity_\(safeKey)"

        // Trim media before archiving (media files already on disk by messageId)
        let trimmed = messages.map { msg -> SecureMessage in
            var t = msg
            t.mediaAttachment = nil
            return t
        }
        FileStorage.shared.save(trimmed, forKey: archiveKey)

        // Also archive pinned messages
        if !pinnedMessages.isEmpty {
            let pinnedKey = "pinned_identity_\(safeKey)"
            FileStorage.shared.save(pinnedMessages, forKey: pinnedKey)
        }

        OshiLog.messaging.info("📦 Archived \(messages.count) messages for identity \(publicKey.prefix(12))...")
    }

    /// Restore archived messages for a specific identity (call after switching)
    func restoreMessagesForIdentity(_ publicKey: String) {
        guard !publicKey.isEmpty else { return }
        let safeKey = publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-")
        if let savedContacts = UserDefaults.standard.dictionary(forKey: "saved_contacts_identity_\(safeKey)") as? [String: Double] {
            ConversationPrefsStore.shared.replaceSaved(savedContacts.mapValues { Date(timeIntervalSince1970: $0) })
            updateConversations()
        }
        let archiveKey = "messages_identity_\(safeKey)"

        if let restored: [SecureMessage] = FileStorage.shared.load(forKey: archiveKey) {
            // Restore media from disk
            messages = restored.map { message in
                var restoredMessage = message
                if let mediaType = message.mediaType, restoredMessage.mediaAttachment == nil {
                    if mediaType == .audio {
                        restoredMessage.mediaAttachment = loadAudioAttachment(for: message.id)
                    } else {
                        restoredMessage.mediaAttachment = loadMediaAttachment(for: message.id, type: mediaType)
                    }
                }
                return restoredMessage
            }

            // Restore pinned messages
            let pinnedKey = "pinned_identity_\(safeKey)"
            if let restoredPinned: [String: String] = FileStorage.shared.load(forKey: pinnedKey) {
                pinnedMessages = restoredPinned
            }

            updateConversations()
            OshiLog.messaging.info("📥 Restored \(messages.count) messages for identity \(publicKey.prefix(12))...")
        } else {
            OshiLog.messaging.info("📭 No archived messages for identity \(publicKey.prefix(12))...")
        }
    }
    
    private func sendDeliveryConfirmation(for message: SecureMessage) {}
    
    // MARK: - Audio Persistence

    private func saveAudioAttachment(_ audioData: Data, for messageId: String) -> Bool {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return false
        }
        
        let audioDirectory = documentsURL.appendingPathComponent("AudioMessages", isDirectory: true)
        
        // Create directory if needed
        if !fileManager.fileExists(atPath: audioDirectory.path) {
            try? fileManager.createDirectory(at: audioDirectory, withIntermediateDirectories: true)
        }
        
        let audioFile = audioDirectory.appendingPathComponent("\(messageId).m4a")
        
        do {
            // __MEDIA_AT_REST_2026_09_22__ Chiffré au repos (`MediaCipher`, FileStorage.swift).
            try MediaCipher.write(audioData, to: audioFile)
            OshiLog.messaging.info("💾 Saved audio for message: \(messageId) (\(audioData.count / 1024) KB)")
            return true
        } catch {
            OshiLog.messaging.info("❌ Failed to save audio: \(error)")
            return false
        }
    }

    private func loadAudioAttachment(for messageId: String) -> Data? {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return nil
        }
        
        let audioFile = documentsURL
            .appendingPathComponent("AudioMessages", isDirectory: true)
            .appendingPathComponent("\(messageId).m4a")
        
        guard fileManager.fileExists(atPath: audioFile.path) else {
            OshiLog.messaging.info("⚠️ Audio file not found for message: \(messageId)")
            return nil
        }
        
        do {
            let audioData = try MediaCipher.read(contentsOf: audioFile)  // clair hérité accepté
            OshiLog.messaging.info("✅ Loaded audio for message: \(messageId) (\(audioData.count / 1024) KB)")
            return audioData
        } catch {
            OshiLog.messaging.info("❌ Failed to load audio: \(error)")
            return nil
        }
    }

    private func deleteAudioAttachment(for messageId: String) {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }
        
        let audioFile = documentsURL
            .appendingPathComponent("AudioMessages", isDirectory: true)
            .appendingPathComponent("\(messageId).m4a")
        
        try? fileManager.removeItem(at: audioFile)
    }
    
    // MARK: - Generic Media Persistence (Images, Documents, Videos)
    
    private func saveMediaAttachment(_ mediaData: Data, for messageId: String, type: MediaManager.MediaType) -> Bool {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return false
        }
        
        let mediaDirectory = documentsURL.appendingPathComponent("MediaMessages", isDirectory: true)
        
        // Create directory if needed
        if !fileManager.fileExists(atPath: mediaDirectory.path) {
            try? fileManager.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
        }
        
        // Use appropriate extension based on type
        let fileExtension: String
        switch type {
        case .image: fileExtension = "jpg"
        // __GIF_2026_09_12__ Les deux switch (écriture ET lecture) doivent
        // donner la MÊME extension: le fichier est écrit en `<id>.<ext>` puis
        // relu par le même calcul. Un GIF rangé en .jpg se relirait très bien
        // ici, mais tout ce qui se fie à l'extension en aval le prendrait pour
        // une image fixe.
        case .gif: fileExtension = "gif"
        case .video: fileExtension = "mp4"
        case .document: fileExtension = "dat"
        case .audio: fileExtension = "m4a"
        case .contact: fileExtension = "vcf"
        }
        
        let mediaFile = mediaDirectory.appendingPathComponent("\(messageId).\(fileExtension)")
        
        do {
            // __MEDIA_AT_REST_2026_09_22__ Chiffré au repos (`MediaCipher`, FileStorage.swift).
            try MediaCipher.write(mediaData, to: mediaFile)
            OshiLog.messaging.info("💾 Saved \(type.rawValue) for message: \(messageId) (\(mediaData.count / 1024) KB)")
            return true
        } catch {
            OshiLog.messaging.info("❌ Failed to save \(type.rawValue): \(error)")
            return false
        }
    }
    
    private func loadMediaAttachment(for messageId: String, type: MediaManager.MediaType) -> Data? {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return nil
        }
        
        let mediaDirectory = documentsURL.appendingPathComponent("MediaMessages", isDirectory: true)
        
        // Try with appropriate extension
        let fileExtension: String
        switch type {
        case .image: fileExtension = "jpg"
        // __GIF_2026_09_12__ Les deux switch (écriture ET lecture) doivent
        // donner la MÊME extension: le fichier est écrit en `<id>.<ext>` puis
        // relu par le même calcul. Un GIF rangé en .jpg se relirait très bien
        // ici, mais tout ce qui se fie à l'extension en aval le prendrait pour
        // une image fixe.
        case .gif: fileExtension = "gif"
        case .video: fileExtension = "mp4"
        case .document: fileExtension = "dat"
        case .audio: fileExtension = "m4a"
        case .contact: fileExtension = "vcf"
        }
        
        let mediaFile = mediaDirectory.appendingPathComponent("\(messageId).\(fileExtension)")
        
        guard fileManager.fileExists(atPath: mediaFile.path) else {
            // Try without extension (legacy files)
            let legacyFile = mediaDirectory.appendingPathComponent(messageId)
            if fileManager.fileExists(atPath: legacyFile.path) {
                do {
                    let mediaData = try MediaCipher.read(contentsOf: legacyFile)
                    OshiLog.messaging.info("✅ Loaded legacy media for message: \(messageId) (\(mediaData.count / 1024) KB)")
                    return mediaData
                } catch {
                    return nil
                }
            }
            return nil
        }
        
        do {
            let mediaData = try MediaCipher.read(contentsOf: mediaFile)  // clair hérité accepté
            OshiLog.messaging.info("✅ Loaded \(type.rawValue) for message: \(messageId) (\(mediaData.count / 1024) KB)")
            return mediaData
        } catch {
            OshiLog.messaging.info("❌ Failed to load \(type.rawValue): \(error)")
            return nil
        }
    }
    
    private func deleteMediaAttachment(for messageId: String) {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }
        
        let mediaDirectory = documentsURL.appendingPathComponent("MediaMessages", isDirectory: true)
        
        // Delete all possible extensions
        let extensions = ["jpg", "gif", "mp4", "dat", "m4a", "vcf", ""]  // "gif": __MEDIA_AT_REST_2026_09_22__ (écrit depuis __GIF_2026_09_12__, jamais effacé)
        for ext in extensions {
            let fileName = ext.isEmpty ? messageId : "\(messageId).\(ext)"
            let mediaFile = mediaDirectory.appendingPathComponent(fileName)
            try? fileManager.removeItem(at: mediaFile)
        }
    }
    
    #if DEBUG
    /// __MEDIA_AT_REST_2026_09_22__ Pour `StorageSelfTest`: passe par les VRAIES
    /// fonctions privées d'écriture puis de relecture (disque chiffré → clair).
    func debugMediaStoreRoundTrip(_ data: Data, id: String, type: MediaManager.MediaType) -> Data? {
        let saved = (type == .audio) ? saveAudioAttachment(data, for: id) : saveMediaAttachment(data, for: id, type: type)
        guard saved else { return nil }
        return (type == .audio) ? loadAudioAttachment(for: id) : loadMediaAttachment(for: id, type: type)
    }
    #endif

    private func cleanupOldMediaFiles() {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }
        
        let mediaDirectory = documentsURL.appendingPathComponent("MediaMessages", isDirectory: true)
        
        guard let mediaFiles = try? fileManager.contentsOfDirectory(at: mediaDirectory, includingPropertiesForKeys: nil) else {
            return
        }
        
        let activeMessageIds = Set(messages.map { $0.id })
        var deletedCount = 0
        
        for mediaFile in mediaFiles {
            let messageId = mediaFile.deletingPathExtension().lastPathComponent
            
            if !activeMessageIds.contains(messageId) {
                try? fileManager.removeItem(at: mediaFile)
                deletedCount += 1
            }
        }
        
        if deletedCount > 0 {
            OshiLog.messaging.info("🧹 Cleaned up \(deletedCount) old media files")
        }
    }

    private func cleanupOldAudioFiles() {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }
        
        let audioDirectory = documentsURL.appendingPathComponent("AudioMessages", isDirectory: true)
        
        guard let audioFiles = try? fileManager.contentsOfDirectory(at: audioDirectory, includingPropertiesForKeys: nil) else {
            return
        }
        
        let activeMessageIds = Set(messages.map { $0.id })
        var deletedCount = 0
        
        for audioFile in audioFiles {
            let messageId = audioFile.deletingPathExtension().lastPathComponent
            
            if !activeMessageIds.contains(messageId) {
                try? fileManager.removeItem(at: audioFile)
                deletedCount += 1
            }
        }
        
        if deletedCount > 0 {
            OshiLog.messaging.info("🧹 Cleaned up \(deletedCount) old audio files")
        }
    }
    
    // Off-main persistence so re-encoding + writing the full history doesn't
    // block the UI on every message. Re-encoding the whole array + disk write
    // synchronously on the main thread (on send, on receive, on every receipt)
    // caused the "lag on send" and, for large histories, spiked memory/CPU
    // enough to crash. We snapshot on the caller thread (the array is
    // copy-on-write, so this is cheap) and persist on a serial background queue,
    // coalescing rapid successive saves down to the latest snapshot only.
    private let messageSaveQueue = DispatchQueue(label: "com.oshi.messages.save", qos: .utility)
    private let messageSaveLock = NSLock()
    private var pendingSaveSnapshot: [SecureMessage]?
    private var messageSaveRunning = false
    /// 🔧 PERF: message ids whose media file is already on disk, so persistMessages
    /// doesn't rewrite every attachment (incl. tens-of-MB videos) on EVERY save.
    /// Pre-seeded by loadMessages for media restored from disk. Touched only on
    /// the save queue + load, which don't overlap. NSLock-guarded for safety.
    private var persistedMediaIds = Set<String>()

    /// __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Fusionne un export déchiffré.
    ///
    /// Volontairement PAS `addMessage`: celui-ci rejoue les effets d'un message
    /// qui ARRIVE (mise à jour de profil, réactions, rotation de clé). Rejouer
    /// un historique ne doit rien déclencher — seulement le remettre en place.
    /// Dédoublonné par id: réimporter le même fichier ne duplique rien.
    /// - Returns: le nombre de messages réellement ajoutés.
    ///
    /// __EXPORT_V2_INTEROP_2026_09_22__ Ids comparés SANS casse: le même message
    /// (même msgId de relais) est souvent en minuscules sur Android/Desktop et en
    /// majuscules ici.
    @discardableResult
    func importMessages(_ imported: [SecureMessage]) -> Int {
        var existing = Set(messages.map { $0.id.lowercased() })
        let fresh = imported.filter { existing.insert($0.id.lowercased()).inserted }
        guard !fresh.isEmpty else { return 0 }
        messages.append(contentsOf: fresh)
        messages.sort { $0.timestamp < $1.timestamp }
        updateConversations()
        saveMessages()
        OshiLog.messaging.info("📥 Imported \(fresh.count) messages from encrypted export")
        return fresh.count
    }

    /// __EXPORT_V2_INTEROP_2026_09_22__ Octets d'un média 1:1 pour l'export,
    /// relus du disque par les MÊMES chemins que l'affichage (donc déchiffrés
    /// s'ils sont chiffrés au repos). Pure lecture disque: appelable hors main.
    func exportMediaBytes(for message: SecureMessage) -> Data? {
        guard let type = message.mediaType else { return nil }
        return type == .audio
            ? loadAudioAttachment(for: message.id)
            : loadMediaAttachment(for: message.id, type: type)
    }

    func saveMessages() {
        let snapshot = messages
        messageSaveLock.lock()
        pendingSaveSnapshot = snapshot
        let alreadyRunning = messageSaveRunning
        messageSaveRunning = true
        messageSaveLock.unlock()
        if alreadyRunning { return }   // a worker is already draining; it'll pick up the latest snapshot

        messageSaveQueue.async { [weak self] in
            guard let self = self else { return }
            while true {
                // __COALESCE_SAVES_2026_09_06__ Let a burst settle before writing.
                // Every save re-encodes the WHOLE store to disk; a receipt storm or
                // a mark-as-read pass used to write it once per mutation, which is
                // what the 2026-09-04 disk-writes resource exception on 1.0.41
                // measured (2.5 h window, footprint 253→305 MB). Only the latest
                // snapshot is ever written, so waiting loses nothing.
                Thread.sleep(forTimeInterval: 0.75)
                self.messageSaveLock.lock()
                guard let snap = self.pendingSaveSnapshot else {
                    self.messageSaveRunning = false
                    self.messageSaveLock.unlock()
                    return
                }
                self.pendingSaveSnapshot = nil
                self.messageSaveLock.unlock()
                self.persistMessages(snap)
            }
        }
    }

    /// Persist a snapshot of the message history. Media bytes stay on disk
    /// addressable by message id; the index file omits both mediaAttachment and
    /// originalMediaData (loadMessages re-attaches them, except for consumed
    /// view-once / deleted messages whose media must stay wiped). Runs on the
    /// background save queue.
    private func persistMessages(_ snapshot: [SecureMessage]) {
        // Persist media to disk ONCE. persistMessages runs on every receive /
        // call-end / group-update; re-writing every attachment each time
        // (including 17–37 MB videos) was the dominant disk-IO lag. Track ids
        // whose media is already on disk and skip them.
        var newlyPersisted = Set<String>()
        for message in snapshot {
            guard let mediaData = message.mediaAttachment, let mediaType = message.mediaType else { continue }
            messageSaveLock.lock(); let already = persistedMediaIds.contains(message.id); messageSaveLock.unlock()
            if already { continue }
            let ok = (mediaType == .audio)
                ? saveAudioAttachment(mediaData, for: message.id)
                : saveMediaAttachment(mediaData, for: message.id, type: mediaType)
            if ok { newlyPersisted.insert(message.id) }
        }
        if !newlyPersisted.isEmpty {
            messageSaveLock.lock(); persistedMediaIds.formUnion(newlyPersisted); messageSaveLock.unlock()
        }

        messageSaveLock.lock(); let onDisk = persistedMediaIds; messageSaveLock.unlock()

        let trimmedMessages = snapshot.map { message -> SecureMessage in
            var trimmed = message
            trimmed.mediaAttachment = nil
            trimmed.originalMediaData = nil
            // 🔧 CRASH/PERF: encryptedContent.ciphertext is the base64 of the FULL
            // encrypted media for media messages — that is what bloated the index
            // to ~209 MB (re-encoded on every save → memory pressure → NSMalloc
            // crashes). The media is on disk and loadMessages re-attaches it from
            // there; encryptedContent is never used to render a media message after
            // load. Drop the heavy ciphertext from the index ONLY when the media is
            // safely on disk, so nothing is ever lost. Keep signature/sender/ts.
            if message.mediaType != nil,
               onDisk.contains(message.id),
               !message.encryptedContent.ciphertext.isEmpty {
                trimmed.encryptedContent = EncryptedMessage(
                    ciphertext: "",
                    signature: message.encryptedContent.signature,
                    senderPublicKey: message.encryptedContent.senderPublicKey,
                    timestamp: message.encryptedContent.timestamp
                )
            }
            return trimmed
        }

        FileStorage.shared.save(trimmedMessages, forKey: "messages")
    }
    
    private func archiveOldMessages() {
        let oldMessages = Array(messages.prefix(messages.count - maxMessagesInMemory))
        
        // Delete all media for old messages
        for message in oldMessages {
            deleteAudioAttachment(for: message.id)
            deleteMediaAttachment(for: message.id)
        }
        
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        
        let archiveURL = documentsURL.appendingPathComponent("archived_messages.json")
        
        if let encoded = try? JSONEncoder().encode(oldMessages) {
            try? encoded.write(to: archiveURL)
            OshiLog.messaging.info("📦 Archived \(oldMessages.count) messages")
        }
        
        // Cleanup orphaned files
        cleanupOldAudioFiles()
        cleanupOldMediaFiles()
    }
    
    private func loadMessages() {
        // One-shot recovery: pull anything that the buggy archiveOldMessages()
        // path wrote to `archived_messages.json` so users who already lost
        // conversations to the 30-message cap get them back on first launch
        // after this fix. We dedupe by id when merging in the real load below.
        var archivedRecovered: [SecureMessage] = []
        let fileManager = FileManager.default
        if let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            let archiveURL = documentsURL.appendingPathComponent("archived_messages.json")
            if fileManager.fileExists(atPath: archiveURL.path),
               let data = try? Data(contentsOf: archiveURL),
               let decoded = try? JSONDecoder().decode([SecureMessage].self, from: data) {
                archivedRecovered = decoded
                OshiLog.messaging.info("🔁 Recovered \(decoded.count) messages from legacy archive")
            }
        }

        // ✅ Try FileStorage first
        if let loadedMessages: [SecureMessage] = FileStorage.shared.load(forKey: "messages") {
            // 🔧 LAUNCH-FREEZE FIX (2026-07-19): publish the message INDEX now,
            // WITHOUT reading media blobs. loadMessages() runs synchronously on the
            // main thread from init(), and the old `.map` here read EVERY stored
            // image/video/audio file (individual videos are 17–37 MB) from disk into
            // RAM before the first frame was drawn. That is invisible on a clean
            // Simulator (empty history) but a multi-second launch hang on a real
            // device with real history — the reported "freezes a few seconds after
            // opening". Media bytes are needed only when a chat bubble actually
            // renders them, not to render the conversation list, so we hand them off
            // to a background back-fill (backfillPersistedMedia below) that reads the
            // blobs off-main and merges them into `messages` a beat later.
            messages = loadedMessages
            
            // 🔧 FIX: Clean up any control payload that shouldn't have been stored
            //
            // __COUNTED_IMPLIES_DRAWN_2026_10_20__ C'était une liste de 5
            // préfixes écrite à la main — exactement la dérive que
            // `OSHIControlPayload` existe pour tuer — et il y manquait
            // `⌨️TYPING⌨️`, `🔧ACTION🔧`, `📢GROUP_UPDATE📢`, `📞CALL_SIGNAL📞`,
            // `__SESSION_RESET__` et le JSON `member_sync`. Un ping de frappe
            // rangé par un build antérieur survivait donc à chaque redémarrage
            // en comptant comme non lu, invisible et donc ineffaçable autrement
            // qu'en ouvrant le fil. Le catalogue décide, et cette passe efface
            // l'historique que l'ancien trou a laissé derrière lui.
            let beforeCount = messages.count
            messages = messages.filter { message in
                guard let content = message.plaintextContent else { return true }
                if content.hasPrefix(OSHIControlPayload.profileUpdatePrefix) { return false }
                return !OSHIControlPayload.isSilent(content)
            }
            if messages.count != beforeCount {
                // __SAY_WHAT_IT_ACTUALLY_CLEANS_2026_10_21__ Ce filtre ne jette
                // plus seulement les mises à jour de profil: il jette TOUT
                // payload silencieux du catalogue, et c'est le but — un
                // ⌨️TYPING⌨️ rangé comme message est exactement ce qui fabriquait
                // le badge fantôme. Le message disait « profile update » et
                // faisait donc passer un nettoyage massif pour un détail: sur un
                // vrai appareil cette passe a retiré près de la moitié du
                // fichier, et personne lisant ce journal n'aurait compris
                // pourquoi.
                OshiLog.messaging.info("🧹 Cleaned up \(beforeCount - messages.count) silent control payload(s) from storage")
                saveMessages()  // Save cleaned messages
            }

            // Merge in archived messages (one-shot recovery — see top of
            // loadMessages). Dedupe by id; sort chronologically. Once merged
            // and re-saved through saveMessages(), the archive file is no
            // longer load-bearing — it gets removed below to avoid future
            // re-merges polluting the live store.
            if !archivedRecovered.isEmpty {
                let existingIds = Set(messages.map { $0.id })
                let newOnes = archivedRecovered.filter { !existingIds.contains($0.id) }
                if !newOnes.isEmpty {
                    messages.append(contentsOf: newOnes)
                    messages.sort { $0.timestamp < $1.timestamp }
                    saveMessages()
                    OshiLog.messaging.info("🔁 Merged \(newOnes.count) archived messages back into live store")
                }
                if let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
                    try? fileManager.removeItem(at: documentsURL.appendingPathComponent("archived_messages.json"))
                }
            }

            OshiLog.messaging.info("✅ Loaded \(messages.count) messages from FileStorage with media restored")
        }
        // ✅ Fallback: migrate from old UserDefaults storage
        else if let data = UserDefaults.standard.data(forKey: "messages"),
                let decoded = try? JSONDecoder().decode([SecureMessage].self, from: data) {
            
            OshiLog.messaging.info("🔄 Migrating messages from UserDefaults to FileStorage...")
            
            // Restore all media types
            messages = decoded.map { message in
                var restoredMessage = message
                
                if let mediaType = message.mediaType,
                   message.mediaAttachment == nil {
                    if mediaType == .audio {
                        if let audioData = loadAudioAttachment(for: message.id) {
                            restoredMessage.mediaAttachment = audioData
                            OshiLog.messaging.info("✅ Restored audio for message: \(message.id)")
                        }
                    } else {
                        if let mediaData = loadMediaAttachment(for: message.id, type: mediaType) {
                            restoredMessage.mediaAttachment = mediaData
                            OshiLog.messaging.info("✅ Restored \(mediaType.rawValue) for message: \(message.id)")
                        }
                    }
                }
                
                return restoredMessage
            }
            
            // Save to FileStorage
            FileStorage.shared.save(messages, forKey: "messages")
            
            // Remove from UserDefaults
            UserDefaults.standard.removeObject(forKey: "messages")
            
            OshiLog.messaging.info("✅ Migrated \(messages.count) messages to FileStorage")
        }

        recoverStuckOutgoingMessages()
        updateConversations()

        // 🔧 LAUNCH-FREEZE FIX companion: now that the media-less index is live and
        // the conversation list can render, read the persisted media blobs OFF the
        // main thread and merge them back in. Nothing above blocked on disk media.
        backfillPersistedMedia()
    }

    /// Reads persisted media attachments from disk on a background task and merges
    /// them into `messages` on the main actor, so app launch never blocks on
    /// multi-MB media reads (see the LAUNCH-FREEZE FIX in loadMessages). Only
    /// touches messages whose media is still nil; idempotent and safe to no-op.
    private func backfillPersistedMedia() {
        // Snapshot (on the main thread) which messages still need media, NEWEST
        // FIRST — the byte budget below spends itself on what the user is about to
        // scroll into, not on a year-old photo.
        let needing: [(id: String, type: MediaManager.MediaType)] = messages
            .filter { $0.mediaType != nil && $0.mediaAttachment == nil }
            // __VIEW_ONCE_DISK_2026_09_29__ Un « vue unique » déjà ouvert ne se
            // réhydrate jamais, même si un fichier ancien traîne encore.
            .filter { !($0.isViewOnce && $0.hasBeenViewed) }
            .sorted { $0.timestamp > $1.timestamp }
            .compactMap { m in m.mediaType.map { (m.id, $0) } }
        guard !needing.isEmpty else { return }

        Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            // Pure disk reads (loadMediaAttachment/loadAudioAttachment touch no
            // shared mutable state), safe off-main.
            //
            // 🔧 RAM FIX: hydrating EVERY persisted blob pulled the whole media
            // history into `loaded` at once. With 200 MB transfers now allowed that
            // is a jetsam kill, and the resident copy is pure waste — the bubbles
            // re-read from disk lazily anyway. Stop at a 48 MB budget; anything
            // beyond it stays on disk.
            let budget = 48 * 1024 * 1024
            var spent = 0
            var loaded: [String: Data] = [:]
            for item in needing {
                if spent >= budget { break }
                let data = (item.type == .audio)
                    ? self.loadAudioAttachment(for: item.id)
                    : self.loadMediaAttachment(for: item.id, type: item.type)
                if let data {
                    loaded[item.id] = data
                    spent += data.count
                }
            }
            guard !loaded.isEmpty else { return }

            await MainActor.run {
                // 🔧 PERF FIX: mutate a LOCAL copy and assign once. `@Published` has
                // no `_modify` accessor, so every `self.messages[i].x = …` copied the
                // entire array and fired objectWillChange — O(N²) copies plus two
                // SwiftUI invalidations per media message, on the main thread, right
                // after launch.
                var updated = self.messages
                for i in updated.indices {
                    let id = updated[i].id
                    guard updated[i].mediaAttachment == nil, let data = loaded[id] else { continue }
                    updated[i].mediaAttachment = data
                    self.persistedMediaIds.insert(id)   // already on disk — don't re-save
                    // Re-attach the original-quality copy, skipping consumed
                    // view-once / deleted messages so wiped media isn't resurrected.
                    if updated[i].originalMediaData == nil,
                       !updated[i].hasBeenViewed, !updated[i].isDeletedForEveryone {
                        updated[i].originalMediaData = data
                    }
                }
                self.messages = updated
                self.updateConversations()
            }
        }
    }

    /// Startup recovery: any of OUR outgoing messages still marked `.pending`
    /// after a relaunch was interrupted mid-send in a previous session (the app
    /// was suspended/killed during the handshake, media upload, or relay POST).
    /// It will never self-heal — nothing re-drives it and `.pending` renders a
    /// spinner with no retry affordance — so it is silently lost. Transition
    /// these to `.failed`, which surfaces the tap-to-retry UI and lets the user
    /// (or the reachability-driven retry queue) resend. Incoming messages and
    /// already-terminal states are untouched.
    private func recoverStuckOutgoingMessages() {
        guard let myKey = MessageManager.sharedIdentityManager?.publicKey, !myKey.isEmpty else { return }
        var changed = 0
        for i in messages.indices {
            guard messages[i].deliveryStatus == .pending,
                  messages[i].senderPublicKey == myKey else { continue }
            messages[i].deliveryStatus = .failed
            changed += 1
        }
        if changed > 0 {
            OshiLog.messaging.info("🩹 Recovered \(changed) stuck .pending outgoing message(s) → .failed (tap to retry)")
            saveMessages()
            // 🚧 The user is about to see red "tap to retry" bubbles. This runs on
            // launch, i.e. exactly when SceneDelegate evaluates the rating ask —
            // stamp friction so that evaluation refuses.
            Task { @MainActor in RateAppManager.shared.recordFriction(reason: "stuck-messages-recovered") }
        }
        // Auto-resend rather than leaving it to the user: enqueue every failed
        // outgoing message (the ones just recovered plus any pre-existing) and
        // kick the drain. If the network isn't up yet, retryFailedMessages() is a
        // no-op and the reachability/foreground triggers pick it up later.
        enqueueFailedOutgoingForRetry()
        retryFailedMessages()
    }

    private func updateConversations() {
        guard let identityManager = MessageManager.sharedIdentityManager else {
            OshiLog.messaging.info("⏸️ Wallet manager not available yet, will update conversations later")
            return
        }
        
        OshiLog.messaging.info("🔄 Updating conversations with \(messages.count) messages...")
        
        var conversationDict: [String: Conversation] = [:]
        // Accumulate unread counts in ONE pass. The previous code recomputed
        // `messages.filter{…}.count` for every conversation on every message →
        // O(n²) over the whole history, which lagged the UI on send and could
        // spike CPU/memory enough to crash on large histories.
        var unreadCounts: [String: Int] = [:]
        // __IDENTITY_CANONICAL_2026_08_15__ Both sides of every comparison below go
        // through the canonical spelling, so a contact whose key reached us as
        // base64url on one transport and standard base64 on another is ONE
        // conversation, not two — and history stored before this build heals on the
        // next pass without a migration.
        let myKey = MessageManager.canonicalIdentity(identityManager.publicKey)

        for message in messages {
            let senderKey = MessageManager.canonicalIdentity(message.senderPublicKey)
            let recipientKey = MessageManager.canonicalIdentity(message.recipientPublicKey)
            // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ A channel broadcast belongs to
            // the channel, whoever spoke.
            //
            // Checked FIRST, above the sender/recipient choice below, because that
            // choice would file it under the speaker: a broadcast's sender is never
            // us, so `otherPartyKey` became the node and three people talking on
            // LongFast produced three one-to-one threads that each looked like a
            // direct message — with the channel itself appearing nowhere.
            let recipientIsChannel = LoRaChannelThread.isChannelKey(recipientKey)
            let otherPartyKey: String
            let otherPartyAddress: String

            if recipientIsChannel {
                otherPartyKey = recipientKey
                otherPartyAddress = LoRaChannelThread.displayName(recipientKey)
            } else if senderKey == myKey {
                otherPartyKey = recipientKey
                otherPartyAddress = message.recipientAddress
            } else {
                otherPartyKey = senderKey
                otherPartyAddress = message.senderAddress
            }

            if otherPartyKey == myKey {
                continue
            }

            // ✅ Skip blocked contacts - don't show in conversation list
            if BlockedContactsManager.shared.isBlocked(otherPartyKey) {
                continue
            }

            // O(1) unread tally: a message I received from this party that I
            // haven't read yet. (Same predicate as the old per-message filter.)
            //
            // __COUNTED_IMPLIES_DRAWN_2026_10_20__ …et qui peut être AFFICHÉ.
            // Sans ce dernier test, tout payload de contrôle rangé par erreur
            // dans le fil produisait un badge devant une conversation vide:
            // compté ici, jeté par `ChatView.computeFilteredMessages`. C'est
            // arrivé pour `⌨️TYPING⌨️` (voir `handleIncomingTypingPing`), et le
            // prédicat partagé fait que ça ne peut plus arriver pour un autre.
            //
            // L'ordre des tests est délibéré: le catalogue est consulté EN
            // DERNIER, donc seulement pour un message reçu et non lu — une
            // poignée de messages, pas tout l'historique, qui est parcouru ici à
            // chaque mise à jour (voir la note O(n²) en tête de fonction).
            // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ `recipient == me` is how this
            // asks "did I receive it", and a channel message is addressed to the
            // ROOM, so every channel would sit at zero unread for ever. Counted by
            // the room instead — and still only when someone ELSE spoke, which the
            // old predicate got for free (our own messages name the peer as
            // recipient) and this one has to say out loud.
            let countsAsReceived = recipientIsChannel
                ? senderKey != myKey
                : recipientKey == myKey
            if !message.isRead,
               countsAsReceived,
               // __ADVANCED_CHAT_PRIVACY_2026_11_26__ `countsAsUnread` = not hidden
               // AND not a quiet notice (the privacy switch row is drawn, not counted).
               message.plaintextContent.map(OSHIControlPayload.countsAsUnread) ?? true {
                unreadCounts[otherPartyKey, default: 0] += 1
            }

            // 🔧 FIX: Skip system protocol messages for lastMessage display
            let isProfileMessage = message.plaintextContent?.hasPrefix("📸PROFILE_UPDATE📸") == true ||
                                   message.plaintextContent?.hasPrefix("📸PROFILE_REQUEST📸") == true ||
                                   message.plaintextContent?.hasPrefix("🔗LINKS_VISIBILITY🔗") == true ||
                                   message.plaintextContent?.hasPrefix("🔧ACTION🔧") == true ||
                                   message.plaintextContent?.hasPrefix("📬DELIVERY_RECEIPT📬") == true ||
                                   message.plaintextContent?.hasPrefix("📖READ_RECEIPT📖") == true ||
                                   message.plaintextContent?.hasPrefix("⌨️TYPING⌨️") == true ||
                                   message.plaintextContent?.hasPrefix("✍️TYPING✍️") == true

            if let existing = conversationDict[otherPartyKey] {
                // Only update lastMessage with a newer, non-profile message.
                if !isProfileMessage && message.timestamp > existing.lastMessage.timestamp {
                    var conversation = existing
                    conversation.lastMessage = message
                    conversationDict[otherPartyKey] = conversation
                }
            } else if !isProfileMessage {
                // Don't create a conversation whose only/first message is a
                // system/profile message.
                conversationDict[otherPartyKey] = Conversation(
                    participantAddress: otherPartyAddress,
                    participantPublicKey: otherPartyKey,
                    lastMessage: message,
                    unreadCount: 0
                )
            }
        }

        // Apply accumulated unread counts to the conversations that exist.
        for (key, count) in unreadCounts {
            if var conversation = conversationDict[key] {
                conversation.unreadCount = count
                conversationDict[key] = conversation
            }
        }

        // __ANATOLE_1311_IOS__ (N18) Saved contacts survive a relaunch.
        // `pendingConversations` is memory only, so a contact added by QR, key
        // or card used to disappear from the list at the next launch until a
        // message was exchanged. Rebuild the missing ones from the saved set.
        let pendingCanonical = Set(pendingConversations.keys.map(MessageManager.canonicalIdentity))
        for (savedKey, savedAt) in ConversationPrefsStore.shared.saved {
            let key = MessageManager.canonicalIdentity(savedKey)
            guard key != myKey, conversationDict[key] == nil, !pendingCanonical.contains(key) else { continue }
            pendingConversations[key] = MessageManager.savedContactPlaceholder(
                publicKey: key, identityManager: identityManager, date: savedAt)
        }

        // Merge in pending conversations (QR-scanned contacts with no messages yet).
        // If a real conversation now exists for a pending key, remove it from pending.
        for (key, pendingConv) in pendingConversations {
            if conversationDict[key] != nil {
                // Real messages exist now — no longer pending
                pendingConversations.removeValue(forKey: key)
            } else if !BlockedContactsManager.shared.isBlocked(key) {
                // Still no messages — keep the pending conversation visible
                conversationDict[key] = pendingConv
            }
        }

        // 🔴 FLICKER FIX (message view): publish ONLY on an actual change.
        // `conversations` is @Published, so an unconditional assignment fires
        // objectWillChange on EVERY call — and updateConversations() is invoked by
        // ~25 event paths, several of which recur while the list/chat is open:
        // live-location updates (updateExistingLocationMessage), multi-device sync
        // merges, block/unblock, and profile/presence/receipt system traffic. Each
        // one recomputes an IDENTICAL list here, and every spurious publish re-ran
        // MessagesListView.body — re-installing .navigationTitle + .searchable, which
        // reads as the "Messages" large title blinking (same failure mode documented
        // for mesh-availability at checkMeshAvailability :1791) — plus the
        // ChatsTabView segment bar (which reads messageManager.conversations for its
        // unread badge). Conversation is Equatable, so compare before assigning.
        // Mirrors the mesh-availability guard in checkMeshAvailability / performHealthCheck.
        let rebuiltConversations = conversationDict.values.sorted { $0.lastMessage.timestamp > $1.lastMessage.timestamp }
        if rebuiltConversations != conversations {
            conversations = rebuiltConversations
        }

        OshiLog.messaging.info("✅ Updated \(conversations.count) conversations")

        // 🔧 2026-06-10: refresh the App Group snapshot the NSE reads when
        // each push arrives so banners show the sender's name instead of
        // generic "OSHI". Conversation doesn't carry alias/displayName
        // directly — pull them from the `contactAliases` UserDefaults dict
        // the app already maintains for name overrides.
        // ⚠️ `contactAliases` is persisted as JSON-encoded Data by the @AppStorage
        // UI layer (see IdentityManager.migrateAliases / SharePickerView), NOT as a
        // plist dictionary. Reading it with `.dictionary(forKey:)` returns nil, which
        // silently stripped every alias from the snapshot — so the Share Extension's
        // "Send to…" list fell back to raw public keys. Decode it the same way all
        // the other readers do.
        let aliases: [String: String] = {
            guard let data = UserDefaults.standard.data(forKey: "contactAliases"),
                  let decoded = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
            return decoded
        }()
        // 🔔 2026-08-18: the snapshot now also carries WHEN each conversation
        // last moved and the contact's profile photo, because OSHIShare's
        // "Send to…" list needs both. Recency in particular cannot be inferred
        // over there: `conversations` is already sorted newest-first here, but
        // an array order is not a promise — the extension used to re-sort
        // alphabetically and threw it away. Sending the timestamp makes the
        // ordering explicit instead of an accident of serialisation.
        let presence = ContactPresenceManager.shared
        let snapshot = conversations.map { conv -> (publicKey: String, alias: String?, displayName: String?, firstName: String?, lastMessageAt: Date?, avatar: Data?) in
            let alias = aliases[conv.participantPublicKey]
            return (publicKey: conv.participantPublicKey,
                    alias: alias,
                    displayName: alias,
                    firstName: nil,
                    lastMessageAt: conv.lastMessage.timestamp,
                    avatar: presence.presenceMap[conv.participantPublicKey]?.profileImageData)
        }
        AppGroupBridge.writeContactsSnapshot(snapshot)
        AppGroupBridge.writeIdentitySnapshot(publicKey: myKey)
    }
}

// MARK: - Supporting Types

struct Conversation: Identifiable, Equatable {
    // ✅ Use participantPublicKey as stable ID to prevent navigation issues
    var id: String { participantPublicKey }
    let participantAddress: String
    let participantPublicKey: String
    var lastMessage: SecureMessage
    var unreadCount: Int
    
    static func == (lhs: Conversation, rhs: Conversation) -> Bool {
        lhs.participantPublicKey == rhs.participantPublicKey &&
        lhs.lastMessage.id == rhs.lastMessage.id &&
        lhs.unreadCount == rhs.unreadCount
    }
}

enum DeliveryMethod: String, Codable {
    case mesh = "Direct"
    case ipfs = "Cloud"
    /// Delivered over a paired Meshtastic LoRa radio. Kilometres of range with
    /// no network at all, so it is worth telling the user apart from the
    /// Bluetooth/Wi-Fi mesh — the two have nothing in common but "offline".
    ///
    /// PURELY LOCAL BOOKKEEPING. `meshWireSafe()` strips `deliveryMethod`
    /// before anything goes on the air precisely so this case can never reach
    /// an older client: `decodeIfPresent` on an enum THROWS on an unknown raw
    /// value, which would drop the whole message, not just the field. Both ends
    /// stamp it from what they know locally (the radio it came out of / went
    /// into), which is the only side that actually knows.
    case lora = "LoRa"

    /// __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ A raw Meshtastic
    /// text packet: carried by the radio, NOT by OSHI, and NOT authenticated.
    ///
    /// [lora] means an OSHI envelope that happened to travel by radio — the
    /// ratchet proved who sent it, exactly as it would over the internet. This
    /// is the other lane: portnum 1, plaintext, on a channel whose key is
    /// public, with a `from` field anyone in radio range can forge — including
    /// into a node number this device has legitimately bound to a contact.
    ///
    /// The distinction has to be STORED rather than derived when drawing,
    /// because the only moment we know it is when the packet arrives. It is
    /// what allows such a message to be filed in a contact's thread at all
    /// without the UI claiming something the ratchet never proved.
    ///
    /// Same local-only bookkeeping as [lora]: `meshWireSafe()` strips
    /// `deliveryMethod` before anything goes on the air, so this case cannot
    /// reach an older client and cannot make `decodeIfPresent` throw there.
    case loraPlaintext = "LoRaPlaintext"

    case pending = "Sending..."
}

struct SecureMessage: Identifiable, Codable, Equatable {
    let id: String
    let senderAddress: String
    let recipientAddress: String
    var encryptedContent: EncryptedMessage  // var: index persist strips the heavy media ciphertext (re-attached from disk on load)
    var timestamp: Date  // 📍 var for live location updates
    var isRead: Bool
    var deliveryStatus: DeliveryStatus
    
    var senderPublicKey: String = ""
    var recipientPublicKey: String = ""
    var plaintextContent: String?
    var mediaAttachment: Data?
    var deliveryMethod: DeliveryMethod? = nil
    var mediaType: MediaManager.MediaType? = nil
    var originalMediaData: Data? = nil
    var mediaFileName: String? = nil  // ✅ Original filename for documents
    var isViewOnce: Bool = false      // ✅ NEW: Ephemeral media - view once then delete
    var hasBeenViewed: Bool = false   // ✅ NEW: Track if view-once media was viewed
    var editedAt: Date? = nil
    var editedContent: String? = nil
    var isDeletedForEveryone: Bool = false
    // Android-parity: optional id of the message this one replies to (for scroll-to-original + cross-platform).
    var replyToId: String? = nil
    // __VIDEO_NOTE_2026_09_24__ Round video message (docs/VIDEO_NOTE_SPEC.md §2.F).
    // Optional and nil on every other message: nil is not encoded, so the JSON of
    // all existing traffic and of `messages.json` is unchanged byte for byte.
    var videoNote: Bool? = nil
    var durationMs: Int? = nil

    enum CodingKeys: String, CodingKey {
        case id, senderAddress, recipientAddress, encryptedContent, timestamp
        case isRead, deliveryStatus, senderPublicKey, recipientPublicKey
        case plaintextContent, mediaAttachment, deliveryMethod, mediaType, originalMediaData
        case mediaFileName, isViewOnce, hasBeenViewed
        case editedAt, editedContent, isDeletedForEveryone
        case replyToId
        case videoNote, durationMs
    }
    
    // ✅ Custom decoder for backward compatibility with old messages
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        
        id = try container.decode(String.self, forKey: .id)
        senderAddress = try container.decode(String.self, forKey: .senderAddress)
        recipientAddress = try container.decode(String.self, forKey: .recipientAddress)
        encryptedContent = try container.decode(EncryptedMessage.self, forKey: .encryptedContent)
        timestamp = try container.decode(Date.self, forKey: .timestamp)
        isRead = try container.decode(Bool.self, forKey: .isRead)
        deliveryStatus = try container.decode(DeliveryStatus.self, forKey: .deliveryStatus)
        
        senderPublicKey = try container.decodeIfPresent(String.self, forKey: .senderPublicKey) ?? ""
        recipientPublicKey = try container.decodeIfPresent(String.self, forKey: .recipientPublicKey) ?? ""
        plaintextContent = try container.decodeIfPresent(String.self, forKey: .plaintextContent)
        mediaAttachment = try container.decodeIfPresent(Data.self, forKey: .mediaAttachment)
        // `decodeIfPresent` returns nil only when the KEY is missing. A key that
        // is present carrying a raw value this build has never heard of THROWS,
        // and because this is the whole message's decoder, the throw discards
        // the message entirely — text, media and all — with no trace beyond a
        // failed decode. That is a silent-message-loss bug waiting for the first
        // peer running a newer build or a different platform, and adding
        // `.lora` is exactly the kind of change that arms it.
        //
        // An unrecognised transport label is worth nothing; the message is worth
        // everything. Degrade the field to nil and keep the message.
        deliveryMethod = (try? container.decodeIfPresent(DeliveryMethod.self, forKey: .deliveryMethod)) ?? nil
        // __TOLERANT_MEDIA_TYPE_2026_09_12__ Same reasoning as `deliveryMethod`
        // directly above, and for the same reason it was fixed there: an
        // unrecognised raw value here THROWS out of the whole-message decoder.
        // `messages.json` is decoded as one array, so a single message carrying a
        // media kind this build doesn't know (a newer peer, the other platform, or
        // the `archived_messages.json` recovery merge) makes the ENTIRE array fail
        // to decode. `loadMessages` then leaves `messages` empty and the next
        // `saveMessages()` writes that emptiness back over the file — the user's
        // whole history, gone, with nothing louder than a failed decode.
        //
        // An unrecognised media label is worth nothing; the message is worth
        // everything. Degrade the field to nil and keep the message.
        mediaType = (try? container.decodeIfPresent(MediaManager.MediaType.self, forKey: .mediaType)) ?? nil
        originalMediaData = try container.decodeIfPresent(Data.self, forKey: .originalMediaData)
        mediaFileName = try container.decodeIfPresent(String.self, forKey: .mediaFileName)
        
        // ✅ Default to false for old messages that don't have these fields
        isViewOnce = try container.decodeIfPresent(Bool.self, forKey: .isViewOnce) ?? false
        hasBeenViewed = try container.decodeIfPresent(Bool.self, forKey: .hasBeenViewed) ?? false
        editedAt = try container.decodeIfPresent(Date.self, forKey: .editedAt)
        editedContent = try container.decodeIfPresent(String.self, forKey: .editedContent)
        isDeletedForEveryone = try container.decodeIfPresent(Bool.self, forKey: .isDeletedForEveryone) ?? false
        replyToId = try container.decodeIfPresent(String.self, forKey: .replyToId)
        // __VIDEO_NOTE_2026_09_24__ Tolerant: a malformed value degrades to a
        // normal video, it never throws the message (or the whole store) away.
        videoNote = (try? container.decodeIfPresent(Bool.self, forKey: .videoNote)) ?? nil
        durationMs = VideoNoteMetadata.sanitize((try? container.decodeIfPresent(Int.self, forKey: .durationMs)) ?? nil)
    }

    // ✅ Standard initializer
    init(id: String, senderAddress: String, recipientAddress: String, encryptedContent: EncryptedMessage, timestamp: Date, isRead: Bool, deliveryStatus: DeliveryStatus, senderPublicKey: String = "", recipientPublicKey: String = "", plaintextContent: String? = nil, mediaAttachment: Data? = nil, deliveryMethod: DeliveryMethod? = nil, mediaType: MediaManager.MediaType? = nil, originalMediaData: Data? = nil, mediaFileName: String? = nil, isViewOnce: Bool = false, hasBeenViewed: Bool = false, editedAt: Date? = nil, editedContent: String? = nil, isDeletedForEveryone: Bool = false, replyToId: String? = nil) {
        self.id = id
        self.senderAddress = senderAddress
        self.recipientAddress = recipientAddress
        self.encryptedContent = encryptedContent
        self.timestamp = timestamp
        self.isRead = isRead
        self.deliveryStatus = deliveryStatus
        self.senderPublicKey = senderPublicKey
        self.recipientPublicKey = recipientPublicKey
        self.plaintextContent = plaintextContent
        self.mediaAttachment = mediaAttachment
        self.deliveryMethod = deliveryMethod
        self.mediaType = mediaType
        self.originalMediaData = originalMediaData
        self.mediaFileName = mediaFileName
        self.isViewOnce = isViewOnce
        self.hasBeenViewed = hasBeenViewed
        self.editedAt = editedAt
        self.editedContent = editedContent
        self.isDeletedForEveryone = isDeletedForEveryone
        self.replyToId = replyToId
    }

    static func == (lhs: SecureMessage, rhs: SecureMessage) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - Message Action Payload (Delete/Edit/Pin sync for 1-on-1)

struct MessageActionPayload: Codable {
    static let messagePrefix = "\u{1F527}ACTION\u{1F527}"

    enum ActionType: String, Codable {
        case deleteForEveryone
        case editMessage
        case pinMessage
        case unpinMessage
    }

    let actionType: ActionType
    let targetMessageId: String
    let newContent: String?
    let timestamp: Date

    static func isActionMessage(_ text: String) -> Bool {
        text.hasPrefix(messagePrefix)
    }

    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else { return "" }
        return "\(Self.messagePrefix)\(json)"
    }

    static func fromMessageString(_ text: String) -> MessageActionPayload? {
        guard text.hasPrefix(messagePrefix) else { return nil }
        let json = String(text.dropFirst(messagePrefix.count))
        guard let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(MessageActionPayload.self, from: data)
    }
}

enum DeliveryStatus: String, Codable {
    case pending = "pending"
    case sent = "sent"
    case delivered = "delivered"
    /// 🔧 (2026-08-08) READ IS NOW A STATE, not a colour.
    ///
    /// It used to be a separate `isRead` boolean that only re-tinted the SAME
    /// double tick. Two very different facts — "their device has it" and "a
    /// person opened it" — wore the same symbol, so a reader could not tell
    /// them apart, and the ordering machinery below could not reason about read
    /// at all.
    case read = "read"
    case failed = "failed"

    /// DECODES TOLERANTLY. An older build receiving "read" would otherwise throw
    /// on the whole message and discard it — this codebase has already lost
    /// messages exactly that way when `deliveryMethod` gained a case. Unknown
    /// values fall back to `.delivered`, which is the closest true statement.
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = DeliveryStatus(rawValue: raw) ?? .delivered
    }

    /// Monotonic ordering used by the delivery-receipt state machine.
    /// pending < sent < delivered < read. `.failed` is terminal (rank -1) — a
    /// receipt must never resurrect a failed message.
    var rank: Int {
        switch self {
        case .pending: return 0
        case .sent: return 1
        case .delivered: return 2
        case .read: return 3
        case .failed: return -1
        }
    }

    /// Returns the status advanced to `target` iff that is a strictly forward
    /// move AND self is not terminal; otherwise returns self unchanged. This is
    /// the single source of truth for M2/M3 receipt upgrades — never downgrade,
    /// never un-fail. Pure + deterministic → unit-testable (ReceiptStateMachineTests).
    func advanced(to target: DeliveryStatus) -> DeliveryStatus {
        guard self != .failed, target.rank > rank else { return self }
        return target
    }
}
