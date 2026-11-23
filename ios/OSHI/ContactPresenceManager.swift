//
//  ContactPresenceManager.swift
//  OSHI - Contact Presence & Profile Sharing System
//  Tracks online/offline status and shares profile pictures between contacts
//

import Foundation
import SwiftUI
import Combine

// MARK: - Contact Presence Info

struct ContactPresence: Codable {
    let publicKey: String
    var lastSeen: Date
    var profileImageData: Data?
    var isOnline: Bool {
        // Consider online if seen within last hour
        return Date().timeIntervalSince(lastSeen) < 3600
    }
    var isRecentlyOnline: Bool {
        // Consider recently online if seen within last 5 minutes
        return Date().timeIntervalSince(lastSeen) < 300
    }
}

// MARK: - Profile Update Message (sent to contacts)

struct ProfileUpdatePayload: Codable {
    var type: String = "profile_update"
    let profileImageData: String? // Base64 encoded
    let lastSeen: Double
    var version: Int = 1

    /// This device's X-Wing (ML-KEM-768 + X25519) public key, base64.
    ///
    /// Optional, and absent on every client that predates the hybrid — which is
    /// exactly why it rides here rather than on the message header. JSON ignores
    /// keys it does not know, so an older build decodes this payload as it
    /// always did, and a newer build simply learns nothing about a peer that
    /// sends none and stays classical with them.
    var pqPublicKey: String? = nil

    /// This build understands group envelope v3 (CV-002 — no group key).
    ///
    /// Absent is read as "cannot", never as "probably": a wrong guess means that
    /// member silently stops receiving group messages, which is worse than
    /// staying on the old format one release longer.
    var groupEnvelopeV3: Bool? = nil

    /// This build holds sessions whose DH ratchet actually turns (CV-010).
    /// Absent means "cannot": a v3 session and a v2 one cannot exchange a single
    /// message, so guessing yes would cut the conversation dead.
    var ratchetV3: Bool? = nil

    /// `CFBundleShortVersionString` de l'expediteur ("1.0.44").
    ///
    /// __PEER_VERSION_2026_09_09__ Ajoute pour le changement de calendrier de
    /// cles du repondeur v3 (commit 0cfc8c9): un appareil a jour doit pouvoir
    /// DIRE a son correspondant reste en arriere que ses messages risquent de ne
    /// pas passer. C'est la seule voie qui existe deja entre deux telephones.
    ///
    /// COMPATIBLE ASCENDANT, et il faut dire pourquoi precisement: cette charge
    /// est du JSON, pas une chaine a separateurs. Cote iOS, l'`init(from:)`
    /// synthetise par Swift lit les cles qu'il connait et IGNORE les autres, et
    /// `encodeIfPresent` fait qu'un `nil` n'ecrit meme pas la cle. Cote Android,
    /// la charge est lue avec `org.json.JSONObject` et des accesseurs `opt*`,
    /// donc tolerante par construction. Une build 1.0.43 decode donc cette
    /// charge exactement comme avant. Verifie par `PeerVersionCompatTests`,
    /// qui rejoue le decodeur de la 1.0.43 sur une charge neuve.
    var appVersion: String? = nil

    /// A QUELLE ECHELLE appartient `appVersion` — `"ios"` ici, `"android"` en
    /// face.
    ///
    /// __POURQUOI CE CHAMP EXISTE, ET POURQUOI IL EST OBLIGATOIRE__
    /// iOS numerote cette app 1.0.x et Android 1.6.x. Les deux ecrivaient leur
    /// propre numero dans le MEME champ `appVersion`, et rien sur le fil ne
    /// disait de quelle echelle venait la chaine. Cote Kotlin, chaque pair iOS
    /// se retrouvait donc sous le seuil 1.6.14 et etait etiquete « version plus
    /// ancienne » — en permanence, pour chaque iPhone, sur une banniere dont
    /// tout le role est d'etre crue. Aucun seuil plus malin ne repare ca: il
    /// n'existe aucun nombre juste sur deux echelles independantes.
    ///
    /// Aussi additif que `appVersion` l'etait, et pour les memes raisons
    /// exactement (JSON + `encodeIfPresent` ici, `opt*` en face). Un lecteur
    /// 1.0.43 lit une charge qui porte les deux cles comme il lit une charge
    /// qui n'en porte aucune — verifie par `PeerVersionCompatTests`.
    var appPlatform: String? = nil

