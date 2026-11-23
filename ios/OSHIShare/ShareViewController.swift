//
//  ShareViewController.swift
//  OSHIShare
//
//  Share Extension for OSHI Messenger
//  Allows sharing content from other apps directly to OSHI.
//
//  🔔 2026-06-29 — In-extension contact picker.
//  Previously this screen only showed an "Open OSHI" button that relied on a
//  responder-chain openURL hack, so sharing an image meant: tap OSHI → "Open
//  OSHI" → app opens → pick a contact. Now the contact list is shown HERE
//  (read from the app group's contacts_snapshot.json), so the user selects the
//  recipient right inside the share sheet. The chosen recipient is queued
//  alongside the content (pendingShareRecipient); the main app auto-routes the
//  send to that contact. If no contacts are available yet, we fall back to the
//  old "open the app and choose there" behaviour.
//

//  🔴 2026-08-18 — THE EXTENSION SENDS BY ITSELF.
//
//  Verified on a real device: a share extension CANNOT launch its host app on
//  current iOS. Both the responder-chain `openURL:` walk and the supported
//  `NSExtensionContext.open(_:)` fail, so "queue it and the app will pick it up"
//  is really "queue it and hope the user launches OSHI by hand". Sending from
//  INSIDE the extension is the only path that actually delivers.
//
//  `selectContact` now tries the v2 stack directly (V2MessageRouter → relay +
//  blob store) and only queues what it could not send. See `attemptDirectSend`
//  for the eligibility gates and `finishSelection` for the two outcomes.

import UIKit
import UniformTypeIdentifiers
import MobileCoreServices
import CryptoKit

/// Localized strings for the share sheet, read from the CONTAINING APP's bundle.
///
/// 🌍 2026-08-18 — This screen was entirely hardcoded English: "Send to…",
/// "Search contacts", "Cancel", "Open OSHI". OSHI ships 34 languages and this is
/// the first OSHI surface a new user ever touches, reached from the Photos share
/// sheet — so it was 34 localized apps with one English door.
///
/// An app extension is a SEPARATE bundle: `NSLocalizedString` inside it reads
/// `OSHIShare.appex/*.lproj`, never the app's, and returns the raw key when the
/// table is missing. Rather than copy 34 `Localizable.strings` into the appex —
/// two copies that drift, and a key added to one is a raw key shown in the other —
/// the containing app's bundle is opened directly. `OSHIShare.appex` lives at
/// `OSHI.app/PlugIns/OSHIShare.appex`, so the app bundle is two levels up.
///
/// Every key used here already exists in all 34 `OSHI/*.lproj/Localizable.strings`
/// — checked, not assumed. Falling back to the extension's own bundle keeps this
/// safe if the layout ever changes; the worst case is the English of today.
enum ShareStrings {
    private static let appBundle: Bundle = {
        let appexURL = Bundle.main.bundleURL                    // …/OSHI.app/PlugIns/OSHIShare.appex
        let appURL = appexURL.deletingLastPathComponent()       // …/OSHI.app/PlugIns
            .deletingLastPathComponent()                        // …/OSHI.app
        return Bundle(url: appURL) ?? .main
    }()

    static func t(_ key: String, fallback: String) -> String {
        let value = appBundle.localizedString(forKey: key, value: nil, table: nil)
        // A missing key comes back AS the key. Never show that to a user.
        return value == key ? fallback : value
    }
}

class ShareViewController: UIViewController {

    // MARK: - Properties

    private let appGroupIdentifier = "group.Moriceau-lab.Genesis"

    private var sharedImages: [Data] = []
    private var sharedVideos: [URL] = []
    private var sharedURLs: [URL] = []
    private var sharedText: String?
    private var sharedFiles: [URL] = []

    private struct ShareContact {
        let publicKey: String
        let name: String
        /// When this conversation last moved, straight from the main app. Drives
        /// the ordering — see `loadContacts()`.
        let lastMessageAt: Date?
        /// Cached 96pt JPEG in the App Group, written by `AppGroupBridge`.
        let avatar: UIImage?
    }
    private var allContacts: [ShareContact] = []
    private var filteredContacts: [ShareContact] = []
    private var contentReady = false

    // MARK: - UI Elements

    private let containerView: UIView = {
        let view = UIView()
        view.backgroundColor = UIColor.systemBackground
        view.layer.cornerRadius = 20
        view.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }()

    private let titleLabel: UILabel = {
        let label = UILabel()
        label.text = ShareStrings.t("share.to", fallback: "Send to…")
        label.font = UIFont.boldSystemFont(ofSize: 20)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }()

    private let previewLabel: UILabel = {
        let label = UILabel()
        label.font = UIFont.systemFont(ofSize: 14)
        label.textColor = .secondaryLabel
        label.textAlignment = .center
        label.numberOfLines = 1
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }()

    private let searchBar: UISearchBar = {
        let sb = UISearchBar()
        sb.placeholder = ShareStrings.t("search.search_contacts", fallback: "Search contacts")
        sb.searchBarStyle = .minimal
        sb.autocapitalizationType = .none
        sb.translatesAutoresizingMaskIntoConstraints = false
        return sb
    }()

    private let tableView: UITableView = {
        let tv = UITableView(frame: .zero, style: .plain)
        tv.translatesAutoresizingMaskIntoConstraints = false
        // 🔧 FIX (2026-08-05): the rows were clipped top and bottom. A fixed 56pt
        // cannot hold a two-line cell (name + key) once Dynamic Type is anything
        // above default — and this list is the ONLY thing between the user and
        // sending, so a truncated key means picking the wrong person.
        tv.rowHeight = UITableView.automaticDimension
        tv.estimatedRowHeight = 60
        tv.keyboardDismissMode = .onDrag
        return tv
    }()

