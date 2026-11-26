//
//  OSHIMailClient.swift
//  OSHI
//
//  Client for OSHI Mail — a zero-access mailbox at @mail.oshi-messenger.com.
//
//  There is no mail password and no IMAP account to configure. The mailbox is owned by
//  this device's OSHI identity: every request is Ed25519-signed through
//  `IdentityManager.signHTTPRequest`, exactly like the `/v2/*` and `/api/web/*` routes,
//  so "create and connect to your email" is a signature, not a credential.
//
//  TWO KEYS, DIFFERENT JOBS — the distinction is the whole security model:
//
//    • The IDENTITY key (Ed25519, in `IdentityManager`) proves WHO is asking. The server
//      knows its public half; that is what authorises a request.
//    • The MAIL key (X25519, created here) is what mail is SEALED to. The server holds
//      only its public half, which is enough to encrypt an arriving message and not
//      enough to read one back. The private half never leaves this device.
//
//  So the server can write your mailbox and cannot read it. The consequence to design
//  around: a lost mail private key is lost mail, permanently — there is no recovery path
//  on the server, by construction. See `mailPrivateKey` for how it is stored.
//
//  The server half is /opt/oshi-mail on the VPS (crypto_mail.js seals, lmtp.js delivers).
//

import Foundation
import CryptoKit

// MARK: - Models

struct OSHIMailAccount: Codable {
    let address: String
    let localpart: String
    let aliases: [String]
    let aliasLimit: Int
    let createdAt: String
    let storage: Storage
    let sending: Sending
    let drive: Drive
    /// "free" or "premium", decided by the server from a verified App Store
    /// receipt. Optional so a client that is newer than the server it is talking
    /// to still decodes the account instead of failing outright.
    let tier: String?
    /// ISO8601 renewal date, nil on a free account.
    let premiumUntil: String?

    var isPremium: Bool { tier == "premium" }

    struct Storage: Codable {
        let used: Int
        let quota: Int
        let remaining: Int
        let messages: Int
    }
    struct Sending: Codable {
        let sent: Int
        let quota: Int
        let remaining: Int
        let month: String
    }
    struct Drive: Codable {
        let used: Int
        let quota: Int
        let remaining: Int
        let files: Int
    }
}

/// Everything the app shows in a mailbox row. `envelope` is nil only when the sealed
/// envelope failed to open, which means this device is not the one the mail was sealed to.
struct OSHIMailMessage: Identifiable {
    let id: String
    let receivedAt: Date
    let bytes: Int
    let seen: Bool
    let folder: String
    /// Which of the account's addresses this arrived at — the alias, when an alias was used.
    let deliveredTo: String
    let envelope: Envelope?

    struct Envelope: Codable {
        let from: String
        let to: String
        let cc: String?
        let subject: String
        let date: String
        let messageId: String?
        let bytes: Int
        /// List preview the server computes at delivery from the plaintext it holds for one
        /// function call, sealed inside this envelope with the subject. Absent on mail
        /// delivered before that change (the row then shows no snippet).
        var snippet: String? = nil
        var hasAttachments: Bool = false
        var attachmentCount: Int = 0
        var hasImages: Bool = false
    }
}

extension OSHIMailMessage.Envelope {
    private enum CodingKeys: String, CodingKey {
        case from, to, cc, subject, date, messageId, bytes
        case snippet, hasAttachments, attachmentCount, hasImages
        case attachments // Sent copies: filenames, older than the counters
    }

    private static let imageName = try! NSRegularExpression(
        pattern: #"\.(jpe?g|png|gif|heic|heif|webp|bmp|tiff?)$"#, options: [.caseInsensitive])

    /// Lenient on purpose: every field past the subject is optional, and a wrong type in
    /// one of them drops that field — never the whole envelope (a nil envelope renders as
    /// "sealed for another device", which would be a lie). Unknown keys are ignored, as
    /// they always were with the synthesized decoder.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = (try? c.decodeIfPresent(String.self, forKey: .from)) ?? ""
        to = (try? c.decodeIfPresent(String.self, forKey: .to)) ?? ""
        cc = try? c.decodeIfPresent(String.self, forKey: .cc)
        subject = (try? c.decodeIfPresent(String.self, forKey: .subject)) ?? ""
        date = (try? c.decodeIfPresent(String.self, forKey: .date)) ?? ""
        messageId = try? c.decodeIfPresent(String.self, forKey: .messageId)
        // Android drafts never carried `bytes`; it used to make the whole draft row unreadable.
        bytes = (try? c.decodeIfPresent(Int.self, forKey: .bytes)) ?? 0

        let legacyNames = ((try? c.decodeIfPresent([String].self, forKey: .attachments)) ?? nil) ?? []
        let rawCount = (try? c.decodeIfPresent(Int.self, forKey: .attachmentCount)) ?? nil
        let count = min(max(rawCount ?? legacyNames.count, 0), 999)
        let flag = ((try? c.decodeIfPresent(Bool.self, forKey: .hasAttachments)) ?? nil) ?? false
        hasAttachments = flag || count > 0
        attachmentCount = hasAttachments ? max(count, 1) : 0
        hasImages = ((try? c.decodeIfPresent(Bool.self, forKey: .hasImages)) ?? nil)
            ?? legacyNames.contains { name in
                Self.imageName.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
            }
        let text = ((try? c.decodeIfPresent(String.self, forKey: .snippet)) ?? nil)?
            .components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
        snippet = (text?.isEmpty ?? true) ? nil : text
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(from, forKey: .from)
        try c.encode(to, forKey: .to)
        try c.encodeIfPresent(cc, forKey: .cc)
        try c.encode(subject, forKey: .subject)
        try c.encode(date, forKey: .date)
        try c.encodeIfPresent(messageId, forKey: .messageId)
        try c.encode(bytes, forKey: .bytes)
        try c.encodeIfPresent(snippet, forKey: .snippet)
        try c.encode(hasAttachments, forKey: .hasAttachments)
        try c.encode(attachmentCount, forKey: .attachmentCount)
        try c.encode(hasImages, forKey: .hasImages)
    }