    /// Le pseudo que l'expediteur s'est choisi (Reglages → pseudo, `userName`).
    ///
    /// __SHARED_NICKNAME_2026_09_22__ Avant, ce pseudo ne quittait jamais le
    /// telephone: la charge ne portait AUCUN nom, donc chaque contact voyait
    /// un prefixe de cle tant qu'il n'avait pas tape un alias lui-meme.
    /// MEME CLE JSON sur les trois clients (iOS, Android, Desktop):
    /// `"displayName"`. Additif exactement comme `appVersion`: `encodeIfPresent`
    /// (nil = cle absente) ici, `opt*` cote Kotlin, donc une build plus
    /// ancienne lit cette charge comme avant. A la reception, TOUJOURS passer
    /// par `SharedNickname.sanitize` — c'est une chaine ecrite par le pair.
    /// Cle absente = « pas d'avis » (on garde ce qu'on avait); cle presente
    /// mais vide apres nettoyage = le pair a efface son pseudo.
    var displayName: String? = nil

    init(profileImageData: String?, lastSeen: Double,
         pqPublicKey: String? = nil, groupEnvelopeV3: Bool? = nil,
         ratchetV3: Bool? = nil, appVersion: String? = nil,
         appPlatform: String? = nil, displayName: String? = nil) {
        self.profileImageData = profileImageData
        self.lastSeen = lastSeen
        self.pqPublicKey = pqPublicKey
        self.groupEnvelopeV3 = groupEnvelopeV3
        self.ratchetV3 = ratchetV3
        self.appVersion = appVersion
        self.appPlatform = appPlatform
        self.displayName = displayName
    }
}

// MARK: - Shared nickname (peer-chosen name)

/// __SHARED_NICKNAME_2026_09_22__ Le pseudo PARTAGE par un contact, distinct de
/// l'ALIAS local (`contactAliases`), que l'utilisateur tape lui-meme et qui
/// l'emporte toujours. Regle d'affichage partout: alias > pseudo > prefixe de cle.
///
/// Stocke dans UserDefaults sous `contactSharedNicknames`, en `Data` JSON
/// `[cleNormalisee: pseudo]` — la meme forme que `contactAliases`, ce qui permet
/// aux vues de l'observer avec un `@AppStorage` et de se redessiner quand un
/// pseudo arrive. Cle = `MessageManager.normalizeAliasKey`, donc base64url /
/// padding ne font jamais rater un pseudo (meme piege que les alias en aout).
///
/// Un changement de pseudo ne NOTIFIE jamais: il voyage dans `📸PROFILE_UPDATE📸`
/// (`OSHIControlPayload.suppressesPush` = vrai), qui n'est ni stocke comme
/// message, ni annonce, ni transmis a la Watch.
enum SharedNickname {
    static let defaultsKey = "contactSharedNicknames"
    static let maxLength = 48

    /// Chaine du pair → pseudo affichable, ou nil. Retire les caracteres de
    /// controle (dont retours a la ligne), les separateurs de ligne/paragraphe
    /// et les forcages de direction (U+202A–202E, U+2066–2069, U+200E/200F),
    /// qui permettraient a un pseudo de retourner visuellement le texte qui
    /// l'entoure dans une notification ou un en-tete.
    static func sanitize(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let filtered = String(String.UnicodeScalarView(raw.unicodeScalars.filter { s in
            if s.properties.generalCategory == .control { return false }
            switch s.value {
            case 0x2028, 0x2029, 0x200E, 0x200F, 0x202A...0x202E, 0x2066...0x2069:
                return false
            default:
                return true
            }
        }))
        let trimmed = filtered.trimmingCharacters(in: .whitespacesAndNewlines)
        let capped = String(trimmed.prefix(maxLength))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return capped.isEmpty ? nil : capped
    }

