//
//  WelcomeMessageManager.swift
//  OSHI — the first thing a new account sees.
//
//  A brand-new identity opens the app to an empty list. This seeds one
//  conversation: a short greeting in the user's own language, followed by a
//  contact card for the OSHI author so they can start talking to a human in one
//  tap instead of having to find and scan a code first.
//
//  Everything is LOCAL. No server call, no network, nothing announced about the
//  new user to anyone — which matters for an app whose whole proposition is that
//  signing up tells nobody you exist. The greeting is generated on device from
//  the app's own localisation, and the contact card is the same media type the
//  share-a-contact flow already produces, so tapping it goes through the
//  existing, tested `StartChatWithContact` path rather than a new one.
//

import Foundation
import UIKit

@MainActor
final class WelcomeMessageManager {
    static let shared = WelcomeMessageManager()
    private init() {}

    /// The account new users are offered a conversation with.
    ///
    /// Public keys are public by definition — this is the same string that would
    /// be printed in a QR code on a business card, and it carries no secret.
    /// __WELCOME_IS_A_REAL_CONVERSATION_2026_10_16__ The owner's primary
    /// account — confirmed by him, and corroborated on the live call server:
    /// this is the identity his iPhone places and receives calls as
    /// (`VoIP push: identity=eAbMQUdW6Utu... devices=2`).
    ///
    /// Briefly pointed at `dMv1jp9Ytys6S4AEwFB9IVG723cDV0Ua0qzvEuGy+F4=`, which
    /// is ALSO a live account of his (both return a full prekey bundle from
    /// `/v2/keys/…` with `caps:3`) — but it is not the one on his phone, so
    /// new users writing to "Hugo" would have reached a mailbox he does not
    /// read. Changing this key changes who every new user talks to: verify
    /// against the phone, not against a link in an email signature.
    static let authorPublicKey = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="
    static let authorAlias = "Hugo"

    /// Sender of the greeting — now the author's real key.
    ///
    /// __WELCOME_IS_A_REAL_CONVERSATION_2026_10_16__ This was `"oshi.welcome"`,
    /// a string that is not a public key, on the reasoning that attributing an
    /// app-written greeting to a person "would be a small lie". The cost of
    /// that choice was larger than the lie it avoided: the thread opened a
    /// normal composer, the greeting said "click to start a conversation with
    /// me", and every reply was addressed to the literal text "oshi.welcome"
    /// and silently went nowhere. A new user's first action in OSHI failed in
    /// silence.
    ///
    /// The owner's decision is that this SHOULD be a real conversation: he is
    /// the creator, he is a person, and he wants to be reachable by anyone who
    /// needs help. So the greeting now comes from the account that can answer
    /// it, and `chat.welcome.creator_notice` states plainly, in all 34
    /// languages, who is on the other end — which is a better answer to the
    /// honesty concern than an unreachable sender was.
    static let systemSenderKey = authorPublicKey

    /// The key the greeting used to be attributed to, before it became a real
    /// conversation. Kept only so installs created under it can be migrated.
    static let legacySystemSenderKey = "oshi.welcome"