    // Shown when there are no cached contacts — falls back to opening the app.
    private let emptyStateLabel: UILabel = {
        let label = UILabel()
        label.text = ShareStrings.t("share.no_contacts", fallback: "Open OSHI to choose a contact.")
        label.font = UIFont.systemFont(ofSize: 15)
        label.textColor = .secondaryLabel
        label.textAlignment = .center
        label.numberOfLines = 0
        label.isHidden = true
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }()

    private let openAppButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle(ShareStrings.t("share.open_oshi", fallback: "Open OSHI"), for: .normal)
        button.titleLabel?.font = UIFont.boldSystemFont(ofSize: 17)
        button.backgroundColor = .systemBlue
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 12
        button.isHidden = true
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }()

    private let cancelButton: UIButton = {
        let button = UIButton(type: .system)
        button.setTitle(ShareStrings.t("common.cancel", fallback: "Cancel"), for: .normal)
        button.titleLabel?.font = UIFont.systemFont(ofSize: 17)
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }()

    private let activityIndicator: UIActivityIndicatorView = {
        let indicator = UIActivityIndicatorView(style: .medium)
        indicator.hidesWhenStopped = true
        indicator.translatesAutoresizingMaskIntoConstraints = false
        return indicator
    }()

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        processSharedContent()
    }

    // MARK: - UI Setup

    private func setupUI() {
        view.backgroundColor = UIColor.black.withAlphaComponent(0.4)

        view.addSubview(containerView)
        containerView.addSubview(titleLabel)
        containerView.addSubview(cancelButton)
        containerView.addSubview(previewLabel)
        containerView.addSubview(searchBar)
        containerView.addSubview(tableView)
        containerView.addSubview(emptyStateLabel)
        containerView.addSubview(openAppButton)
        containerView.addSubview(activityIndicator)

        tableView.dataSource = self
        tableView.delegate = self
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "contactCell")
        searchBar.delegate = self

        NSLayoutConstraint.activate([
            containerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            containerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            containerView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            containerView.heightAnchor.constraint(equalTo: view.heightAnchor, multiplier: 0.85),

            titleLabel.topAnchor.constraint(equalTo: containerView.topAnchor, constant: 18),
            titleLabel.centerXAnchor.constraint(equalTo: containerView.centerXAnchor),

            cancelButton.centerYAnchor.constraint(equalTo: titleLabel.centerYAnchor),
            cancelButton.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 16),

            previewLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 6),
            previewLabel.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 20),
            previewLabel.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -20),

            searchBar.topAnchor.constraint(equalTo: previewLabel.bottomAnchor, constant: 6),
            searchBar.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 8),
            searchBar.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -8),

            tableView.topAnchor.constraint(equalTo: searchBar.bottomAnchor, constant: 4),
            tableView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: containerView.safeAreaLayoutGuide.bottomAnchor, constant: -12),

            emptyStateLabel.topAnchor.constraint(equalTo: previewLabel.bottomAnchor, constant: 40),
            emptyStateLabel.leadingAnchor.constraint(equalTo: containerView.leadingAnchor, constant: 24),
            emptyStateLabel.trailingAnchor.constraint(equalTo: containerView.trailingAnchor, constant: -24),

            openAppButton.topAnchor.constraint(equalTo: emptyStateLabel.bottomAnchor, constant: 24),
            openAppButton.centerXAnchor.constraint(equalTo: containerView.centerXAnchor),
            openAppButton.heightAnchor.constraint(equalToConstant: 50),
            openAppButton.widthAnchor.constraint(equalToConstant: 200),

            activityIndicator.centerXAnchor.constraint(equalTo: containerView.centerXAnchor),
            activityIndicator.centerYAnchor.constraint(equalTo: containerView.centerYAnchor),
        ])

        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        openAppButton.addTarget(self, action: #selector(openAppTapped), for: .touchUpInside)

        // Tap-outside to dismiss (only on the dimmed area above the sheet).
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(backgroundTapped))
        tapGesture.delegate = self
        view.addGestureRecognizer(tapGesture)
    }

    // MARK: - Content Processing

    private func processSharedContent() {
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            finishProcessing()
            return
        }

        activityIndicator.startAnimating()

        let group = DispatchGroup()

        for item in extensionItems {
            guard let attachments = item.attachments else { continue }

            for attachment in attachments {
                // Images
                if attachment.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    group.enter()
                    attachment.loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { [weak self] data, _ in
                        defer { group.leave() }
                        if let url = data as? URL, let imageData = try? Data(contentsOf: url) {
                            self?.sharedImages.append(imageData)
                        } else if let image = data as? UIImage, let imageData = image.jpegData(compressionQuality: 0.8) {
                            self?.sharedImages.append(imageData)
                        } else if let imageData = data as? Data {
                            self?.sharedImages.append(imageData)
                        }
                    }
                }
                // Videos
                else if attachment.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    group.enter()
                    attachment.loadItem(forTypeIdentifier: UTType.movie.identifier, options: nil) { [weak self] data, _ in
                        defer { group.leave() }
                        if let url = data as? URL {
                            self?.sharedVideos.append(url)
                        }
                    }
                }
                // URLs
                else if attachment.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    group.enter()
                    attachment.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { [weak self] data, _ in
                        defer { group.leave() }
                        if let url = data as? URL {
                            self?.sharedURLs.append(url)
                        }
                    }
                }
                // Text
                else if attachment.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    group.enter()
                    attachment.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { [weak self] data, _ in
                        defer { group.leave() }
                        if let text = data as? String {
                            self?.sharedText = text
                        }
                    }
                }
                // Files
                else if attachment.hasItemConformingToTypeIdentifier(UTType.data.identifier) {
                    group.enter()
                    attachment.loadItem(forTypeIdentifier: UTType.data.identifier, options: nil) { [weak self] data, _ in
                        defer { group.leave() }
                        if let url = data as? URL {
                            self?.sharedFiles.append(url)
                        }
                    }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            self?.finishProcessing()
        }
    }

    private func finishProcessing() {
        activityIndicator.stopAnimating()
        contentReady = true
        updatePreview()
        loadContacts()
    }

    private func updatePreview() {
        // Same `share.*` nouns the in-app picker uses, so "2 photos" reads the same
        // on both sides of the hand-off instead of English here and French there.
        func noun(_ count: Int, _ singular: String, _ plural: String, _ fs: String, _ fp: String) -> String {
            "\(count) " + (count == 1 ? ShareStrings.t(singular, fallback: fs)
                                       : ShareStrings.t(plural, fallback: fp))
        }
        var previewParts: [String] = []
        if !sharedImages.isEmpty { previewParts.append(noun(sharedImages.count, "share.image", "share.images", "image", "images")) }
        if !sharedVideos.isEmpty { previewParts.append(noun(sharedVideos.count, "share.video", "share.videos", "video", "videos")) }
        if !sharedURLs.isEmpty { previewParts.append(noun(1, "share.link", "share.link", "link", "link")) }
        if sharedText != nil { previewParts.append(ShareStrings.t("share.text", fallback: "text")) }
        if !sharedFiles.isEmpty { previewParts.append(noun(sharedFiles.count, "share.file", "share.files", "file", "files")) }

        // No "Sharing:" prefix any more — it needed a 35th translated string to say
        // what the sheet's own title already says. The list of what is being sent
        // is the useful half.
        previewLabel.text = previewParts.isEmpty
            ? ShareStrings.t("share.no_content_to_share", fallback: "No content to share")
            : previewParts.joined(separator: ", ")
    }

    // MARK: - Contacts (from App Group snapshot written by the main app)

    private func loadContacts() {
        var loaded: [ShareContact] = []
        if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            let url = containerURL.appendingPathComponent("contacts_snapshot.json")
            let avatarDir = containerURL.appendingPathComponent("share_avatars", isDirectory: true)
            if let data = try? Data(contentsOf: url),
               let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] {
                for entry in arr {
                    guard let pk = entry["publicKey"], !pk.isEmpty else { continue }
                    let name = entry["alias"] ?? entry["displayName"] ?? entry["firstName"] ?? shortKey(pk)
                    let last = entry["lastMessageAt"].flatMap(Double.init).map(Date.init(timeIntervalSince1970:))
                    var avatar: UIImage? = nil
                    if let file = entry["avatarFile"], !file.isEmpty,
                       let bytes = try? Data(contentsOf: avatarDir.appendingPathComponent(file)) {
                        avatar = UIImage(data: bytes)
                    }
                    loaded.append(ShareContact(publicKey: pk, name: name, lastMessageAt: last, avatar: avatar))
                }
            }
        }
        // 🔔 2026-08-18: MOST RECENTLY USED FIRST, not alphabetical.
        //
        // Someone opening this sheet is almost always sending to whoever they
        // were just talking to. Alphabetical order put that person wherever
        // their name happened to fall, so the common case cost a scroll and a
        // search every time.
        //
        // Contacts with no timestamp (an older snapshot, or a conversation with
        // no message yet) sort AFTER everyone dated, alphabetically among
        // themselves — they get a stable place instead of jumping to the top on
        // a nil-compares-first accident.
        loaded.sort { a, b in
            switch (a.lastMessageAt, b.lastMessageAt) {
            case let (l?, r?):  return l > r
            case (_?, nil):     return true
            case (nil, _?):     return false
            case (nil, nil):    return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
            }
        }
        allContacts = loaded
        filteredContacts = loaded

        let hasContacts = !loaded.isEmpty
        searchBar.isHidden = !hasContacts
        tableView.isHidden = !hasContacts
        emptyStateLabel.isHidden = hasContacts
        openAppButton.isHidden = hasContacts
        tableView.reloadData()
    }

    private func shortKey(_ key: String) -> String {
        guard key.count > 12 else { return key }
        return String(key.prefix(6)) + "…" + String(key.suffix(4))
    }

    // MARK: - Actions

    @objc private func cancelTapped() {
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    @objc private func backgroundTapped() {
        cancelTapped()
    }

    @objc private func openAppTapped() {
        // Fallback path: no cached contacts — queue content and open the app so
        // the user can pick a recipient there.
        saveContentToAppGroup(recipientPublicKey: nil)
        openMainApp()
    }

    private func selectContact(_ contact: ShareContact) {
        guard contentReady else { return }

        view.isUserInteractionEnabled = false
        searchBar.resignFirstResponder()
        searchBar.isHidden = true
        tableView.isHidden = true
        emptyStateLabel.isHidden = true
        openAppButton.isHidden = true
        previewLabel.numberOfLines = 0
        previewLabel.text = ShareStrings.t("share.sending", fallback: "Sending…")
        activityIndicator.startAnimating()

        // Snapshot on the main thread; the send runs off it.
        let peer = contact.publicKey
        let items = outgoingItems()
        let hasUnsendableAttachments = !sharedVideos.isEmpty || !sharedFiles.isEmpty

        // 🔴 __QUEUE_BEFORE_SENDING_2026_08_18__ Found by adversarial review.
        //
        // The queue used to be written only on the FALLBACK branch, after the
        // direct send had declined. So between "send started" and "send finished"
        // the payload existed nowhere durable — and iOS kills share extensions
        // without warning, especially while they hold a photo in memory and the
        // user swipes away. The window was small, the loss was total and silent,
        // and it was the one outcome that must never happen: the user shared a
        // photo, the sheet vanished, nothing arrived, nothing was queued.
        //
        // Now the content is on disk BEFORE the first byte goes out, and the
        // marker is REMOVED again on success. Worst case is therefore a duplicate
        // the app re-sends, never a photo that ceased to exist. A duplicate is a
        // nuisance; a lost photo is the bug.
        queue(items, recipientPublicKey: peer)

        Task {
            let outcome = await ShareViewController.attemptDirectSend(
                peer: peer,
                items: items,
                hasUnsendableAttachments: hasUnsendableAttachments)
            await MainActor.run { [weak self] in
                self?.finishSelection(outcome, peer: peer)
            }
        }
    }

    // MARK: - Direct send (the extension does the work itself)

    /// One thing to deliver, in the order the app's own queue path delivers them
    /// (`SharedContentHandler.sendToRecipient`: images, then the text, then each
    /// URL as its own message). Keeping the order and the granularity identical
    /// means a partial failure can requeue the exact remainder — no duplicate, no
    /// loss, and the same conversation transcript either way.
    private enum ShareItem {
        case image(Data)
        case text(String)
        case url(URL)
    }

    private enum DirectSendOutcome {
        /// Everything went out over v2. Nothing to queue, `pendingShareRecipient`
        /// must NOT be set — the app would send it a second time.
        case sent(msgIds: [String])
        /// Nothing, or only a prefix, was sent. `remaining` is what still has to
        /// travel via the App Group queue, plus the videos/files that never take
        /// the direct path at all.
        case fellBack(reason: String, remaining: [ShareItem])
    }

    /// Ceiling for ONE attachment on the direct path.
    ///
    /// `V2MessageRouter.sendFile` switches to the background `URLSession` at
    /// `backgroundUploadMinBytes`, and `V2BackgroundBlobUploader` builds that
    /// session with `URLSessionConfiguration.background(withIdentifier:)` and NO
    /// `sharedContainerIdentifier` — which an app extension cannot use, and whose
    /// identifier is already owned by the app process. Staying strictly below the
    /// threshold keeps every direct send on the foreground session, which
    /// completes inside the extension's own lifetime. Anything larger queues.
    private static var directSendMaxAttachmentBytes: Int {
        V2MessageRouter.backgroundUploadMinBytes - 1
    }

    /// Aggregate ceiling across all attachments in one share.
    ///
    /// Everything is already resident as `Data` (loaded by `processSharedContent`)
    /// and `OSHICryptoV2.encryptFile` materialises a second copy as chunks, so the
    /// peak is roughly 2×. A share extension is jetsammed around 120 MB, and being
    /// killed mid-send is the one outcome that loses the user's photo silently.
    private static let directSendMaxTotalBytes = 24 * 1024 * 1024

    /// Everything this share has to deliver, minus videos and arbitrary files —
    /// those never take the direct path (see `attemptDirectSend`).
    private func outgoingItems() -> [ShareItem] {
        var items: [ShareItem] = sharedImages.map { .image($0) }
        if let text = sharedText, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            items.append(.text(text))
        }
        items.append(contentsOf: sharedURLs.map { .url($0) })
        return items
    }

    /// Try to deliver `items` to `peer` over v2, from inside this extension.
    ///
    /// Every refusal and every throw returns `.fellBack` with the exact remainder,
    /// so the caller queues what did not go and nothing is ever lost. There is no
    /// path out of here that drops content.
    ///
    /// 🔒 LOCKING: this deliberately takes NO lock of its own.
    /// `V2MessageRouter.encrypt1to1` — which both `sendText` and `sendFile` go
    /// through — already wraps its ratchet load/advance/save in
    /// `V2SessionStore.withPeerSession(peer)`, i.e. `CrossProcessLock.forPeer(peer)`.
    /// That lock is documented NOT reentrant, and `CrossProcessLock.withLock`
    /// serialises on a per-instance `localQueue` via `sync` — so taking it here and
    /// again inside the router would DEADLOCK the extension outright, not fail.
    /// The read-modify-write is already atomic across processes; adding a second
    /// lock would only break it.
    private static func attemptDirectSend(peer: String,
                                          items: [ShareItem],
                                          hasUnsendableAttachments: Bool) async -> DirectSendOutcome {
        // ── Gate 1: things the router cannot stream ──────────────────────────
        // `sendFile` takes `Data`, and even its ≥16 MB "streaming" branch writes
        // that `Data` to disk before the first byte moves — the whole attachment
        // is resident either way. The router exposes no URL-taking entry point, so
        // a video or an arbitrary file would have to be read whole into a process
        // with a ~120 MB ceiling. Those queue, always.
        guard !hasUnsendableAttachments else {
            return .fellBack(reason: "video_or_file_needs_a_streaming_entry_point", remaining: items)
        }
        guard !items.isEmpty else {
            return .fellBack(reason: "nothing_to_send", remaining: [])
        }

        // ── Gate 2: size ─────────────────────────────────────────────────────
        var totalBytes = 0
        for case .image(let data) in items {
            guard data.count <= directSendMaxAttachmentBytes else {
                return .fellBack(reason: "attachment_\(data.count)B_over_foreground_upload_limit", remaining: items)
            }
            totalBytes += data.count
        }
        guard totalBytes <= directSendMaxTotalBytes else {
            return .fellBack(reason: "total_\(totalBytes)B_over_extension_memory_budget", remaining: items)
        }

        // ── Gate 3: an EXISTING ratchet session, readable from this process ───
        //
        // 🔴 The extension must never ESTABLISH a session. Two reasons, both
        // load-bearing:
        //
        //  1. `loadOrEstablish` → `establishInitiatorSession` → `ensureOwnKeyMaterial()`,
        //     which REGENERATES and persists this device's signed pre-key + the
        //     whole one-time pre-key pool when the stored material does not verify.
        //     The extension cannot publish the replacement bundle (that is
        //     `register()`, an app-launch job), so every peer that popped an OPK
        //     from the old generation would bootstrap against a private half this
        //     device just threw away — `RouterError.unknownOneTimePreKey`, silently,
        //     for as long as the app stays closed.
        //
        //  2. `V2SessionStore.saveSession` writes with `accessGroup: nil`, i.e. the
        //     writer's DEFAULT keychain group. That is `…Genesis` for the app and
        //     `…Genesis.shared` for this extension (its entitlement lists only the
        //     shared group). A session created here would land in a DIFFERENT
        //     keychain item than the app's, and the app's own `load(accessGroup: nil)`
        //     searches every reachable group and returns an unspecified one of the
        //     two — a ratchet that diverges depending on which copy answers.
        //
        // So: send only when a session is ALREADY there. `hasSession` is a local
        // keychain read, no network, and it reports a locked keychain as "absent",
        // which is exactly the answer we want (queue it, the app will send it).
        let store = V2SessionStore()
        guard store.hasSession(peerUserKey: peer) else {
            return .fellBack(reason: "no_v2_session_readable_from_the_extension", remaining: items)
        }

        // ── Send, in order, stopping at the first failure ────────────────────
        let router = V2MessageRouter()
        var msgIds: [String] = []
        for (index, item) in items.enumerated() {
            do {
                switch item {
                case .image(let data):
                    let (mime, ext) = imageWireFormat(data)
                    // "image" is `MediaManager.MediaType.image.rawValue`, which the
                    // receiver feeds straight back into `MediaManager.MediaType(rawValue:)`
                    // (`MessageManager+V2.swift`). A WIRE value — never localized.
                    // MediaManager itself is not in this target, hence the literal.
                    msgIds += try await router.sendFile(peer: peer,
                                                        data: data,
                                                        filename: "shared_image_\(index).\(ext)",
                                                        mime: mime,
                                                        mediaType: "image")
                case .text(let text):
                    msgIds += try await router.sendText(peer: peer, text)
                case .url(let url):
                    msgIds += try await router.sendText(peer: peer, url.absoluteString)
                }
            } catch {
                // Whatever the reason — no bundle for the peer, locked keychain,
                // network down, `CrossProcessLock` timeout because the app is
                // mid-send on the same conversation — the rest goes to the queue.
                return .fellBack(reason: "send_threw: \(error)",
                                 remaining: Array(items[index...]))
            }
        }

        // The relay does not push; the app fires this itself right after a v2 send
        // (`MessageManager.v2TrySend`). Best effort, never fatal — see the note on
        // `requestPushNotification`.
        await requestPushNotification(to: peer, messageId: msgIds.first ?? "")
        return .sent(msgIds: msgIds)
    }

    /// `(mime, file extension)` for image bytes, sniffed from the magic number.
    /// The receiver keys its bubble off `mediaType`, not off this, so a wrong
    /// guess would be cosmetic — but a `.jpg` name on HEIC bytes is exactly the
    /// kind of thing that breaks a re-share three apps later.
    private static func imageWireFormat(_ data: Data) -> (mime: String, ext: String) {
        let head = [UInt8](data.prefix(12))
        if head.count >= 3, head[0] == 0xFF, head[1] == 0xD8, head[2] == 0xFF {
            return ("image/jpeg", "jpg")
        }
        if head.count >= 8, head[0] == 0x89, head[1] == 0x50, head[2] == 0x4E, head[3] == 0x47 {
            return ("image/png", "png")
        }
        if head.count >= 6, head[0] == 0x47, head[1] == 0x49, head[2] == 0x46 {
            return ("image/gif", "gif")
        }
        // ISO-BMFF: "....ftyp" + a brand. heic/heix/mif1/msf1 are the HEIF family.
        if head.count >= 12, head[4] == 0x66, head[5] == 0x74, head[6] == 0x79, head[7] == 0x70 {
            let brand = String(bytes: head[8..<12], encoding: .ascii) ?? ""
            if brand.hasPrefix("hei") || brand == "mif1" || brand == "msf1" {
                return ("image/heic", "heic")
            }
        }
        return ("image/jpeg", "jpg")
    }

    /// Fire the APNs alert for a message this EXTENSION sent.
    ///
    /// The v2 relay does not push — `MessageManager.v2TrySend` sends this itself
    /// immediately after a successful v2 send, and without it a recipient with the
    /// app closed learns about the message only when they next open OSHI. Which is
    /// the exact failure this whole change exists to remove, moved to the other end.
    ///
    /// `VoIPPushManager` is not in this target (it pulls in PushKit, CallKit and a
    /// CXProvider for what is a 20-line signed POST), so the request is rebuilt
    /// here against the SAME canonical string `VoIPPushManager.signRequest` builds:
    /// `METHOD\npath\nsha256hex(body)\ntimestamp_ms`, signed with the shared
    /// "signingKey" Ed25519 item. Title/body are the app's own English wire
    /// strings; the recipient's device renders `bodyLocKey`/`titleLocKey` from its
    /// OWN bundle, in its OWN language — localizing them here would ship the
    /// SENDER's language to the receiver.
    ///
    /// ⚠️ Every failure is swallowed on purpose. The message is already on the
    /// relay; throwing would turn a delivered send into a queued one and deliver
    /// it twice.
    private static func requestPushNotification(to recipientPublicKey: String, messageId: String) async {
        guard let signKeyData = try? KeychainHelper.load(key: "signingKey"),
              let signingKey = try? Curve25519.Signing.PrivateKey(rawRepresentation: signKeyData),
              let senderKey = try? KeychainHelper.load(key: "privateKey"),
              let identity = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: senderKey),
              let url = URL(string: "https://oshi-messenger.com/push/send-push") else {
            OshiShareLog.warn("push skipped — no signing identity reachable")
            return
        }
        let me = identity.publicKey.rawRepresentation.base64EncodedString()
        let data: [String: String] = [
            "type": "message",
            "senderPublicKey": me,
            "senderKey": me,
            "messageId": messageId,
            "v2": "1",
            "bodyLocKey": "notification.push.body.encrypted",
            "titleLocKey": "notification.push.title.message",
        ]
        let payload: [String: Any] = [
            "recipientPublicKey": recipientPublicKey,
            "type": "message",
            "title": "New Message",
            "body": "You have a new encrypted message",
            "data": data,
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 5

        // __CALL_PUSH_AUTH_IOS_2026_09_23__ docs/CALL_PUSH_AUTH_CONTRACT.md §1-§3 row 4: path AS
        // SENT (percent-encoded, no query) — `/push/send-push` — over the exact body bytes, with
        // the Keychain `signingKey` (= the key v2 publication bound to `me`); actor read by the
        // server = data.senderPublicKey = `me`. Byte-identical to `HTTPRequestSignature.sign`
        // in the app target (not linked into this extension).
        let timestamp = String(Int(Date().timeIntervalSince1970 * 1000))
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        let signedPath = URLComponents(url: url, resolvingAgainstBaseURL: true)?.percentEncodedPath ?? url.path
        let canonical = "POST\n\(signedPath)\n\(bodyHash)\n\(timestamp)"
        if let signature = try? signingKey.signature(for: Data(canonical.utf8)) {
            request.setValue(signature.base64EncodedString(), forHTTPHeaderField: "x-oshi-signature")
            request.setValue(timestamp, forHTTPHeaderField: "x-oshi-timestamp")
            request.setValue(signingKey.publicKey.rawRepresentation.base64EncodedString(),
                             forHTTPHeaderField: "x-oshi-signing-pubkey")
        } else {
            OshiShareLog.warn("push: Ed25519 signing failed — sent unsigned (403 once enforce is on)")
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        request.setValue("OSHI-iOS/\(version)", forHTTPHeaderField: "User-Agent")

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            OshiShareLog.warn("push → HTTP \(code)")
        } catch {
            OshiShareLog.warn("push failed (message already delivered): \(error)")
        }
    }

    // MARK: - Outcome

    @MainActor
    private func finishSelection(_ outcome: DirectSendOutcome, peer: String) {
        activityIndicator.stopAnimating()

        switch outcome {
        case .sent(let msgIds):
            OshiShareLog.warn("sent from the extension (\(msgIds.count) envelope(s)) — nothing queued, pendingShareRecipient NOT set")
            recordDirectSend(peer: peer, msgIds: msgIds)
            previewLabel.text = ShareStrings.t("share.sent", fallback: "Sent!")
            // Nothing to hand off, so the app is NOT opened: the send is done and
            // yanking the user into OSHI would be a context switch for no reason.
            // The pause is so the confirmation is actually read before the sheet
            // goes away.
            // Sent for real — drop the safety copy so the app does not re-send it.
            clearQueuedContent()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            }

        case .fellBack(let reason, let remaining):
            OshiShareLog.warn("direct send declined (\(reason)) — queueing for the app")
            queue(remaining, recipientPublicKey: peer)
            // The honest instruction, and the only localized string that says it:
            // the content is safe, opening OSHI is what sends it.
            // ⚠️ A dedicated key ("Saved — it will be sent when you open OSHI")
            // would say it properly, but inventing one would ship English to 33
            // locales. Reported instead of invented.
            previewLabel.text = ShareStrings.t("share.open_oshi", fallback: "Open OSHI")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
                self?.openMainApp()
            }
        }
    }

    /// Durable record of what the extension sent on its own.
    ///
    /// ⚠️ NOTHING READS THIS YET. The app's chat transcript is built from
    /// `MessageManager.messages`, which lives in the app container and whose model
    /// types are not in this target — so a message sent from here reaches the
    /// RECIPIENT but does not appear in the SENDER's own conversation until
    /// `SharedContentHandler` learns to drain this key. Written anyway because it
    /// is the only trace that the send happened, it is metadata-only (a few
    /// hundred bytes, capped at 20 entries), and it makes that follow-up a reader
    /// rather than an archaeology exercise.
    private func recordDirectSend(peer: String, msgIds: [String]) {
        guard let defaults = UserDefaults(suiteName: appGroupIdentifier) else { return }
        var log = defaults.array(forKey: "shareExtensionSentLog") as? [[String: Any]] ?? []
        log.append([
            "recipient": peer,
            "msgIds": msgIds,
            "images": sharedImages.count,
            "sentAt": Date().timeIntervalSince1970,
        ])
        if log.count > 20 { log = Array(log.suffix(20)) }
        defaults.set(log, forKey: "shareExtensionSentLog")
    }

    // MARK: - App Group hand-off

    /// Queue exactly `items` (plus every video/file, which never take the direct
    /// path) for the app to send on next launch.
    /// Removes the safety copy written by `queue(_:recipientPublicKey:)` before a
    /// direct send. Called ONLY once the send is confirmed.
    ///
    /// The pair exists because iOS can kill a share extension at any moment: the
    /// content is durable from before the first byte leaves until after the last
    /// one lands. The cost of that guarantee is a possible duplicate if we are
    /// killed between the send completing and this call — which is the right side
    /// of the trade, because a duplicate is visible and a lost photo is not.
    private func clearQueuedContent() {
        guard let defaults = UserDefaults(suiteName: appGroupIdentifier) else { return }
        defaults.removeObject(forKey: "pendingSharedContent")
        defaults.removeObject(forKey: "sharedContentTimestamp")
        defaults.removeObject(forKey: "pendingShareRecipient")
        defaults.synchronize()
        removeQueueFile()   // __LEAK_FIX_2026_09_25__
    }

    /// __LEAK_FIX_2026_09_25__ The queued share now lives in a file, not in defaults.
    private func removeQueueFile() {
        guard let c = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) else { return }
        try? FileManager.default.removeItem(at: c.appendingPathComponent(SharedContentInfo.queueFileName))
    }

    private func queue(_ items: [ShareItem], recipientPublicKey: String?) {
        var images: [Data] = []
        var texts: [String] = []
        var urls: [URL] = []
        for item in items {
            switch item {
            case .image(let data): images.append(data)
            case .text(let text): texts.append(text)
            case .url(let url): urls.append(url)
            }
        }

        // Nothing survived to queue — write NOTHING. `SharedContentHandler` only
        // clears `pendingShareRecipient` inside its "there is content" branch, so
        // an empty payload with a recipient attached would leave that recipient
        // behind to be picked up by whatever is shared NEXT.
        if images.isEmpty && texts.isEmpty && urls.isEmpty
            && sharedVideos.isEmpty && sharedFiles.isEmpty {
            if let defaults = UserDefaults(suiteName: appGroupIdentifier) {
                defaults.removeObject(forKey: "pendingSharedContent")
                defaults.removeObject(forKey: "sharedContentTimestamp")
                defaults.removeObject(forKey: "pendingShareRecipient")
            }
            removeQueueFile()   // __LEAK_FIX_2026_09_25__
            return
        }

        saveContentToAppGroup(recipientPublicKey: recipientPublicKey,
                              images: images,
                              urls: urls,
                              text: texts.isEmpty ? nil : texts.joined(separator: "\n"))
    }

    private func saveContentToAppGroup(recipientPublicKey: String?,
                                       images: [Data]? = nil,
                                       urls: [URL]? = nil,
                                       text: String?? = nil) {
        guard let userDefaults = UserDefaults(suiteName: appGroupIdentifier) else { return }

        let images = images ?? sharedImages
        let urls = urls ?? sharedURLs
        let text = text ?? sharedText

        var contentInfo = SharedContentInfo()
        let timestamp = Date().timeIntervalSince1970

        if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            for (index, imageData) in images.enumerated() {
                let fileName = "shared_image_\(index)_\(timestamp).jpg"
                let fileURL = containerURL.appendingPathComponent(fileName)
                // __PLAINTEXT_LEFTOVERS_2026_09_24__ classe explicite; l'app supprime
                // ces `shared_*` une fois consommés (balayage au lancement sinon).
                try? imageData.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                contentInfo.imageFileNames.append(fileName)
            }
            for (index, videoURL) in sharedVideos.enumerated() {
                let fileName = "shared_video_\(index)_\(timestamp).\(videoURL.pathExtension)"
                let fileURL = containerURL.appendingPathComponent(fileName)
                try? FileManager.default.copyItem(at: videoURL, to: fileURL)
                try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                                       ofItemAtPath: fileURL.path)
                contentInfo.videoFileNames.append(fileName)
            }
            for (index, fileURL) in sharedFiles.enumerated() {
                let fileName = "shared_file_\(index)_\(timestamp)_\(fileURL.lastPathComponent)"
                let destURL = containerURL.appendingPathComponent(fileName)
                try? FileManager.default.copyItem(at: fileURL, to: destURL)
                try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                                       ofItemAtPath: destURL.path)
                contentInfo.fileNames.append(fileName)
            }
        }

        contentInfo.urls = urls.map { $0.absoluteString }
        contentInfo.text = text

        // __LEAK_FIX_2026_09_25__ The shared TEXT and URLs used to be written in clear
        // into the App Group UserDefaults plist (backed up to iCloud/Finder). They now go
        // to a file in the App Group container, file-protected and excluded from backup;
        // the app reads it (and still reads the old key). The media files get the same
        // backup exclusion.
        if let encoded = try? JSONEncoder().encode(contentInfo) {
            var wroteFile = false
            if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
                let url = containerURL.appendingPathComponent(SharedContentInfo.queueFileName)
                if (try? encoded.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil {
                    wroteFile = true
                    SharedContentInfo.excludeFromBackup(url)
                    userDefaults.removeObject(forKey: "pendingSharedContent")
                }
                for name in contentInfo.imageFileNames + contentInfo.videoFileNames + contentInfo.fileNames {
                    SharedContentInfo.excludeFromBackup(containerURL.appendingPathComponent(name))
                }
            }
            // Fallback only if the container write failed: the share must not be lost.
            if !wroteFile { userDefaults.set(encoded, forKey: "pendingSharedContent") }
            userDefaults.set(Date(), forKey: "sharedContentTimestamp")
        }

        // 🔔 The contact chosen right here in the share sheet. The main app reads
        // this and auto-routes the send to this recipient (no second pick).
        if let recipient = recipientPublicKey, !recipient.isEmpty {
            userDefaults.set(recipient, forKey: "pendingShareRecipient")
        } else {
            userDefaults.removeObject(forKey: "pendingShareRecipient")
        }
        userDefaults.synchronize()
    }

    private func openMainApp() {
        // Deep link target. The recipient travels via the App Group (above),
        // not the URL, so it works even if the URL is truncated/handled loosely.
        guard let url = URL(string: "oshi://share") else {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }

        // 🔴 __OPEN_HOST_APP_2026_08_18__ THE BUG: the responder-chain hack is dead.
        //
        // This walked the responder chain looking for `UIApplication`'s `openURL:`
        // and called it by selector. That trick has been progressively closed by
        // Apple and does nothing at all on current iOS: `UIApplication` is simply
        // not on an app extension's responder chain any more. So the content was
        // queued correctly, the extension dismissed itself, and NOTHING opened —
        // the photo sat in the App Group until the user happened to launch OSHI by
        // hand. Which is exactly the report: "I have to open the app for the photo
        // to start sending."
        //
        // 🔴 2026-08-18 UPDATE: `NSExtensionContext.open(_:)` does NOT work either.
        // Measured on a real device, both it and the responder walk fail, so this
        // whole function is a best-effort nudge and NOTHING may depend on it. That
        // is why `selectContact` now sends from inside the extension and this is
        // reached only on the fallback path, where the content is already safely
        // queued and the user has been told to open OSHI. The request is still
        // completed in the CALLBACK, not on a hopeful timer — tearing the extension
        // down before the open has been handed off is another way to lose it.
        guard let context = extensionContext else { return }
        context.open(url) { [weak self] opened in
            if !opened {
                OshiShareLog.warn("extensionContext.open failed — falling back to the responder chain")
                var responder: UIResponder? = self
                let selector = sel_registerName("openURL:")
                while let r = responder {
                    if r.responds(to: selector) {
                        r.perform(selector, with: url)
                        break
                    }
                    responder = r.next
                }
            }
            DispatchQueue.main.async {
                self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            }
        }
    }

    private enum OshiShareLog {
        static func warn(_ message: String) {
            NSLog("[OSHIShare] ⚠️ %@", message)
        }
    }

}