    /// Precedence d'affichage, pure (testee par `StorageSelfTest`).
    static func resolve(alias: String?, nickname: String?) -> String? {
        if let a = alias, !a.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return a }
        if let n = nickname, !n.isEmpty { return n }
        return nil
    }

    static func decode(_ data: Data?) -> [String: String] {
        guard let data, !data.isEmpty,
              let map = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return map
    }

    /// Le pseudo partage par ce contact, depuis une table deja decodee (vues).
    static func nickname(for publicKey: String, in map: [String: String]) -> String? {
        guard !map.isEmpty else { return nil }
        let wanted = MessageManager.normalizeAliasKey(publicKey)
        guard !wanted.isEmpty else { return nil }
        if let hit = map[wanted], !hit.isEmpty { return hit }
        // Tables ecrites sous une autre orthographe de la cle: repli plie.
        for (k, v) in map where !v.isEmpty && MessageManager.normalizeAliasKey(k) == wanted {
            return v
        }
        return nil
    }

    /// Le pseudo partage par ce contact (lecture UserDefaults synchrone).
    static func nickname(for publicKey: String) -> String? {
        nickname(for: publicKey, in: decode(UserDefaults.standard.data(forKey: defaultsKey)))
    }

    /// Applique ce que dit une charge de profil. `wirePresent == false` (cle
    /// absente, client plus ancien) ne touche a rien.
    static func apply(_ wireValue: String?, wirePresent: Bool, for publicKey: String) {
        guard wirePresent else { return }
        let key = MessageManager.normalizeAliasKey(publicKey)
        guard !key.isEmpty else { return }
        var map = decode(UserDefaults.standard.data(forKey: defaultsKey))
        let clean = sanitize(wireValue)
        // Purge les anciennes orthographes de la meme cle avant d'ecrire.
        for k in map.keys where k != key && MessageManager.normalizeAliasKey(k) == key {
            map[k] = nil
        }
        guard map[key] != clean else { return }
        map[key] = clean
        if let data = try? JSONEncoder().encode(map) {
            UserDefaults.standard.set(data, forKey: defaultsKey)
        }
    }

    /// Notre propre pseudo, tel qu'il part sur le fil (nil = on n'en dit rien).
    static func ownForWire() -> String? {
        sanitize(UserDefaults.standard.string(forKey: "userName"))
    }
}

// MARK: - Contact Presence Manager

class ContactPresenceManager: ObservableObject {
    static let shared = ContactPresenceManager()
    
    @Published private(set) var presenceMap: [String: ContactPresence] = [:]
    
    private let presenceFileName = "contact_presence.json"
    /// Decoded-JPEG memo for `getProfileImage`. Deliberately NOT `@Published` —
    /// it is read from view bodies, and publishing there triggers the
    /// "Publishing changes from within view updates" runtime warning.
    private var decodedImageCache: [String: UIImage] = [:]
    private var updateTimer: Timer?
    private var cancellables = Set<AnyCancellable>()
    
    private init() {
        loadPresenceData()
        startPresenceUpdates()
    }
    
    // MARK: - Public API
    
    /// Check if a contact is online (seen within last hour)
    func isOnline(publicKey: String) -> Bool {
        return presenceMap[publicKey]?.isOnline ?? false
    }
    
    /// Check if a contact was recently online (within 5 minutes)
    func isRecentlyOnline(publicKey: String) -> Bool {
        return presenceMap[publicKey]?.isRecentlyOnline ?? false
    }
    
    /// Get last seen date for a contact
    func lastSeen(publicKey: String) -> Date? {
        return presenceMap[publicKey]?.lastSeen
    }
    
    /// Get profile image for a contact.
    ///
    /// 🔧 PERF FIX: this is called from `ConversationRow.body`, so it ran a full
    /// JPEG decode per row on EVERY re-render of the conversation list. Decoded
    /// images are memoised here (non-published, so reading it from a view body is
    /// safe); `updateProfileImage` invalidates the entry when the bytes change.
    func getProfileImage(publicKey: String) -> UIImage? {
        if let cached = decodedImageCache[publicKey] { return cached }
        guard let data = presenceMap[publicKey]?.profileImageData,
              let image = UIImage(data: data) else { return nil }
        decodedImageCache[publicKey] = image
        return image
    }
    