    /// __WELCOME_IS_A_REAL_CONVERSATION_2026_10_16__ Re-point an EXISTING
    /// welcome thread at the author's real account.
    ///
    /// Changing `systemSenderKey` only helps identities seeded AFTER the
    /// change: `hasSeeded` is already true for everyone else, so without this
    /// every current user keeps a thread addressed to the literal string
    /// "oshi.welcome" — it opens, it offers a composer, and nothing it sends
    /// can arrive. That is the exact complaint this came from, reported on a
    /// Mac whose install predated the fix.
    ///
    /// Rewrites the sender on the stored greeting, in both places it is held
    /// (the envelope and the `SecureMessage`), and moves the avatar across.
    /// Idempotent: once no message carries the legacy key, it does nothing.
    @MainActor
    static func migrateLegacyWelcomeThread(attempt: Int = 0) {
        // __MIGRATION_RAN_TOO_EARLY_2026_10_16__ `MessageManager.shared` is
        // assigned inside MessageManager's own init, which happens when SwiftUI
        // first builds the view hierarchy — AFTER `didFinishLaunching`, where
        // this is called from. The first version therefore found nil, returned
        // in silence, and the thread stayed broken; measured on a Mac where
        // `contacts_snapshot.json` still listed "oshi.welcome" after a relaunch.
        // An empty `messages` array means the store has not been read back yet,
        // which is the same race one step later.
        //
        // Bounded retry: ~10 s, then give up. Idempotent either way.
        let manager = MessageManager.shared
        // Entry trace: distinguishes "never called" from "called, matched
        // nothing". Without it, a silent no-op and a missing call site look
        // identical from outside — which cost three relaunches of guessing.
        UserDefaults.standard.set("attempt=\(attempt) mgr=\(manager != nil) msgs=\(manager?.messages.count ?? -1)",
                                  forKey: "welcomeMigration.lastAttempt")
        if manager == nil || manager?.messages.isEmpty == true {
            // 60 s, not 10. The Mac takes ~20 s to finish launching and the
            // encrypted store is read after that, so a 10 s window expired
            // before `messages` was ever populated — the migration gave up
            // before it could see a single row.
            if attempt < 60 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    migrateLegacyWelcomeThread(attempt: attempt + 1)
                }
            }
            return
        }
        guard let manager else { return }
        let legacy = legacySystemSenderKey
        var touched = 0
        // __BOTH_DIRECTIONS_2026_10_19__ The first version re-keyed only
        // `senderPublicKey` and matched 0 of 71 stored rows, because the rows
        // that survive are the user's OWN attempts — a text and a call placed
        // TO the welcome thread — where the legacy string is the RECIPIENT.
        // A conversation is keyed by the other party, which is the sender on an
        // inbound row and the recipient on an outbound one; migrating one side
        // only was always going to leave the thread on screen.
        for i in manager.messages.indices
        where manager.messages[i].recipientPublicKey == legacy {
            manager.messages[i].recipientPublicKey = authorPublicKey
            touched += 1
        }
        for i in manager.messages.indices
        where manager.messages[i].senderPublicKey == legacy {
            manager.messages[i].senderPublicKey = authorPublicKey
            // `EncryptedMessage.senderPublicKey` is a `let`, so the envelope is
            // rebuilt rather than mutated — same bytes otherwise.
            let old = manager.messages[i].encryptedContent
            manager.messages[i].encryptedContent = EncryptedMessage(
                ciphertext: old.ciphertext,
                signature: old.signature,
                senderPublicKey: authorPublicKey,
                timestamp: old.timestamp)
            touched += 1
        }
        if touched == 0 {
            UserDefaults.standard.set("ran, 0 legacy rows among \(manager.messages.count) msgs",
                                      forKey: "welcomeMigration.lastRun")
            return
        }
        manager.saveMessages()
        // __MIGRATION_SAVED_BUT_DID_NOT_SHOW_2026_10_19__ `saveMessages()` writes
        // the store and nothing else: the conversation LIST is a separate
        // @Published array that `updateConversations()` rebuilds, and that only
        // runs when a message is added or the app next loads. So the rewrite
        // landed on disk while the stale "oshi.welcome" row stayed on screen —
        // which is exactly what kept being observed after each relaunch.
        manager.refreshConversations()

        // Carry the alias over, or the re-keyed thread loses its "Hugo" label
        // and shows a raw public key instead. `contactAliases` is an
        // @AppStorage dictionary keyed by public key.
        if let data = UserDefaults.standard.data(forKey: "contactAliases"),
           var aliases = try? JSONDecoder().decode([String: String].self, from: data) {
            if aliases[authorPublicKey] == nil {
                aliases[authorPublicKey] = aliases[legacy] ?? authorAlias
            }
            aliases.removeValue(forKey: legacy)
            if let out = try? JSONEncoder().encode(aliases) {
                UserDefaults.standard.set(out, forKey: "contactAliases")
            }
        } else {
            // No alias map yet — seed one so the thread is named.
            if let out = try? JSONEncoder().encode([authorPublicKey: authorAlias]) {
                UserDefaults.standard.set(out, forKey: "contactAliases")
            }
        }

        // Carry the face over, or the migrated thread loses its avatar.
        if let img = ContactPresenceManager.shared.getProfileImage(publicKey: legacy),
           ContactPresenceManager.shared.getProfileImage(publicKey: authorPublicKey) == nil,
           let data = img.pngData() {
            ContactPresenceManager.shared.updateProfileImage(publicKey: authorPublicKey, imageData: data)
        }
        OshiLog.messaging.info("👋 welcome thread migrated to the author's real key (\(touched) message(s))")
        // A readable trace. OshiLog redacts interpolated values, so the only way
        // to tell from outside whether this ran — and how many rows it touched —
        // was to infer it from the UI. Three relaunches were spent doing exactly
        // that. One defaults key costs nothing and answers it directly.
        UserDefaults.standard.set("\(touched) msg(s) @ \(ISO8601DateFormatter().string(from: Date()))",
                                  forKey: "welcomeMigration.lastRun")
    }

    /// Seeded once per identity. Keyed by public key rather than a global flag,
    /// so someone who creates a second identity gets welcomed again — and
    /// someone RESTORING an existing identity does not, because their key is
    /// already marked.
    private static func seededKey(for publicKey: String) -> String {
        "oshi.welcome.seeded." + String(publicKey.prefix(24))
    }

    static func hasSeeded(for publicKey: String) -> Bool {
        UserDefaults.standard.bool(forKey: seededKey(for: publicKey))
    }

    /// Seed the welcome conversation for a freshly created identity.
    ///
    /// - Parameter isRestoredIdentity: true when the user imported a seed
    ///   phrase. A restore is not a new user — they have their history, and
    ///   greeting them again would be noise.
    func seedIfNeeded(for publicKey: String, isRestoredIdentity: Bool) {
        guard !publicKey.isEmpty else { return }
        guard !isRestoredIdentity else { return }
        guard !Self.hasSeeded(for: publicKey) else { return }

        // Mark BEFORE inserting. If the insert throws or the app is killed
        // mid-way, a duplicate greeting on every launch would be worse than no
        // greeting at all.
        UserDefaults.standard.set(true, forKey: Self.seededKey(for: publicKey))

        seedWelcomeAvatar()
        Task { await insert(recipientPublicKey: publicKey) }
    }

    private func insert(recipientPublicKey: String) async {
        guard let manager = MessageManager.shared else { return }

        let now = Date()
        // The greeting, then the card a moment later, so they read in order and
        // the card is the last thing on screen — the thing to act on.
        addTextMessage(Self.greetingBody, to: manager,
                       recipientPublicKey: recipientPublicKey, at: now)

        guard let card = try? await MediaManager().createContactCard(
            publicKey: Self.authorPublicKey, alias: Self.authorAlias) else {
            OshiLog.messaging.info("👋 Welcome: contact card could not be built — greeting sent alone")
            return
        }
        addContactMessage(card, to: manager,
                          recipientPublicKey: recipientPublicKey,
                          at: now.addingTimeInterval(1))
    }

    // MARK: Message construction

    /// Localised greeting. Falls back to the key's English value automatically
    /// when a language is missing a translation, which is how `.localized`
    /// already behaves everywhere else in the app.
    private static var greetingBody: String {
        "welcome.message.body".localized
    }

    private func envelope(id: String, plaintext: String, at date: Date,
                          recipientPublicKey: String) -> SecureMessage {
        SecureMessage(
            id: id,
            senderAddress: Self.authorAlias,
            recipientAddress: String(recipientPublicKey.prefix(10)),
            encryptedContent: EncryptedMessage(
                // Locally generated: there is no ciphertext because nothing
                // travelled. The plaintext field below is the message.
                ciphertext: "",
                signature: "oshi_welcome",
                senderPublicKey: Self.systemSenderKey,
                timestamp: date
            ),
            timestamp: date,
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: Self.systemSenderKey,
            recipientPublicKey: recipientPublicKey,
            plaintextContent: plaintext,
            deliveryMethod: nil          // never travelled, so no transport badge
        )
    }

    /// __WELCOME_LOGO_2026_08_14__ Give the greeting a face.
    ///
    /// The message is sent by `systemSenderKey` ("oshi.welcome"), which is not a
    /// person and therefore had no profile picture — so the first thing a brand
    /// new user saw was a grey initial. The app icon is already in the bundle;
    /// reusing it costs nothing and needs no new asset. Read through
    /// `CFBundleIcons` rather than a hardcoded name so a future icon rename
    /// cannot silently blank this again.
    private func seedWelcomeAvatar() {
        guard ContactPresenceManager.shared.getProfileImage(publicKey: Self.systemSenderKey) == nil else { return }
        let name: String? = {
            if let icons = Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any],
               let primary = icons["CFBundlePrimaryIcon"] as? [String: Any],
               let files = primary["CFBundleIconFiles"] as? [String],
               let last = files.last {
                return last
            }
            return "AppIcon60x60"
        }()
        guard let name, let icon = UIImage(named: name),
              let data = icon.pngData() else {
            OshiLog.messaging.info("⚠️ welcome avatar: app icon not loadable — greeting keeps its initial")
            return
        }
        ContactPresenceManager.shared.updateProfileImage(publicKey: Self.systemSenderKey, imageData: data)
    }

    private func addTextMessage(_ text: String, to manager: MessageManager,
                                recipientPublicKey: String, at date: Date) {
        let msg = envelope(id: "oshi-welcome-text-" + String(recipientPublicKey.prefix(8)),
                           plaintext: text, at: date,
                           recipientPublicKey: recipientPublicKey)
        manager.addMessage(msg)
    }

    private func addContactMessage(_ card: MediaManager.MediaAttachment,
                                   to manager: MessageManager,
                                   recipientPublicKey: String, at date: Date) {
        var msg = envelope(id: "oshi-welcome-card-" + String(recipientPublicKey.prefix(8)),
                           plaintext: "welcome.message.card_caption".localized, at: date,
                           recipientPublicKey: recipientPublicKey)
        msg.mediaAttachment = card.data
        msg.mediaType = .contact
        msg.mediaFileName = card.fileName
        manager.addMessage(msg)
        OshiLog.messaging.info("👋 Welcome conversation seeded for \(recipientPublicKey.prefix(8))…")
    }
}