// MARK: - UITableView

extension ShareViewController: UITableViewDataSource, UITableViewDelegate {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return filteredContacts.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "contactCell", for: indexPath)
        let contact = filteredContacts[indexPath.row]
        var config = cell.defaultContentConfiguration()

        // The NAME is what someone is choosing between — give it the weight.
        // Literally: semibold, so the alias wins the row against the key below
        // it at a glance instead of sharing the row's weight with it.
        config.text = contact.name
        let bodyFont = UIFont.preferredFont(forTextStyle: .body)
        config.textProperties.font = UIFont.systemFont(ofSize: bodyFont.pointSize, weight: .semibold)
        config.textProperties.adjustsFontForContentSizeCategory = true
        config.textProperties.numberOfLines = 1

        // The key is a disambiguator for two contacts with the same name, not
        // something to read. Monospaced digits so the abbreviations line up
        // down the column instead of jittering, and secondary colour so it
        // recedes behind the name.
        config.secondaryText = shortKey(contact.publicKey)
        config.secondaryTextProperties.font = UIFont.monospacedSystemFont(
            ofSize: UIFont.preferredFont(forTextStyle: .caption1).pointSize,
            weight: .regular)
        config.secondaryTextProperties.adjustsFontForContentSizeCategory = true
        config.secondaryTextProperties.color = .secondaryLabel
        config.secondaryTextProperties.numberOfLines = 1

        config.textToSecondaryTextVerticalPadding = 2
        // The face, when the main app has one cached for this contact. Falls
        // back to the generic glyph rather than to initials: an initial drawn in
        // the app's blue looks like a real avatar, and two contacts sharing a
        // first letter would then look like the same person.
        let avatarSide: CGFloat = 40
        config.image = contact.avatar ?? UIImage(systemName: "person.crop.circle.fill")
        if contact.avatar == nil {
            config.imageProperties.tintColor = .systemBlue
        }
        config.imageProperties.maximumSize = CGSize(width: avatarSide, height: avatarSide)
        config.imageProperties.reservedLayoutSize = CGSize(width: avatarSide, height: avatarSide)
        config.imageProperties.cornerRadius = avatarSide / 2
        config.directionalLayoutMargins = .init(top: 10, leading: 0, bottom: 10, trailing: 0)

        cell.contentConfiguration = config
        cell.accessoryType = .disclosureIndicator
        // One spoken label instead of two fragments: VoiceOver otherwise reads
        // the name, pauses, then spells a truncated base64 key.
        cell.accessibilityLabel = contact.name
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.row < filteredContacts.count else { return }
        selectContact(filteredContacts[indexPath.row])
    }
}