    /// Update contact's last seen time
    func updateLastSeen(publicKey: String) {
        if var presence = presenceMap[publicKey] {
            presence.lastSeen = Date()
            presenceMap[publicKey] = presence
        } else {
            presenceMap[publicKey] = ContactPresence(publicKey: publicKey, lastSeen: Date())
        }
        savePresenceData()
        objectWillChange.send()
    }
    
    /// Update contact's profile image
    func updateProfileImage(publicKey: String, imageData: Data?) {
        if var presence = presenceMap[publicKey] {
            presence.profileImageData = imageData
            presenceMap[publicKey] = presence
        } else {
            presenceMap[publicKey] = ContactPresence(
                publicKey: publicKey,
                lastSeen: Date(),
                profileImageData: imageData
            )
        }
        
        decodedImageCache[publicKey] = nil   // bytes changed — drop the memo

        // Also save to ContactPhotoManager for compatibility
        if let data = imageData, let image = UIImage(data: data) {
            _ = ContactPhotoManager.shared.savePhoto(image, for: publicKey)
        }

        savePresenceData()
        objectWillChange.send()
    }
    
    /// Process incoming profile update message
    func processProfileUpdate(from publicKey: String, payload: ProfileUpdatePayload) {
        var imageData: Data? = nil
        if let base64 = payload.profileImageData {
            imageData = Data(base64Encoded: base64)
        }

        if var presence = presenceMap[publicKey] {
            presence.lastSeen = Date(timeIntervalSince1970: payload.lastSeen)
            presence.profileImageData = imageData
            presenceMap[publicKey] = presence
        } else {
            presenceMap[publicKey] = ContactPresence(
                publicKey: publicKey,
                lastSeen: Date(timeIntervalSince1970: payload.lastSeen),
                profileImageData: imageData
            )
        }

        decodedImageCache[publicKey] = nil   // bytes changed — drop the memo

        // Also update ContactPhotoManager
        if let data = imageData, let image = UIImage(data: data) {
            _ = ContactPhotoManager.shared.savePhoto(image, for: publicKey)
        }

        savePresenceData()

        // 🔧 FIX: SwiftUI updates MUST happen on main thread
        DispatchQueue.main.async { [weak self] in
            self?.objectWillChange.send()
            // Post notification for UI updates
            NotificationCenter.default.post(name: .contactPresenceUpdated, object: publicKey)
        }

        OshiLog.messaging.info("✅ Profile update applied for \(publicKey.prefix(8))... (main thread dispatch)")
    }
    
    /// Create profile update payload to send to contacts
    func createProfileUpdatePayload() -> ProfileUpdatePayload {
        // 🔧 FIX: Read profile image directly from UserDefaults (same source as IdentityManager)
        // This ensures we get the actual saved profile image, not from a new instance
        let imageData = UserDefaults.standard.data(forKey: "profileImageData")
        let imageBase64 = imageData?.base64EncodedString()
        return ProfileUpdatePayload(
            profileImageData: imageBase64,
            lastSeen: Date().timeIntervalSince1970,
            // nil on iOS < 26 — the peer then keeps talking to us classically.
            pqPublicKey: PostQuantumIdentity.shared.publicKey()?.base64EncodedString(),
            groupEnvelopeV3: true,
            ratchetV3: true,
            // Chaine vide si le bundle ne le porte pas: on prefere ne rien dire
            // a dire quelque chose d'illisible que le pair devrait interpreter.
            appVersion: AppVersion.current.isEmpty ? nil : AppVersion.current,
            // La plateforme UNIQUEMENT a cote d'une version, jamais seule: un
            // tag sans numero ne dit rien, et ce serait du bruit sur le fil.
            // Meme regle que `ProfileVersionWire.stamp` cote Kotlin.
            appPlatform: AppVersion.current.isEmpty ? nil : RatchetInterop.platform,
            // __SHARED_NICKNAME_2026_09_22__ Le pseudo choisi dans Reglages.
            // Cette charge part aussi au premier contact et sur chaque
            // PROFILE_REQUEST, donc un nouveau contact le recoit sans attendre
            // que l'utilisateur le change.
            displayName: SharedNickname.ownForWire()
        )
    }
    