    /// An opened (decrypted) envelope, or nil if it is not a JSON object at all.
    static func decode(_ plain: Data) -> OSHIMailMessage.Envelope? {
        try? JSONDecoder().decode(OSHIMailMessage.Envelope.self, from: plain)
    }
}

/// `GET /mail/v1/inbox/status` (ServerPatches/mail_inbox_status). Counts and a
/// fingerprint only — nothing sealed, nothing the server did not already index.
struct OSHIMailInboxStatus: Codable, Equatable {
    let inboxUnread: Int
    let inboxTotal: Int
    let latestInboxId: String?
    let latestInboxAt: String?
    let fingerprint: String

    /// sha256 hex of the rows' `id:seen(0|1):folder` lines, sorted, joined by "\n" —
    /// byte-for-byte the server's `inbox_status.js fingerprint()`. Order-independent so
    /// the client's newest-first list and the server's append-order index agree.
    static func fingerprint(_ rows: [(id: String, seen: Bool, folder: String)]) -> String {
        let lines = rows.map { "\($0.id):\($0.seen ? 1 : 0):\($0.folder)" }.sorted()
        let digest = SHA256.hash(data: Data(lines.joined(separator: "\n").utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

enum OSHIMailError: LocalizedError {
    case notSignedIn
    case noMailbox
    case addressTaken
    case addressInvalid(String)
    case sendQuotaExceeded(sent: Int, quota: Int, resetsAt: String?)
    case mailboxFull
    case driveFull
    case server(status: Int, code: String)
    case sealedForAnotherDevice
    case transport(Error)

    var errorDescription: String? {
        switch self {
        case .notSignedIn:            return "mail.err.notSignedIn".localized
        case .noMailbox:              return "mail.err.noMailbox".localized
        case .addressTaken:           return "mail.err.addressTaken".localized
        case .addressInvalid(let r):  return String(format: "mail.err.addressInvalid".localized, r)
        case .sendQuotaExceeded(let s, let q, _):
            return String(format: "mail.err.quotaSent".localized, s, q)
        case .mailboxFull:            return "mail.err.mailboxFull".localized
        case .driveFull:              return "mail.err.driveFull".localized
        case .sealedForAnotherDevice:
            return "mail.err.sealedOther".localized
        case .server(let st, let code): return String(format: "mail.err.server".localized, st, code)
        case .transport(let e):       return e.localizedDescription
        }
    }
}

// MARK: - Account cache (__MAIL_OFFLINE_NOT_SETUP_2026_11_25__)

/// Where the last server-confirmed `OSHIMailAccount` is kept, per identity.
protocol OSHIMailAccountStore {
    func load(scope: String) -> OSHIMailAccount?
    func save(_ account: OSHIMailAccount, scope: String)
    func clear(scope: String)
}

/// Keychain-backed: same service as the mail key, one item per identity, this device
/// only. `PanicWipe` empties every keychain class, so a wipe takes it too.
struct OSHIMailKeychainAccountStore: OSHIMailAccountStore {
    static let service = "com.oshi.messenger.mail"

    /// The identity public key is hashed so the keychain item name does not carry it.
    static func itemAccount(scope: String) -> String {
        let digest = SHA256.hash(data: Data(scope.utf8))
        return "mail-account-cache." + digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    private func baseQuery(_ scope: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Self.service,
         kSecAttrAccount as String: Self.itemAccount(scope: scope)]
    }

    func load(scope: String) -> OSHIMailAccount? {
        var q = baseQuery(scope)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(OSHIMailAccount.self, from: data)
    }

    func save(_ account: OSHIMailAccount, scope: String) {
        guard let data = try? JSONEncoder().encode(account) else { return }
        SecItemDelete(baseQuery(scope) as CFDictionary)
        var q = baseQuery(scope)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    func clear(scope: String) {
        SecItemDelete(baseQuery(scope) as CFDictionary)
    }
}

/// What the Email screen shows. Only a DEFINITIVE `no-mailbox` from the server may
/// lead to the claim-an-address form; any other failure without a known account is
/// "can't reach mail", never "you have no mailbox".
enum MailScreenState: Equatable {
    case loading
    case mailbox
    case setup
    case unreachable

    static func resolve(isLoading: Bool, hasAccount: Bool, confirmedNoMailbox: Bool) -> MailScreenState {
        if hasAccount { return .mailbox }
        if isLoading { return .loading }
        return confirmedNoMailbox ? .setup : .unreachable
    }
}

// MARK: - Client

@MainActor
final class OSHIMailClient: ObservableObject {

    static let shared = OSHIMailClient()

    /// The mail domain is a SUBDOMAIN of the API host: the API is served from
    /// oshi-messenger.com (existing certificate), while addresses live at
    /// mail.oshi-messenger.com so the apex domain's existing iCloud mail keeps working.
    static let mailDomain = "mail.oshi-messenger.com"

    private let baseURL: URL
    private let session: URLSession

    /// `IdentityManager` is an `@EnvironmentObject` in this app, not a singleton, so the
    /// identity is injected once by `MailView` rather than reached for globally. Until
    /// that happens every signed call fails closed with `.notSignedIn` — a mailbox must
    /// never fall back to an unsigned request.
    private var identity: IdentityManager?

    @Published private(set) var account: OSHIMailAccount?
    @Published private(set) var messages: [OSHIMailMessage] = [] {
        // Any change to the mailbox (a fetch that pulls new mail, a message read,
        // moved or deleted) shifts the unread count, so the single app-icon badge —
        // which sums 1:1 + group + mail unread — is recomputed here. `syncAppBadge`
        // reads `unreadInboxCount` back from this same client, so it always reflects
        // the value just assigned.
        didSet { MessageManager.shared?.syncAppBadge() }
    }

    /// Unread mail in the Inbox only. Archive/Sent/Trash never count toward "pending"
    /// email, exactly like `MailView.folderCount` shows an unread pill on the Inbox
    /// folder alone. Drives the Email-tab badge and the mail slice of the app icon badge.
    var unreadInboxCount: Int {
        messages.filter { $0.folder == "inbox" && !$0.seen }.count
    }

    /// Plaintext (subject + from + body) for messages already opened this session, keyed
    /// by id. Populated by the reader when it decrypts a message. Search's "deep" mode
    /// matches against this cache instead of downloading every message — the mailbox is
    /// zero-access, so there is no server-side search to fall back to.
    @Published private(set) var bodyCache: [String: String] = [:]

    /// __MAILBOX_FRESHNESS_BADGE_2026_11_04__ When the inbox was last proved
    /// current, or nil if it never has been in this session.
    ///
    /// The mailbox gave no sign of whether what it showed was fresh: an inbox
    /// that failed to refresh looked exactly like an inbox with nothing new,
    /// and both look exactly like an inbox that has never loaded. Three states
    /// rendered as one.
    ///
    /// Set ONLY on a refresh that actually returned rows, never on entering
    /// the screen, because the badge is a claim about the server and arriving
    /// at a view proves nothing about it. See
    /// [freshness-indicator-watched-one-of-two-sources] in the project notes:
    /// an "up to date" light that is not wired to the thing it describes is
    /// worse than no light, because it ends the user's doubt wrongly.
    @Published private(set) var lastSyncedAt: Date?

    /// A refresh is in flight. Distinct from `lastSyncedAt == nil`: trying and
    /// never having tried are different things to show.
    @Published private(set) var isRefreshing: Bool = false

    /// The most recent refresh threw. Cleared by the next one that does not.
    @Published private(set) var lastRefreshFailed: Bool = false

    #if DEBUG
    // DEBUG-only mail fixtures (`MailFixtureHarness`): lets the reader be driven on a
    // simulator with real-world-shaped .eml files, no server and no mailbox needed.
    private(set) var debugFixtureAccount: OSHIMailAccount?
    private(set) var debugFixtureBodies: [String: Data] = [:]

    func installDebugFixtures(account: OSHIMailAccount, messages: [OSHIMailMessage], bodies: [String: Data]) {
        debugFixtureAccount = account
        debugFixtureBodies = bodies
        self.account = account
        self.messages = messages
    }
    #endif

    func cacheBody(id: String, _ text: String) {
        bodyCache[id] = text
    }

    init(baseURL: URL = URL(string: "https://oshi-messenger.com")!,
         session: URLSession = .shared,
         accountStore: OSHIMailAccountStore = OSHIMailKeychainAccountStore()) {
        self.baseURL = baseURL
        self.session = session
        self.accountStore = accountStore
    }

    /// Called by the mail UI with the identity the app already holds. Idempotent.
    func configure(identity: IdentityManager) {
        self.identity = identity
        seedAccountFromCache(scope: identity.publicKey)
    }

    // MARK: Account cache (__MAIL_OFFLINE_NOT_SETUP_2026_11_25__)

    /// The last account the server confirmed, kept on disk so that a cold start with
    /// no network still knows this identity HAS a mailbox. Without it `account` was
    /// memory-only: offline, `refreshAccount()` threw, `account` stayed nil, and the
    /// mail screen offered "claim an address" to someone who already owns one.
    ///
    /// Scoped by the identity's public key, so another identity on this device never
    /// sees it. Cleared only by the server's explicit `no-mailbox` answer — never by
    /// a timeout, an offline error or a 5xx, which say nothing about the mailbox.
    private let accountStore: OSHIMailAccountStore

    /// The identity scope `account` currently belongs to (nil before `configure`).
    private(set) var accountScope: String?

    /// Seeds `account` from the cache for `scope`. Switching to a different identity
    /// drops whatever the previous one had in memory first.
    func seedAccountFromCache(scope: String) {
        guard !scope.isEmpty else { return }
        #if DEBUG
        if debugFixtureAccount != nil { return }
        #endif
        if accountScope != scope {
            accountScope = scope
            account = accountStore.load(scope: scope)
        } else if account == nil {
            account = accountStore.load(scope: scope)
        }
    }

    /// The live identity wins over the remembered scope, so a result fetched after an
    /// identity switch is never filed under the previous identity.
    private var currentScope: String? {
        if let key = identity?.publicKey, !key.isEmpty { return key }
        return accountScope
    }

    /// Records a server-confirmed account: in memory and in the per-identity cache.
    private func adoptAccount(_ acct: OSHIMailAccount) {
        account = acct
        if let scope = currentScope {
            accountScope = scope
            accountStore.save(acct, scope: scope)
        }
    }

    /// The server said this identity has no mailbox: the only thing that may forget one.
    private func forgetAccount() {
        account = nil
        if let scope = currentScope {
            accountStore.clear(scope: scope)
        }
    }

    /// Applies the outcome of an account fetch to memory and the cache. Split out so
    /// the seed/clear rules are testable without a network.
    func applyAccountFetch(_ result: Result<OSHIMailAccount, Error>) {
        switch result {
        case .success(let acct):
            adoptAccount(acct)
        case .failure(OSHIMailError.noMailbox):
            forgetAccount()
        case .failure:
            // Offline / timeout / 5xx: keep whatever we had, and say it is stale.
            lastRefreshFailed = true
        }
    }

    // MARK: Mail key (X25519)

    private static let keychainService = "com.oshi.messenger.mail"
    private static let keychainAccount = "mail-x25519-private"

    /// The private half of the mail key.
    ///
    /// It is DERIVED from the OSHI identity (`IdentityManager.deriveMailKeyMaterial`),
    /// not randomly generated. The derivation is a pure function of the identity secret,
    /// which the seed phrase / 64-byte key backup restores exactly — so restoring the
    /// identity on a new phone re-derives the SAME mail key, and the mailbox and drive
    /// (both sealed to it / bound to the identity) come back. Lose the phone, keep the
    /// key, keep the mail. The server still never sees this key: zero-access is unchanged.
    ///
    /// The keychain copy is only a fast local cache. If no identity is configured yet
    /// (e.g. a legacy install before this change), we fall back to a device-local key so
    /// the surface still works, but that key is not recoverable — the identity path is.
    private var mailPrivateKey: Curve25519.KeyAgreement.PrivateKey {
        get throws {
            if let material = identity?.deriveMailKeyMaterial() {
                return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: material)
            }
            if let existing = try Self.loadMailKey() { return existing }
            let fresh = Curve25519.KeyAgreement.PrivateKey()
            try Self.storeMailKey(fresh)
            return fresh
        }
    }

    /// A valid local-part derived from this device's public key, for an alias that reads
    /// like an identity rather than a chosen name — e.g. "a3f81c…@mail.oshi-messenger.com".
    /// The full key is 64 hex chars (too long for a local-part), so a stable 18-char prefix
    /// is used; hex is already within the allowed [a-z0-9] set.
    var keyBasedLocalpart: String? {
        guard let hex = mailPublicKeyHex else { return nil }
        return String(hex.prefix(18)).lowercased()
    }

    var mailPublicKeyHex: String? {
        guard let key = try? mailPrivateKey else { return nil }
        return key.publicKey.rawRepresentation.map { String(format: "%02x", $0) }.joined()
    }

    private static func loadMailKey() throws -> Curve25519.KeyAgreement.PrivateKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data)
    }

    private static func storeMailKey(_ key: Curve25519.KeyAgreement.PrivateKey) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecValueData as String: key.rawRepresentation,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        SecItemDelete(query as CFDictionary)
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: "OSHIMail", code: Int(status),
                          userInfo: [NSLocalizedDescriptionKey: "Could not store the mail key (\(status))"])
        }
    }

    // MARK: Opening sealed blobs

    /// Undoes `crypto_mail.js seal()`: epk(32) ‖ iv(12) ‖ tag(16) ‖ ciphertext.
    ///
    /// The HKDF salt is `epk ‖ ourPublicKey`, which binds the blob to this recipient —
    /// a sealed message re-pointed at another account simply fails to open rather than
    /// decrypting to something plausible.
    func open(_ blob: Data) throws -> Data {
        guard blob.count > 60 else { throw OSHIMailError.sealedForAnotherDevice }
        let priv = try mailPrivateKey

        let epk = blob.prefix(32)
        let iv  = blob.dropFirst(32).prefix(12)
        let tag = blob.dropFirst(44).prefix(16)
        let ct  = blob.dropFirst(60)

        do {
            let ephemeral = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: epk)
            let shared = try priv.sharedSecretFromKeyAgreement(with: ephemeral)
            let salt = Data(epk) + priv.publicKey.rawRepresentation
            let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self,
                                                     salt: salt,
                                                     sharedInfo: Data("oshi-mail-v1".utf8),
                                                     outputByteCount: 32)
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv),
                                            ciphertext: Data(ct),
                                            tag: Data(tag))
            return try AES.GCM.open(box, using: key)
        } catch {
            throw OSHIMailError.sealedForAnotherDevice
        }
    }

    /// Mirrors `crypto_mail.js seal()` so the app can encrypt what it uploads to the
    /// drive. Sealing to the user's OWN mail public key keeps one key to lose instead of
    /// two: a drive file and a mailbox message have exactly the same recovery story
    /// (namely, none — see `mailPrivateKey`).
    ///
    /// Whole-file, in memory. That is why `MailDriveView` caps what it will upload: a
    /// streaming, chunk-sealed format would lift the cap but has to be designed so the
    /// download side can seek, and a half-streaming implementation that OOMs on a big
    /// file is worse than an honest limit.
    func seal(_ plaintext: Data) throws -> Data {
        try seal(plaintext, to: try mailPrivateKey.publicKey.rawRepresentation)
    }

    /// Seals to an arbitrary recipient. Used by browser pairing, where the recipient is an
    /// ephemeral key that exists only inside one browser tab.
    func seal(_ plaintext: Data, to recipientPub: Data) throws -> Data {
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let epk = ephemeral.publicKey.rawRepresentation
        let shared = try ephemeral.sharedSecretFromKeyAgreement(
            with: try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientPub))

        let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self,
                                                 salt: epk + recipientPub,
                                                 sharedInfo: Data("oshi-mail-v1".utf8),
                                                 outputByteCount: 32)
        let box = try AES.GCM.seal(plaintext, using: key)
        // Field order must match the server's: epk ‖ iv ‖ tag ‖ ciphertext. CryptoKit's
        // `combined` puts the tag AFTER the ciphertext, so it cannot be used as-is.
        return epk + Data(box.nonce) + box.tag + box.ciphertext
    }

    // MARK: Signed transport

    private func request(_ method: String, _ path: String, body: Data? = nil,
                         contentType: String = "application/json") throws -> URLRequest {
        guard let identity,
              let signed = identity.signHTTPRequest(method: method, path: path, bodyData: body) else {
            throw OSHIMailError.notSignedIn
        }
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = method
        req.httpBody = body
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        req.setValue(signed.signature, forHTTPHeaderField: "x-oshi-signature")
        req.setValue(signed.timestamp, forHTTPHeaderField: "x-oshi-timestamp")
        req.setValue(signed.signingPublicKey, forHTTPHeaderField: "x-oshi-signing-pubkey")
        return req
    }

    /// The signature covers the path WITHOUT the query string, so a query is appended
    /// after signing — signing the full URL is the classic way to earn a silent 401.
    private func request(_ method: String, _ path: String, query: [URLQueryItem]) throws -> URLRequest {
        var req = try request(method, path)
        var comps = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)!
        comps.queryItems = query
        req.url = comps.url
        return req
    }

    private func send(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                throw OSHIMailError.server(status: 0, code: "not-http")
            }
            return (data, http)
        } catch let e as OSHIMailError {
            throw e
        } catch {
            throw OSHIMailError.transport(error)
        }
    }

    private func decodeError(_ data: Data, status: Int) -> OSHIMailError {
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let code = obj?["error"] as? String ?? "unknown"
        switch code {
        case "address-taken":         return .addressTaken
        case "no-mailbox":            return .noMailbox
        case "mailbox-full":          return .mailboxFull
        case "drive-full":            return .driveFull
        case "send-quota-exceeded":
            return .sendQuotaExceeded(sent: obj?["sent"] as? Int ?? 0,
                                      quota: obj?["quota"] as? Int ?? 150,
                                      resetsAt: obj?["resetsAt"] as? String)
        case let c where c.hasPrefix("localpart-"): return .addressInvalid(c)
        default:                      return .server(status: status, code: code)
        }
    }

    // MARK: Account

    /// Is this local-part free? Unauthenticated and rate-limited server-side, so it can
    /// be called while the user types.
    func isAvailable(_ localpart: String) async throws -> Bool {
        var comps = URLComponents(url: baseURL.appendingPathComponent("/mail/v1/availability"),
                                  resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "localpart", value: localpart.lowercased())]
        let (data, http) = try await send(URLRequest(url: comps.url!))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        return ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["available"] as? Bool ?? false
    }

    /// Claim an address. Publishes only the PUBLIC mail key — this is the moment the
    /// mailbox becomes writable-but-unreadable by the server.
    @discardableResult
    func register(localpart: String) async throws -> OSHIMailAccount {
        guard let pub = mailPublicKeyHex else { throw OSHIMailError.notSignedIn }
        var fields: [String: Any] = [
            "localpart": localpart.lowercased(),
            "mailPubKey": pub,
        ]
        // The X25519 messaging identity (this same key already carries the device's APNs
        // token in the push service), so a delivered mail can wake the device. Not secret,
        // not the mail key — only "ping these devices". Zero-access is unchanged.
        if let pushId = identity?.publicKey { fields["identityPubKey"] = pushId }
        let body = try JSONSerialization.data(withJSONObject: fields)
        let (data, http) = try await send(try request("POST", "/mail/v1/register", body: body))
        guard http.statusCode == 201 || http.statusCode == 200 else {
            throw decodeError(data, status: http.statusCode)
        }
        let acct = try JSONDecoder().decode(Wrapper.self, from: data).account
        adoptAccount(acct)
        return acct
    }

    @discardableResult
    func refreshAccount() async throws -> OSHIMailAccount {
        #if DEBUG
        if let fixture = debugFixtureAccount { return fixture }
        #endif
        if let key = identity?.publicKey { seedAccountFromCache(scope: key) }
        do {
            let (data, http) = try await send(try request("GET", "/mail/v1/account"))
            guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
            let acct = try JSONDecoder().decode(Wrapper.self, from: data).account
            applyAccountFetch(.success(acct))
            return acct
        } catch {
            applyAccountFetch(.failure(error))
            throw error
        }
    }

    private struct Wrapper: Codable { let account: OSHIMailAccount }

    // MARK: Premium

    /// Hands Apple's signed transaction to the server, which verifies Apple's
    /// signature against a pinned root before granting anything. The app does
    /// not decide its own tier — it forwards a receipt and reads back whatever
    /// the server decided.
    ///
    /// Throws on refusal so the caller can tell "already used on another
    /// mailbox" (409) from "try again later" (5xx / offline), which need
    /// opposite handling.
    @discardableResult
    func redeemPremium(signedTransaction jws: String) async throws -> OSHIMailAccount {
        let body = try JSONEncoder().encode(["signedTransaction": jws])
        let (data, http) = try await send(try request("POST", "/mail/v1/premium/redeem", body: body))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        let acct = try JSONDecoder().decode(Wrapper.self, from: data).account
        adoptAccount(acct)
        return acct
    }

    // MARK: Inbox

    /// Fetches the sealed envelopes and opens them HERE. The list you see is assembled on
    /// this device; the server never held the subjects it is showing.
    @discardableResult
    func refreshInbox() async throws -> [OSHIMailMessage] {
        // __MAILBOX_FRESHNESS_BADGE_2026_11_04__ The badge's three states are
        // set here and nowhere else, so it can only ever describe a real round
        // trip to the server.
        isRefreshing = true
        defer { isRefreshing = false }
        do {
            let result = try await refreshInboxInner()
            lastSyncedAt = Date()
            lastRefreshFailed = false
            return result
        } catch {
            lastRefreshFailed = true
            throw error
        }
    }

    private func refreshInboxInner() async throws -> [OSHIMailMessage] {
        #if DEBUG
        if debugFixtureAccount != nil { return messages }
        #endif
        let (data, http) = try await send(try request("GET", "/mail/v1/inbox"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }

        struct Row: Codable {
            let id: String, receivedAt: String, bytes: Int, seen: Bool
            let folder: String, deliveredTo: String, sealedEnvelope: String?
        }
        struct Response: Codable { let messages: [Row] }

        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        let rows = try JSONDecoder().decode(Response.self, from: data).messages
        let opened: [OSHIMailMessage] = rows.map { row in
            var envelope: OSHIMailMessage.Envelope?
            if let sealed = row.sealedEnvelope, let blob = Data(base64Encoded: sealed),
               let plain = try? open(blob) {
                envelope = OSHIMailMessage.Envelope.decode(plain)
            }
            return OSHIMailMessage(id: row.id,
                                   receivedAt: iso.date(from: row.receivedAt) ?? Date(),
                                   bytes: row.bytes,
                                   seen: row.seen,
                                   folder: row.folder,
                                   deliveredTo: row.deliveredTo,
                                   envelope: envelope)
        }
        self.messages = opened
        return opened
    }

    // MARK: Inbox status (__MAIL_UNREAD_REFRESH_2026_11_22__)

    /// Whether a signed request can be made at all. `MailUnreadRefresher` checks this
    /// before asking, so an app that has not finished loading its identity does not
    /// burn its backoff on `.notSignedIn`.
    var canSign: Bool { identity != nil }

    /// Ever refreshed in this process. Until then the local list proves nothing and a
    /// status that "matches" an empty list must not be trusted.
    var hasSyncedOnce: Bool { lastSyncedAt != nil }

    /// `GET /mail/v1/inbox/status` — a few hundred bytes from the server's index (no
    /// sealed blob is read): unread count and a fingerprint of the rows `/inbox` would
    /// list. Returns nil when the route is not deployed (404 `not-found`), so the caller
    /// falls back to the full listing; `no-mailbox` and every other error throw.
    func fetchInboxStatus() async throws -> OSHIMailInboxStatus? {
        #if DEBUG
        if debugFixtureAccount != nil { return nil }
        #endif
        let (data, http) = try await send(try request("GET", "/mail/v1/inbox/status"))
        if http.statusCode == 404 {
            let err = decodeError(data, status: 404)
            if case .server(_, let code) = err, code == "not-found" { return nil }
            throw err
        }
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        return try JSONDecoder().decode(OSHIMailInboxStatus.self, from: data)
    }

    /// The same fingerprint the server computes over the rows it would list, from the
    /// rows this device holds. Equal ⇒ nothing arrived, was read, moved or deleted.
    var localInboxFingerprint: String {
        OSHIMailInboxStatus.fingerprint(messages.map { ($0.id, $0.seen, $0.folder) })
    }

    /// Unread Inbox rows, newest first — what a "new mail" banner may be about.
    var unreadInboxMessages: [OSHIMailMessage] {
        messages.filter { $0.folder == "inbox" && !$0.seen }
                .sorted { $0.receivedAt > $1.receivedAt }
    }

    /// The full RFC822 message, decrypted on this device.
    func fetchMessage(id: String) async throws -> Data {
        #if DEBUG
        if let fixture = debugFixtureBodies[id] { return fixture }
        #endif
        let (data, http) = try await send(try request("GET", "/mail/v1/message/\(id)"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        return try open(data)
    }

    func markSeen(id: String, seen: Bool = true) async throws {
        #if DEBUG
        if debugFixtureAccount != nil { replaceLocal(id: id) { $0.seen = seen }; return }
        #endif
        let body = try JSONSerialization.data(withJSONObject: ["seen": seen])
        _ = try await send(try request("POST", "/mail/v1/message/\(id)/seen", body: body))
        replaceLocal(id: id) { $0.seen = seen }
    }

    /// Moves a message between folders — the archive/restore/trash actions, and how a
    /// draft becomes a real message is NOT this (sending deletes the draft; see `delete`).
    func move(id: String, to folder: String) async throws {
        let body = try JSONSerialization.data(withJSONObject: ["folder": folder])
        let (data, http) = try await send(try request("POST", "/mail/v1/message/\(id)/move", body: body))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        replaceLocal(id: id) { $0.folder = folder }
    }

    /// The first call moves a message to Trash; a second call (or `permanent: true`)
    /// destroys it for good. Returns whether it was actually destroyed, so the caller can
    /// tell "moved to trash" from "gone forever" apart without guessing from the folder.
    @discardableResult
    func delete(id: String, permanent: Bool = false) async throws -> Bool {
        let req: URLRequest
        if permanent {
            req = try request("DELETE", "/mail/v1/message/\(id)", query: [URLQueryItem(name: "permanent", value: "1")])
        } else {
            req = try request("DELETE", "/mail/v1/message/\(id)")
        }
        let (data, http) = try await send(req)
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let destroyed = (obj?["deleted"] as? Bool) ?? permanent
        if destroyed {
            messages.removeAll { $0.id == id }
            bodyCache.removeValue(forKey: id)
        } else {
            replaceLocal(id: id) { $0.folder = "trash" }
        }
        _ = try? await refreshAccount()
        return destroyed
    }

    /// `OSHIMailMessage`'s fields are all `let`, so a local update rebuilds the row rather
    /// than mutating it in place — kept in one place because every action above needs it.
    private func replaceLocal(id: String, _ change: (inout MutableMessage) -> Void) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        var m = MutableMessage(messages[idx])
        change(&m)
        messages[idx] = m.frozen
    }

    private struct MutableMessage {
        var id: String; var receivedAt: Date; var bytes: Int; var seen: Bool
        var folder: String; var deliveredTo: String; var envelope: OSHIMailMessage.Envelope?
        init(_ m: OSHIMailMessage) {
            id = m.id; receivedAt = m.receivedAt; bytes = m.bytes; seen = m.seen
            folder = m.folder; deliveredTo = m.deliveredTo; envelope = m.envelope
        }
        var frozen: OSHIMailMessage {
            OSHIMailMessage(id: id, receivedAt: receivedAt, bytes: bytes, seen: seen,
                            folder: folder, deliveredTo: deliveredTo, envelope: envelope)
        }
    }

    // MARK: Drafts

    /// Saves the composer as a SEALED draft (both the envelope and the body, separately —
    /// mirrors `saveDraft` in the web client). Reuses `id` when editing so autosave does
    /// not create a new draft on every debounce tick.
    @discardableResult
    func saveDraft(id: String?, to: String, subject: String, body: String) async throws -> String {
        let iso = ISO8601DateFormatter()
        let envelope: [String: Any] = [
            "from": account?.address ?? "",
            "to": to,
            "subject": subject,
            "date": iso.string(from: Date()),
            "bytes": body.utf8.count,
            // Same list preview field the server puts in delivered mail.
            "snippet": MailMessageParser.listSnippet(of: body),
        ]
        let sealedEnvelope = try seal(try JSONSerialization.data(withJSONObject: envelope))
        let sealedBody = try seal(Data(body.utf8))

        var payload: [String: Any] = [
            "sealedEnvelope": sealedEnvelope.base64EncodedString(),
            "sealedBody": sealedBody.base64EncodedString(),
        ]
        if let id { payload["id"] = id }

        let bodyData = try JSONSerialization.data(withJSONObject: payload)
        let (data, http) = try await send(try request("POST", "/mail/v1/draft", body: bodyData))
        guard http.statusCode == 200 || http.statusCode == 201 else { throw decodeError(data, status: http.statusCode) }
        guard let newId = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["id"] as? String else {
            throw OSHIMailError.server(status: http.statusCode, code: "no-draft-id")
        }
        return newId
    }

    // MARK: Address book

    /// Built LOCALLY from decrypted envelopes — from/to/cc across every message this
    /// device has opened. There is deliberately no server-side contact store on a
    /// zero-access mailbox: the mailbox already contains everyone you correspond with,
    /// and only this device can read it.
    struct MailContact: Identifiable, Hashable {
        let address: String
        let name: String
        var id: String { address }
    }

    var contacts: [MailContact] {
        var name: [String: String] = [:]
        var order: [String] = []
        for m in messages {
            guard let e = m.envelope else { continue }
            for field in [e.from, e.to, e.cc].compactMap({ $0 }) {
                for piece in field.split(separator: ",") {
                    let addr = MailMessageParser.emailAddress(from: String(piece)).lowercased()
                    guard addr.contains("@"), addr.count <= 120 else { continue }
                    let display = MailMessageParser.displayName(from: String(piece))
                    if name[addr] == nil { order.append(addr) }
                    if name[addr] == nil || (!display.isEmpty && display != addr) { name[addr] = display }
                }
            }
        }
        return order.sorted().map { MailContact(address: $0, name: name[$0] ?? "") }
    }

    // MARK: Sending

    /// Sends one message, counted against the 150/month allowance.
    ///
    /// `from` may only be one of this account's own aliases; the server refuses anything
    /// else with 403 rather than letting a client forge a sender.
    struct Attachment {
        let filename: String
        let mimeType: String
        let data: Data
    }

    /// The server caps a whole message at 25 MB and base64 inflates by about a third, so
    /// this is the real ceiling for the raw bytes. Checked here to fail before the upload
    /// rather than after it.
    static let maxAttachmentTotalBytes = 18 * 1024 * 1024

    @discardableResult
    func sendMail(to: [String], subject: String, text: String,
                  cc: [String] = [], bcc: [String] = [],
                  html: String? = nil, from: String? = nil,
                  inReplyTo: String? = nil,
                  attachments: [Attachment] = []) async throws -> String {
        var payload: [String: Any] = ["to": to, "subject": subject, "text": text]
        if !attachments.isEmpty {
            let total = attachments.reduce(0) { $0 + $1.data.count }
            guard total <= Self.maxAttachmentTotalBytes else {
                throw OSHIMailError.server(status: 413, code: "attachments-too-large")
            }
            payload["attachments"] = attachments.map {
                ["filename": $0.filename, "mimeType": $0.mimeType, "dataBase64": $0.data.base64EncodedString()]
            }
        }
        if !cc.isEmpty { payload["cc"] = cc }
        if !bcc.isEmpty { payload["bcc"] = bcc }
        if let html { payload["html"] = html }
        if let from { payload["from"] = from }
        if let inReplyTo { payload["inReplyTo"] = inReplyTo }

        let body = try JSONSerialization.data(withJSONObject: payload)
        let (data, http) = try await send(try request("POST", "/mail/v1/send", body: body))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }

        _ = try? await refreshAccount()
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        return obj?["messageId"] as? String ?? ""
    }

    // MARK: Aliases

    @discardableResult
    func addAlias(_ alias: String) async throws -> [String] {
        let body = try JSONSerialization.data(withJSONObject: ["alias": alias.lowercased()])
        let (data, http) = try await send(try request("POST", "/mail/v1/alias", body: body))
        guard http.statusCode == 201 else { throw decodeError(data, status: http.statusCode) }
        _ = try? await refreshAccount()
        return ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["aliases"] as? [String] ?? []
    }

    @discardableResult
    func removeAlias(_ alias: String) async throws -> [String] {
        let localpart = alias.split(separator: "@").first.map(String.init) ?? alias
        let (data, http) = try await send(try request("DELETE", "/mail/v1/alias/\(localpart)"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        _ = try? await refreshAccount()
        return ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["aliases"] as? [String] ?? []
    }

    // MARK: Browser pairing

    struct PairingRequest {
        let code: String
        /// The ephemeral public key of the browser tab asking to be let in.
        let browserPublicKey: Data
        let ageSeconds: Int
    }

    /// Looks up what a typed code refers to, so the user can be shown what they are about
    /// to authorise before the key is handed over.
    func inspectPairing(code: String) async throws -> PairingRequest {
        let clean = code.uppercased().filter { !$0.isWhitespace }
        var comps = URLComponents(url: baseURL.appendingPathComponent("/mail/v1/web/pair/inspect"),
                                  resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "code", value: clean)]
        let (data, http) = try await send(URLRequest(url: comps.url!))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }

        struct Response: Codable { let browserPubKey: String; let ageMs: Int; let claimed: Bool }
        let r = try JSONDecoder().decode(Response.self, from: data)
        guard let pub = Data(hexString: r.browserPubKey) else {
            throw OSHIMailError.server(status: 200, code: "browser-pubkey-invalid")
        }
        return PairingRequest(code: clean, browserPublicKey: pub, ageSeconds: r.ageMs / 1000)
    }

    /// Hands the mailbox key to that browser — sealed to ITS key, so the server relays
    /// something it cannot read. This is the only operation that lets another device read
    /// this mailbox, which is why it needs the identity signature and a deliberate tap.
    @discardableResult
    func approvePairing(_ pairing: PairingRequest, label: String) async throws -> String {
        let sealedKey = try seal(try mailPrivateKey.rawRepresentation, to: pairing.browserPublicKey)
        let body = try JSONSerialization.data(withJSONObject: [
            "code": pairing.code,
            "sealedMailKey": sealedKey.base64EncodedString(),
            "label": label,
        ])
        let (data, http) = try await send(try request("POST", "/mail/v1/web/pair/claim", body: body))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        return ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["address"] as? String ?? ""
    }

    struct WebSession: Identifiable, Codable {
        let id: String
        let label: String
        let createdAt: String
        let lastSeenAt: String
    }

    func webSessions() async throws -> [WebSession] {
        let (data, http) = try await send(try request("GET", "/mail/v1/web/sessions"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        struct Response: Codable { let sessions: [WebSession] }
        return try JSONDecoder().decode(Response.self, from: data).sessions
    }

    /// `id` may be a session id, or "all" to sign every browser out at once.
    @discardableResult
    func revokeWebSession(id: String) async throws -> Int {
        let (data, http) = try await send(try request("DELETE", "/mail/v1/web/sessions/\(id)"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        return ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["revoked"] as? Int ?? 0
    }

    // MARK: Drive (1 GB)

    struct DriveFile: Identifiable {
        let id: String
        let bytes: Int
        let createdAt: String
        /// Decrypted on this device; the server stores only the sealed form.
        let name: String?
    }

    func listDriveFiles() async throws -> [DriveFile] {
        let (data, http) = try await send(try request("GET", "/mail/v1/drive"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }

        struct Row: Codable { let id: String, sealedMeta: String, bytes: Int, createdAt: String }
        struct Response: Codable { let files: [Row] }

        return try JSONDecoder().decode(Response.self, from: data).files.map { row in
            var name: String?
            if let blob = Data(base64Encoded: row.sealedMeta), let plain = try? open(blob) {
                name = (try? JSONSerialization.jsonObject(with: plain) as? [String: Any])??["name"] as? String
            }
            return DriveFile(id: row.id, bytes: row.bytes, createdAt: row.createdAt, name: name)
        }
    }

    /// Uploads a file in chunks. The bytes handed to the server are ALREADY encrypted by
    /// the caller — this method never sees, and never uploads, plaintext.
    ///
    /// `progress` is called on the main actor after each chunk.
    @discardableResult
    func uploadToDrive(encrypted: Data, sealedName: Data,
                       progress: ((Double) -> Void)? = nil) async throws -> String {
        let initBody = try JSONSerialization.data(withJSONObject: [
            "bytes": encrypted.count,
            "sealedMeta": sealedName.base64EncodedString(),
        ])
        let (initData, initHTTP) = try await send(try request("POST", "/mail/v1/drive/upload", body: initBody))
        guard initHTTP.statusCode == 201 else { throw decodeError(initData, status: initHTTP.statusCode) }

        let initObj = (try? JSONSerialization.jsonObject(with: initData)) as? [String: Any]
        guard let uploadId = initObj?["uploadId"] as? String else {
            throw OSHIMailError.server(status: 201, code: "no-upload-id")
        }
        let chunkSize = initObj?["chunkSize"] as? Int ?? 8 * 1024 * 1024

        var offset = 0
        while offset < encrypted.count {
            let end = min(offset + chunkSize, encrypted.count)
            let chunk = encrypted.subdata(in: offset..<end)
            let req = try request("PUT", "/mail/v1/drive/upload/\(uploadId)",
                                  query: [URLQueryItem(name: "offset", value: String(offset))])
            var chunkReq = req
            chunkReq.httpBody = chunk
            chunkReq.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")

            // The signature covers the body, so it must be produced over THESE bytes.
            guard let identity,
                  let signed = identity.signHTTPRequest(method: "PUT",
                                                        path: "/mail/v1/drive/upload/\(uploadId)",
                                                        bodyData: chunk) else {
                throw OSHIMailError.notSignedIn
            }
            chunkReq.setValue(signed.signature, forHTTPHeaderField: "x-oshi-signature")
            chunkReq.setValue(signed.timestamp, forHTTPHeaderField: "x-oshi-timestamp")
            chunkReq.setValue(signed.signingPublicKey, forHTTPHeaderField: "x-oshi-signing-pubkey")

            let (data, http) = try await send(chunkReq)
            guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }

            let received = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["received"] as? Int
            offset = received ?? end
            progress?(Double(offset) / Double(encrypted.count))
        }

        let (doneData, doneHTTP) = try await send(
            try request("POST", "/mail/v1/drive/upload/\(uploadId)/complete"))
        guard doneHTTP.statusCode == 201 else { throw decodeError(doneData, status: doneHTTP.statusCode) }

        let file = ((try? JSONSerialization.jsonObject(with: doneData)) as? [String: Any])?["file"] as? [String: Any]
        return file?["id"] as? String ?? uploadId
    }

    func downloadFromDrive(id: String) async throws -> Data {
        let (data, http) = try await send(try request("GET", "/mail/v1/drive/file/\(id)"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
        return data
    }

    func deleteFromDrive(id: String) async throws {
        let (data, http) = try await send(try request("DELETE", "/mail/v1/drive/file/\(id)"))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
    }

    /// Renaming re-seals the metadata HERE and sends only the new sealed blob — the
    /// server swaps one opaque value for another and never learns either name.
    func renameDriveFile(id: String, name: String) async throws {
        let meta = try JSONSerialization.data(withJSONObject: ["name": name])
        let sealedMeta = try seal(meta)
        let body = try JSONSerialization.data(withJSONObject: ["sealedMeta": sealedMeta.base64EncodedString()])
        let (data, http) = try await send(try request("PATCH", "/mail/v1/drive/file/\(id)/meta", body: body))
        guard http.statusCode == 200 else { throw decodeError(data, status: http.statusCode) }
    }
}