// MARK: - UISearchBarDelegate

extension ShareViewController: UISearchBarDelegate {
    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) {
        if searchText.isEmpty {
            filteredContacts = allContacts
        } else {
            filteredContacts = allContacts.filter {
                $0.name.localizedCaseInsensitiveContains(searchText) ||
                $0.publicKey.localizedCaseInsensitiveContains(searchText)
            }
        }
        tableView.reloadData()
    }

    func searchBarSearchButtonClicked(_ searchBar: UISearchBar) {
        searchBar.resignFirstResponder()
    }
}

// MARK: - UIGestureRecognizerDelegate

extension ShareViewController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        // Only the dimmed backdrop dismisses; taps inside the sheet pass through.
        return touch.view == view
    }
}

// MARK: - Shared Content Info (Codable)

struct SharedContentInfo: Codable {
    /// __LEAK_FIX_2026_09_25__ App Group file carrying the queued share (was a
    /// UserDefaults key). `shared_` prefix: swept with the media by the app.
    static let queueFileName = "shared_pending_content.json"
    static func excludeFromBackup(_ url: URL) {
        var u = url
        var rv = URLResourceValues(); rv.isExcludedFromBackup = true
        try? u.setResourceValues(rv)
    }

    var imageFileNames: [String] = []
    var videoFileNames: [String] = []
    var urls: [String] = []
    var text: String?
    var fileNames: [String] = []
}