    /// 🔧 NEW: Broadcast profile update to all contacts via mesh and message system
    func broadcastProfileUpdateToContacts(via messageManager: Any?) {
        let payload = createProfileUpdatePayload()
        
        // Encode payload as JSON
        guard let payloadData = try? JSONEncoder().encode(payload) else {
            OshiLog.messaging.info("❌ Failed to encode profile update payload")
            return
        }
        
        // Post notification for the message system to handle broadcasting
        NotificationCenter.default.post(
            name: NSNotification.Name("BroadcastProfileUpdate"),
            object: nil,
            userInfo: [
                "payload": payloadData,
                "type": "profile_update"
            ]
        )
        
        OshiLog.messaging.info("📸 Profile update broadcast initiated")
    }
    
    /// Get status badge for a contact
    func getStatusBadge(publicKey: String) -> StatusBadge {
        guard let presence = presenceMap[publicKey] else {
            return .offline
        }
        
        if presence.isRecentlyOnline {
            return .online
        } else if presence.isOnline {
            return .recentlyActive
        } else {
            return .offline
        }
    }
    
    // MARK: - Private Methods
    
    /// 🔧 LAUNCH-FREEZE FIX: `contact_presence.json` embeds a full JPEG per contact
    /// (`ContactPresence.profileImageData`), so this is a multi-MB
    /// `Data(contentsOf:)` + `JSONDecoder` — and it ran from `private init()`, i.e.
    /// on the main thread the first time anything touched `.shared`, before the
    /// first frame. Presence is purely decorative on that frame, so read off-main
    /// and publish back.
    private func loadPresenceData() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self else { return }
            let url = self.getPresenceFileURL()
            // __REGRESSION_FIX_2026_09_23__ `contact_presence` is in
            // FileStorage.encryptedStoreKeys, so the launch migration seals this file
            // (OSHIENC1). Reading it as raw JSON emptied the presence map on every
            // launch. `open` passes legacy plaintext through unchanged.
            guard let raw = try? Data(contentsOf: url),
                  let data = try? AtRestCipher.shared.open(raw),
                  let decoded = try? JSONDecoder().decode([String: ContactPresence].self, from: data) else {
                return
            }
            DispatchQueue.main.async {
                self.presenceMap = decoded
            }
        }
    }
    
    private func savePresenceData() {
        let url = getPresenceFileURL()
        guard let data = try? JSONEncoder().encode(presenceMap) else { return }
        // __REGRESSION_FIX_2026_09_23__ write sealed, like every other encryptedStoreKeys store;
        // never fall back to plaintext if sealing fails (e.g. before first unlock).
        guard let sealed = try? AtRestCipher.shared.seal(data) else { return }
        try? sealed.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    
    private func getPresenceFileURL() -> URL {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documentsDir.appendingPathComponent(presenceFileName)
    }
    
    private func startPresenceUpdates() {
        // Update presence every 30 seconds
        updateTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.objectWillChange.send()
        }
    }
}

// MARK: - Status Badge Enum

enum StatusBadge {
    case online       // Green badge - seen within 5 minutes
    case recentlyActive  // Yellow/Orange badge - seen within 1 hour
    case offline      // Gray/no badge - not seen within 1 hour
    
    var color: Color {
        switch self {
        case .online: return .green
        case .recentlyActive: return .orange
        case .offline: return .gray
        }
    }
    
    var icon: String {
        switch self {
        case .online: return "circle.fill"
        case .recentlyActive: return "clock.fill"
        case .offline: return "circle"
        }
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let contactPresenceUpdated = Notification.Name("contactPresenceUpdated")
}

// MARK: - Contact Avatar View with Status Badge

struct ContactAvatarWithBadge: View {
    let publicKey: String
    let size: CGFloat
    let showBadge: Bool
    let displayName: String?

    @StateObject private var presenceManager = ContactPresenceManager.shared
    @StateObject private var photoManager = ContactPhotoManager.shared

    init(publicKey: String, size: CGFloat = 44, showBadge: Bool = true, displayName: String? = nil) {
        self.publicKey = publicKey
        self.size = size
        self.showBadge = showBadge
        self.displayName = displayName
    }

    // __ONE_AVATAR_NOT_THREE_2026_11_07__ The `initials` computed property that
    // lived here moved into `AvatarIdentity.initials(name:key:count:)`, where it
    // is pure, shared by every avatar in the app, and testable. Its rule is
    // unchanged: two initials from a two-word name, else the first two
    // characters of the name, else of the key.
    
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            // Avatar - 🔧 FIX: Contact's actual profile (from them) takes priority
            if let image = presenceManager.getProfileImage(publicKey: publicKey) ?? photoManager.getPhoto(for: publicKey) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else {
                // __ONE_AVATAR_NOT_THREE_2026_11_07__ was a hardcoded
                // `[.purple, .blue]` — the SAME gradient for everyone, and a
                // different one again from the conversation list's colour for
                // the same person. This component is used all over the app, so
                // it was the widest of the three disagreements.
                DefaultAvatar(key: publicKey, name: displayName, size: size)
            }
            
            // Status Badge
            if showBadge {
                let badge = presenceManager.getStatusBadge(publicKey: publicKey)
                if badge != .offline {
                    Circle()
                        .fill(badge.color)
                        .frame(width: size * 0.28, height: size * 0.28)
                        .overlay(
                            Circle()
                                .stroke(Color(.systemBackground), lineWidth: 2)
                        )
                        .offset(x: 2, y: 2)
                }
            }
        }
        .onAppear {
            photoManager.preloadPhoto(for: publicKey)
        }
    }
}

// MARK: - Last Seen Text View

struct LastSeenText: View {
    let publicKey: String
    
    @StateObject private var presenceManager = ContactPresenceManager.shared
    
    var body: some View {
        if let lastSeen = presenceManager.lastSeen(publicKey: publicKey) {
            Text(formatLastSeen(lastSeen))
                .font(.caption)
                .foregroundColor(.secondary)
        } else {
            Text("presence.unknown".localized)
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
    
    private func formatLastSeen(_ date: Date) -> String {
        let interval = Date().timeIntervalSince(date)
        
        if interval < 60 {
            return "presence.now".localized
        } else if interval < 3600 {
            let minutes = Int(interval / 60)
            return String(format: "presence.minutes_ago".localized, minutes)
        } else if interval < 86400 {
            let hours = Int(interval / 3600)
            return String(format: "presence.hours_ago".localized, hours)
        } else {
            let formatter = DateFormatter()
            formatter.dateStyle = .short
            formatter.timeStyle = .short
            return formatter.string(from: date)
        }
    }
}

// MARK: - Profile Image Picker for Settings

struct ProfileImagePicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) var dismiss
    
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        picker.sourceType = .photoLibrary
        picker.allowsEditing = true
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: ProfileImagePicker
        
        init(_ parent: ProfileImagePicker) {
            self.parent = parent
        }
        
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let edited = info[.editedImage] as? UIImage {
                parent.image = edited
            } else if let original = info[.originalImage] as? UIImage {
                parent.image = original
            }
            parent.dismiss()
        }
        
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

#if DEBUG
/// __SHARED_NICKNAME_2026_09_22__ Verifications du pseudo partage, jouees par
/// `StorageSelfTest` (`-OSHISelfTestStorage`, sortie `Documents/selftest_storage.txt`)
/// parce que le target OSHITests n'est pas synchronise au dossier.
enum SharedNicknameSelfTest {
    static func run() -> [String] {
        var lines: [String] = []
        func check(_ name: String, _ ok: Bool) { lines.append("\(ok ? "PASS" : "FAIL") nickname.\(name)") }

        // Nettoyage
        check("sanitize.trimNewline", SharedNickname.sanitize("  Alice\n") == "Alice")
        check("sanitize.innerNewline", SharedNickname.sanitize("Al\nice") == "Alice")
        check("sanitize.rlo", SharedNickname.sanitize("\u{202E}evil") == "evil")
        check("sanitize.isolates", SharedNickname.sanitize("a\u{2066}b\u{2069}c\u{200F}") == "abc")
        check("sanitize.control", SharedNickname.sanitize("\u{0007}Bob\u{0000}") == "Bob")
        check("sanitize.blank", SharedNickname.sanitize(" \n\t ") == nil)
        check("sanitize.nil", SharedNickname.sanitize(nil) == nil)
        check("sanitize.cap48", SharedNickname.sanitize(String(repeating: "x", count: 60))?.count == 48)
        let emoji = SharedNickname.sanitize(String(repeating: "👩‍👩‍👧", count: 60))
        check("sanitize.cap48.graphemes", emoji?.count == 48 && emoji.map { $0.unicodeScalars.count == 48 * 5 } == true)

        // Precedence
        check("resolve.aliasWins", SharedNickname.resolve(alias: "A", nickname: "N") == "A")
        check("resolve.nicknameFallback", SharedNickname.resolve(alias: nil, nickname: "N") == "N")
        check("resolve.blankAliasIgnored", SharedNickname.resolve(alias: "  ", nickname: "N") == "N")
        check("resolve.none", SharedNickname.resolve(alias: nil, nickname: nil) == nil)

        // Fil: compatibilite ascendante
        let old = #"{"type":"profile_update","profileImageData":null,"lastSeen":1.0,"version":1}"#
        let decodedOld = try? JSONDecoder().decode(ProfileUpdatePayload.self, from: Data(old.utf8))
        check("wire.oldPayloadDecodes", decodedOld != nil && decodedOld?.displayName == nil)
        let neu = #"{"type":"profile_update","lastSeen":1.0,"version":1,"displayName":"Zoé"}"#
        check("wire.newPayloadDecodes", (try? JSONDecoder().decode(ProfileUpdatePayload.self, from: Data(neu.utf8)))?.displayName == "Zoé")
        let noName = (try? JSONEncoder().encode(ProfileUpdatePayload(profileImageData: nil, lastSeen: 1)))
            .flatMap { String(data: $0, encoding: .utf8) } ?? ""
        check("wire.nilOmitted", !noName.contains("displayName"))
        let withName = (try? JSONEncoder().encode(ProfileUpdatePayload(profileImageData: nil, lastSeen: 1, displayName: "Zoé")))
            .flatMap { String(data: $0, encoding: .utf8) } ?? ""
        check("wire.keyIsDisplayName", withName.contains("\"displayName\":\"Zoé\""))

        // Stockage: cle synthetique (32 octets), orthographe base64url a la lecture
        let std = Data((0..<32).map { UInt8(($0 * 7 + 3) & 0xFF) }).base64EncodedString()
        let url = std.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        SharedNickname.apply("  Zoé\u{202E}  ", wirePresent: true, for: std)
        check("store.set", SharedNickname.nickname(for: std) == "Zoé")
        check("store.urlVariantLookup", SharedNickname.nickname(for: url) == "Zoé")
        check("store.resolverFallsBackToNickname",
              MessageManager.lookupContactAlias(std) != nil || MessageManager.resolveContactAlias(std) == "Zoé")
        SharedNickname.apply(nil, wirePresent: false, for: url)
        check("store.absentKeepsValue", SharedNickname.nickname(for: std) == "Zoé")
        SharedNickname.apply("   ", wirePresent: true, for: url)
        check("store.emptyClears", SharedNickname.nickname(for: std) == nil)
        check("store.aliasOnlyUntouched", MessageManager.lookupContactAlias(std) == nil)

        // Silence: la charge ne reveille personne
        check("silent.suppressesPush", OSHIControlPayload.suppressesPush("📸PROFILE_UPDATE📸" + neu))
        return lines
    }
}
#endif
