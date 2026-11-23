//
//  NotificationService.swift
//  OSHINotificationService
//
//  iOS Notification Service Extension — runs briefly when each APNs push
//  arrives with `mutable-content: 1`, INDEPENDENT of whether the main
//  OSHI app is suspended, backgrounded, or even force-quit. Gives us up
//  to 30 seconds to:
//
//    1. Fetch the encrypted IPFS blob from oshi-messenger.com
//    2. Save it to the shared App Group container so the main app reads
//       it instantly on next open (no extra HTTP roundtrip)
//    3. Hit /api/pending so the server's fetchedBy tracking knows we
//       grabbed the hash → 30s repeat push suppressed → no redundant
//       second banner
//    4. Decorate the notification banner with the sender's display name
//       looked up from a shared contacts JSON snapshot
//
//  We do NOT decrypt the message in this Phase 1 NSE — the encrypted
//  envelope is staged in the App Group; the main app decrypts on open.
//  Decryption requires sharing IdentityManager's private keys via a
//  shared keychain access group (kSecAttrAccessGroup) which is a
//  separate refactor (Phase 2).
//
//  IMPORTANT — App Group: `group.Moriceau-lab.Genesis`. Configured on
//  both the main app and this extension target. Without it the
//  containerURL(forSecurityApplicationGroupIdentifier:) returns nil.
//

import UserNotifications
import os.log

final class NotificationService: UNNotificationServiceExtension {

    private static let appGroupID = "group.Moriceau-lab.Genesis"
    // 🔧 2026-07-17: was "https://oshi-messenger.com/pinata", which is a **404** — that
    // route does not exist, so every NSE prefetch silently failed ("all gateways failed")
    // and the two public-gateway fallbacks behind it were dead too. This is the route the
    // main app uses (MessageManager.vpsIPFSProxyURL) and it is verified serving 200.
    private static let ipfsProxyBaseURL = "https://oshi-messenger.com/api/ipfs/proxy"
    private static let vpsBaseURL = "https://oshi-messenger.com"

    /// Held across the async fetch so `serviceExtensionTimeWillExpire`
    /// can call it with whatever we've got if iOS yanks us at 30s.
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttemptContent: UNMutableNotificationContent?
    /// __BLOCKED_NOTIF_2026_09_23__ identifier of the push being handled, so a
    /// downgraded (not suppressible) notification can be withdrawn afterwards.
    private var currentRequestId: String?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        self.currentRequestId = request.identifier
        guard let bestAttempt = (request.content.mutableCopy() as? UNMutableNotificationContent) else {
            contentHandler(request.content)
            return
        }
        self.bestAttemptContent = bestAttempt

        let userInfo = request.content.userInfo
        // V2 relay messages carry no IPFS CID — only a messageId UUID. The old
        // `ipfsHash ?? messageId` fallback made the extension treat that UUID as
        // a CID and burn its entire 30 s budget on doomed gateway fetches, so
        // read the CID strictly and gate the legacy prefetch on the v2 flag.
        let isV2 = (userInfo["v2"] as? String) == "1"
        let ipfsHash = (userInfo["ipfsHash"] as? String) ?? ""
        // __BLOCKED_NOTIF_2026_09_23__ every key a sender can arrive under (a call
        // summary carries `callerPublicKey`), not just the two message spellings.
        let senderPublicKey = MessageBannerPolicy.sender(of: userInfo) ?? ""
        let type = (userInfo["type"] as? String) ?? "message"

        os_log("📬 NSE.didReceive type=%{public}@ v2=%{public}@ hash=%{public}@ sender=%{public}@",
               log: .default, type: .info, type, isV2 ? "1" : "0", ipfsHash.prefix(12) as CVarArg, senderPublicKey.prefix(12) as CVarArg)

        // __OWN_DEVICE_ECHO_2026_08_27__ Un message que J'AI envoyé depuis un
        // AUTRE de mes appareils ne doit pas me notifier.
        //
        // SIGNALÉ: « je reçois encore des notifications de l'iPhone 14 sur le
        // 17. » Les deux téléphones partagent la même clé publique, donc le VPS
        // pousse à tous les appareils enregistrés pour cette clé — y compris
        // celui qui vient d'envoyer.
        //
        // POURQUOI LE GARDE DE L'APP NE SUFFIT PAS
        // `MessageManager` a déjà exactement ce test — « ⏭️ Skipping own sent
        // message », `message.senderPublicKey == publicKey`. Mais il tourne
        // quand l'APP traite le message, et la bannière, elle, est dessinée par
        // CETTE extension bien avant. Le garde était au bon endroit pour la
        // liste des messages, et au mauvais pour la notification.
        //
        // Rendre un contenu VIDE est la façon documentée de supprimer une
        // bannière depuis une extension: iOS ne présente rien. On ne peut pas
        // « ne pas appeler » le gestionnaire — PushKit et UN exigent une
        // réponse.
        //
        // ⚠️ Ce n'est PAS une décision sur ce qui mérite d'être notifié: c'est
        // uniquement « ce message vient de moi ». Un message d'un tiers, même
        // sur un appareil secondaire, passe comme avant.
        if !senderPublicKey.isEmpty,
           let mine = Self.recipientPublicKeyFromSharedIdentity(),
           Self.base64urlEquivalent(senderPublicKey, mine) {
            os_log("🔇 NSE: suppressing own-device echo (sender == me)", log: .default, type: .info)
            suppressDisplay(contentHandler)
            return
        }

        // 🚫 BLOCKED SENDER. The main app checked this in a dozen places, but none
        // of them run here: the extension is a separate process and could not read
        // the app's blocked-contacts file, so blocking someone silenced them inside
        // the app while their pushes kept arriving. The main app now mirrors the
        // list into the App Group on every change.
        //
        // Suppression uses the same empty-content handoff as the own-echo and
        // silent-wake cases above — the established way this extension drops a
        // push without showing anything.
        if !senderPublicKey.isEmpty, Self.isBlockedSender(senderPublicKey) {
            os_log("🚫 NSE: suppressing push from blocked sender", log: .default, type: .info)
            suppressDisplay(contentHandler)
            return
        }

        // __GROUP_MUTE_BLOCK_IN_NSE_2026_09_23__ MUTED or BLOCKED group. Muting
        // only silenced the app's LOCAL banner; this extension rendered the APNs
        // one regardless, so a muted group still rang whenever the app was in the
        // background. Messages still arrive (the app pulls them); only the
        // banner/sound/badge is dropped. Fails open on a missing snapshot.
        let pushGroupId = (userInfo["groupId"] as? String) ?? ""
        if !pushGroupId.isEmpty, Self.isSilencedGroup(pushGroupId) {
            os_log("🔕 NSE: suppressing push for muted/blocked group", log: .default, type: .info)
            suppressDisplay(contentHandler)
            return
        }

        // __ANATOLE_1311_IOS__ (N8) A 1:1 conversation the user MUTED from the
        // chat list. Same handoff as a blocked sender: the message still arrives
        // (the app pulls it), only the banner is dropped. Calls are never muted
        // here — a ring is not a message banner.
        let isCallPush = type.lowercased().contains("call") || userInfo["callId"] != nil
            || userInfo["callerPublicKey"] != nil
        if pushGroupId.isEmpty, !isCallPush, !senderPublicKey.isEmpty, Self.isMutedSender(senderPublicKey) {
            os_log("🔕 NSE: suppressing push for muted conversation", log: .default, type: .info)
            suppressDisplay(contentHandler)
            return
        }

        // __RATCHET_HEAL_OVER_V2_2026_08_28__ Un accusé de réparation de ratchet
        // n'est pas un message: c'est de la plomberie qui répare la session, et
        // il ne doit RIEN afficher.
        //
        // Sans ce garde il produirait une bannière sans message à l'ouverture —
        // c'est-à-dire EXACTEMENT le symptôme qu'il vient réparer.
        //
        // Le marqueur est sur l'IDENTIFIANT, en clair, et pas dans le contenu:
        // cette extension ne déchiffre pas (délibérément — déchiffrer ici
        // désynchronise le ratchet, cf. le commentaire plus bas), donc un
        // marqueur chiffré lui serait invisible.
        //
        // `RatchetHealSignal.swift` est COMPILÉ DANS CETTE CIBLE (il n'importe
        // que Foundation), plutôt que recopié ici. Un préfixe recopié dérive, et
        // le jour où il dérive l'accusé recommence à afficher une bannière sans
        // message — sans qu'aucun test ne puisse le voir.
        let incomingMsgId = (userInfo["messageId"] as? String) ?? ""
        if RatchetHealSignal.isHealMsgId(incomingMsgId) {
            os_log("🔇 NSE: suppressing ratchet heal signal (not a message)", log: .default, type: .info)
            suppressDisplay(contentHandler)
            return
        }

        // __PHANTOM_BANNER_2026_10_11__ Une poussée RÉPÉTÉE pour un message que
        // l'app a déjà essayé d'ouvrir et n'a pas pu.
        //
        // SIGNALÉ (avis App Store 10/10, 5★, FR): « les notifications excessives
        // alors que je n'ai rien reçu ».
        //
        // Cette extension ne déchiffre pas — c'est délibéré (déchiffrer ici
        // avancerait le ratchet persisté et l'app échouerait ensuite sur le même
        // seq). Elle ne peut donc PAS savoir, seule, s'il y a un message derrière
        // la poussée: elle dessine la bannière, puis l'app se réveille, essaie, et
        // gare le message en attente de ratchet. La PREMIÈRE bannière est donc
        // inévitable et l'app la retire après coup
        // (`NotificationManager.withdrawBanners`). Mais le serveur re-pousse le
        // même message tant qu'il n'est pas acquitté, et chaque re-poussée
        // sonnait à nouveau — pour un message qui, lui, n'arrivait toujours pas.
        //
        // `undecryptable_snapshot.json` est ce que l'app rapporte de ses essais.
        // Un id dedans = « celui-là a déjà sonné et n'a rien montré ». On le tait.
        // Échoue OUVERT sur un instantané absent (installation neuve).
        let parkedMsgId = (userInfo["messageId"] as? String) ?? ""
        if !parkedMsgId.isEmpty, Self.isParkedUndecryptable(parkedMsgId) {
            os_log("🔇 NSE: suppressing repeat push for a message already parked undecryptable", log: .default, type: .info)
            suppressDisplay(contentHandler)
            return
        }

        // __SILENT_FLAG_WAS_DECORATION_2026_09_02__ Une poussée marquée
        // `silent` est un RÉVEIL, pas une notification.
        //
        // SIGNALÉ: « l'iPhone 14, app ouverte, fait dire à l'iPhone 17 qu'il a
        // reçu un message de l'iPhone 14 — et aucun message n'arrive. » Ouvrir
        // un fil de groupe déclenche `GroupMessaging.requestMemberSync`, qui
        // pousse chaque membre en type `group_update`, titre VIDE, corps VIDE et
        // `silent: "true"`, sous le commentaire « silent push … no visible
        // notification ». Personne ne lisait ce drapeau: le serveur retombait sur
        // `title || 'OSHI'` / `body || 'New message'` (une chaîne vide est
        // falsy) et `sendMessagePush` n'a AUCUNE branche silencieuse — il
        // construit toujours aps.alert + son + pastille. Puis, quelques lignes
        // plus bas, on remplace le titre par le nom de l'EXPÉDITEUR: d'où une
        // bannière au nom de l'autre téléphone. La demande de sync, elle, part
        // en `/api/queue?nopush=1` et arrive par le sondage: la bannière n'a
        // jamais rien eu derrière elle.
        //
        // Le vrai correctif est côté serveur (`push_service.js`, même marqueur):
        // content-available SEUL, en `apns-push-type: background`. Ce garde-ci
        // existe parce que l'app se met à jour SANS le serveur: tant qu'un VPS
        // non redéployé envoie encore une alerte, elle ne doit plus s'afficher
        // ici. Quand le serveur est à jour, la poussée devient un background
        // push et cette extension n'est même plus réveillée — les deux correctifs
        // ne se marchent pas dessus.
        //
        // Rendre un contenu VIDE supprime la présentation (ni bannière, ni son,
        // ni pastille); le corps de l'app reste réveillé par
        // `didReceiveRemoteNotification`, qui relance le sondage. Un vrai message
        // ne porte pas ce drapeau: seul un appelant qui demande un réveil le pose.
        if (userInfo["silent"] as? String) == "true" {
            os_log("🔇 NSE: suppressing silent wake push type=%{public}@ (no message behind it)",
                   log: .default, type: .info, type)
            suppressDisplay(contentHandler)
            return
        }

        // Decorate the banner with the sender's display name immediately
        // (synchronous, no network) so even if our fetch fails or times out the
        // user still sees "Hugo" instead of "OSHI". For V2 this IS the whole
        // job: we deliberately do NOT decrypt the relay message here. Decrypting
        // in the extension would advance the persisted Double-Ratchet state, and
        // the main app re-pulling the same seq on open would then fail to
        // decrypt (desync). The body stays a generic encrypted-message string
        // (no plaintext in the push — the server never sees content), and the
        // app decrypts on its next pull. Content-preview would require leaking
        // plaintext to the server, which E2E forbids.
        var resolvedSenderName: String?
        // __NOTIFICATION_NAMES_HIDDEN_2026_11_20__ "Show Preview" off in the app:
        // keep the server's generic title, resolve no contact or group name.
        let showsNames = MessageBannerPolicy.showsNames()
        if showsNames, !senderPublicKey.isEmpty {
            let resolvedName = Self.lookupSenderName(senderPublicKey: senderPublicKey)
            if let resolvedName, !resolvedName.isEmpty {
                bestAttempt.title = resolvedName
                resolvedSenderName = resolvedName
            }
        }

        // __GROUP_NAME_IN_NSE_2026_09_23__ A group push carries only the opaque
        // `groupId` — the server forces "OSHI" / "new encrypted message" on every
        // non-call push so the group name never reaches Apple. The name is
        // resolved HERE, from the snapshot the app writes to the App Group
        // (`AppGroupBridge.writeGroupsSnapshot`). No network. Title = group,
        // subtitle = sender when the sender is a contact (bots are not).
        // Unknown group (snapshot missing, not yet synced): banner unchanged.
        let groupId = (userInfo["groupId"] as? String) ?? ""
        if !groupId.isEmpty, !showsNames {
            bestAttempt.threadIdentifier = groupId.uppercased()
        } else if !groupId.isEmpty, let groupName = Self.lookupGroupName(groupId: groupId) {
            bestAttempt.title = groupName
            if let resolvedSenderName { bestAttempt.subtitle = resolvedSenderName }
            bestAttempt.threadIdentifier = groupId.uppercased()
        }

        // __ONE_BANNER_PER_MESSAGE_2026_09_23__ The body of a MESSAGE banner is
        // ALWAYS the generic localized sentence — never text, whatever an old
        // server or a legacy sender path put in `aps.alert.body`. Resolved at
        // delivery time from the APP bundle's Localizable.strings (same key as
        // the server's loc-key), since this extension ships no strings of its own.
        let isCallish = type.lowercased().contains("call") || userInfo["callId"] != nil
            || userInfo["callerPublicKey"] != nil
        let isMessageBanner = !isCallish
            && (MessageBannerPolicy.messageTypes.contains(type) || !groupId.isEmpty)
        if isMessageBanner {
            bestAttempt.body = NSString.localizedUserNotificationString(
                forKey: MessageBannerPolicy.genericBodyKey, arguments: nil)
        }

        // V2: nothing to fetch (relay is non-destructive; the app pulls on
        // open) and the legacy /api/pending fetchedBy tracking doesn't apply.
        // Deliver the decorated banner immediately instead of stalling.
        if isV2 {
            deliverUnlessLocalCovers(bestAttempt, isMessageBanner: isMessageBanner,
                                     messageId: incomingMsgId, userInfo: userInfo)
            return
        }

        // Legacy IPFS path: run prefetch + /api/pending update concurrently —
        // both have a ~25s budget before iOS yanks us at 30s.
        Task {
            await withTaskGroup(of: Void.self) { group in
                if !ipfsHash.isEmpty && !ipfsHash.hasPrefix("fallback_") {
                    group.addTask {
                        await Self.prefetchAndCache(ipfsHash: ipfsHash)
                    }
                }
                // Hit /api/pending so the server's fetchedBy tracking sees
                // we grabbed this hash → 30s repeat push suppressed.
                if let recipient = Self.recipientPublicKeyFromSharedIdentity() {
                    group.addTask {
                        await Self.markFetched(recipientPubKey: recipient)
                    }
                }
            }
            self.deliverUnlessLocalCovers(bestAttempt, isMessageBanner: isMessageBanner,
                                          messageId: incomingMsgId, userInfo: userInfo)
        }
    }

    /// __ONE_BANNER_PER_MESSAGE_2026_09_23__ If the app already posted its LOCAL
    /// banner for this message (same id) or this conversation a few seconds ago
    /// (it woke first, via the socket or content-available), this remote banner
    /// would be the second one for the same message — hand back empty content,
    /// the documented way to present nothing. Symmetric to the app-side check
    /// (`MessageBannerPolicy.remoteBannerCovers`).
    private func deliverUnlessLocalCovers(_ content: UNMutableNotificationContent,
                                          isMessageBanner: Bool,
                                          messageId: String,
                                          userInfo: [AnyHashable: Any]) {
        guard isMessageBanner else {
            contentHandler?(content)
            return
        }
        let convKey = MessageBannerPolicy.conversationKey(userInfo)
        UNUserNotificationCenter.current().getDeliveredNotifications { [weak self] delivered in
            let snapshot = delivered.map {
                MessageBannerPolicy.Delivered(identifier: $0.request.identifier,
                                              userInfo: $0.request.content.userInfo,
                                              date: $0.date)
            }
            if MessageBannerPolicy.localBannerCovers(messageId: messageId,
                                                     conversationKey: convKey,
                                                     delivered: snapshot) {
                os_log("🔕 NSE: app already posted the banner for this message — suppressing duplicate",
                       log: .default, type: .info)
                if let h = self?.contentHandler { self?.suppressDisplay(h) }
            } else {
                self?.contentHandler?(content)
            }
        }
    }

    override func serviceExtensionTimeWillExpire() {
        os_log("⌛ NSE.timeWillExpire — flushing whatever we have", log: .default, type: .info)
        if let contentHandler, let bestAttemptContent {
            contentHandler(bestAttemptContent)
        }
    }

    // MARK: - Shared App Group helpers

    private static func sharedContainerURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)
    }

    /// Looks up `senderPublicKey` in the contacts snapshot the main app
    /// writes to the App Group on every contacts mutation. Returns nil if
    /// the snapshot is missing or the sender isn't a known contact.
    private static func lookupSenderName(senderPublicKey: String) -> String? {
        guard let container = sharedContainerURL() else { return nil }
        let snapshotURL = container.appendingPathComponent("contacts_snapshot.json")
        guard let data = try? Data(contentsOf: snapshotURL),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return nil
        }
        for contact in array {
            guard let pk = contact["publicKey"] as? String else { continue }
            if pk == senderPublicKey || base64urlEquivalent(pk, senderPublicKey) {
                // Prefer alias → displayName → first name → nothing
                if let alias = contact["alias"] as? String, !alias.isEmpty { return alias }
                if let displayName = contact["displayName"] as? String, !displayName.isEmpty { return displayName }
                if let firstName = contact["firstName"] as? String, !firstName.isEmpty { return firstName }
            }
        }
        return nil
    }

    /// __GROUP_NAME_IN_NSE_2026_09_23__ `groups_snapshot.json` =
    /// `[{groupId, name}]`. UUIDs compared case-insensitively (Android and the
    /// bot API lower-case them, Swift's `uuidString` is upper-case).
    private static func lookupGroupName(groupId: String) -> String? {
        guard let container = sharedContainerURL() else { return nil }
        let url = container.appendingPathComponent("groups_snapshot.json")
        guard let data = try? Data(contentsOf: url),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        let wanted = groupId.trimmingCharacters(in: .whitespacesAndNewlines)
        for g in array {
            guard let id = g["groupId"] as? String,
                  id.caseInsensitiveCompare(wanted) == .orderedSame,
                  let name = g["name"] as? String else { continue }
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        return nil
    }

    /// __GROUP_MUTE_BLOCK_IN_NSE_2026_09_23__ Muted (`groups_snapshot.json`,
    /// `"muted": "1"`) or blocked (`blocked_groups_snapshot.json`)? Decision in
    /// `MessageBannerPolicy.groupIsSilenced` (shared with the app + tests).
    private static func isSilencedGroup(_ groupId: String) -> Bool {
        guard let container = sharedContainerURL() else { return false }
        let groups = (try? Data(contentsOf: container.appendingPathComponent("groups_snapshot.json")))
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [[String: Any]] } ?? []
        let blocked = (try? Data(contentsOf: container.appendingPathComponent("blocked_groups_snapshot.json")))
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String] } ?? []
        return MessageBannerPolicy.groupIsSilenced(groupId: groupId, groupsSnapshot: groups, blockedGroupIds: blocked)
    }

    /// base64url ↔ base64 standard equivalence (`-` ↔ `+`, `_` ↔ `/`, `=` padding).
    private static func base64urlEquivalent(_ a: String, _ b: String) -> Bool {
        func norm(_ s: String) -> String {
            return s.replacingOccurrences(of: "-", with: "+")
                   .replacingOccurrences(of: "_", with: "/")
                   .trimmingCharacters(in: CharacterSet(charactersIn: "="))
        }
        return norm(a) == norm(b)
    }

    /// Read the local user's public key from the shared identity snapshot
    /// the main app writes on every connect. Used to construct the
    /// `/api/pending/<recipient>` call so the server's fetchedBy tracking
    /// is updated. Returns nil if the snapshot is missing.
    /// Is this sender on the blocked list the main app publishes to the App Group?
    ///
    /// Compared on the same canonical form `BlockedContactsManager.normalizeKey`
    /// writes (base64url → base64, padding dropped), so a key spelled differently
    /// on the wire than in the block list still matches.
    ///
    /// Fails OPEN on a missing or unreadable snapshot: a fresh install has no
    /// file, and silently dropping every notification would be a far worse bug
    /// than the one this fixes.
    private static func isBlockedSender(_ senderPublicKey: String) -> Bool {
        MessageBannerPolicy.senderIsBlocked(senderPublicKey, blockedKeys: blockedKeysSnapshot())
    }

    /// __PHANTOM_BANNER_2026_10_11__ Ce messageId est-il garé indéchiffrable ?
    /// Voir `AppGroupBridge.writeUndecryptableSnapshot`. Comparaison insensible à
    /// la casse (les UUID Swift sont en majuscules, Android/bot en minuscules).
    /// Échoue OUVERT: sans instantané lisible, la poussée s'affiche.
    private static func isParkedUndecryptable(_ messageId: String) -> Bool {
        guard let container = sharedContainerURL() else { return false }
        let url = container.appendingPathComponent("undecryptable_snapshot.json")
        guard let data = try? Data(contentsOf: url),
              let ids = try? JSONSerialization.jsonObject(with: data) as? [String] else { return false }
        return ids.contains { $0.caseInsensitiveCompare(messageId) == .orderedSame }
    }

    /// __ANATOLE_1311_IOS__ (N8) Muted 1:1 contacts (`muted_contacts_snapshot.json`,
    /// written by `ConversationPrefsStore`). Fails OPEN like the blocked list.
    private static func isMutedSender(_ senderPublicKey: String) -> Bool {
        guard let container = sharedContainerURL() else { return false }
        let url = container.appendingPathComponent("muted_contacts_snapshot.json")
        guard let data = try? Data(contentsOf: url),
              let keys = try? JSONSerialization.jsonObject(with: data) as? [String] else { return false }
        return MessageBannerPolicy.senderIsBlocked(senderPublicKey, blockedKeys: keys)
    }

    private static func blockedKeysSnapshot() -> [String] {
        guard let container = sharedContainerURL() else { return [] }
        let url = container.appendingPathComponent("blocked_snapshot.json")
        guard let data = try? Data(contentsOf: url),
              let keys = try? JSONSerialization.jsonObject(with: data) as? [String] else { return [] }
        return keys
    }

    /// __BLOCKED_NOTIF_2026_09_23__ YES only once Apple has granted
    /// `com.apple.developer.usernotifications.filtering` AND it is in this target's
    /// entitlements. Never set it without the entitlement: the empty content would
    /// then fall back to the original alert again.
    private static let filteringEntitled =
        (Bundle.main.object(forInfoDictionaryKey: "OSHINotificationFilteringEntitled") as? Bool) == true

    /// __BLOCKED_NOTIF_2026_09_23__ The ONE way this extension hides a push.
    ///
    /// Apple: handing an EMPTY `UNNotificationContent` to the handler prevents
    /// display only for an extension holding the managed entitlement
    /// `com.apple.developer.usernotifications.filtering`. This one does not, so every
    /// "suppress" here used to fall back to the ORIGINAL alert — reported as "I
    /// blocked him and still get his notifications". Without the entitlement the
    /// least visible thing iOS allows is a PASSIVE notification: no banner, no
    /// sound, no badge change, no screen wake — one line in Notification Centre,
    /// marked `oshiSuppressed`, withdrawn a moment later here and swept again by
    /// the app (`BlockedContactsManager.withdrawDeliveredNotifications`).
    private func suppressDisplay(_ handler: @escaping (UNNotificationContent) -> Void) {
        if Self.filteringEntitled {
            handler(UNNotificationContent())
            return
        }
        let quiet = UNMutableNotificationContent()
        quiet.title = ""
        quiet.body = "OSHI"   // non-empty, or iOS treats it as "no alert" and shows the original
        quiet.sound = nil
        quiet.badge = nil
        quiet.threadIdentifier = "oshi.suppressed"
        quiet.relevanceScore = 0
        if #available(iOS 15.0, *) { quiet.interruptionLevel = .passive }
        quiet.userInfo = [MessageBannerPolicy.suppressedMarkerKey: "1"]
        handler(quiet)
        let id = currentRequestId
        DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) {
            if let id { UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id]) }
            Self.withdrawSuppressedAndBlocked()
        }
    }

    /// Withdraw every delivered notification that is marked suppressed or comes
    /// from a blocked sender (catches the ones an earlier run could not remove).
    private static func withdrawSuppressedAndBlocked() {
        let blocked = blockedKeysSnapshot()
        UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
            let ids = MessageBannerPolicy.deliveredToWithdraw(
                delivered.map { MessageBannerPolicy.Delivered(identifier: $0.request.identifier,
                                                              userInfo: $0.request.content.userInfo,
                                                              date: $0.date) },
                blockedKeys: blocked)
            if !ids.isEmpty {
                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
            }
        }
    }

    private static func recipientPublicKeyFromSharedIdentity() -> String? {
        guard let container = sharedContainerURL() else { return nil }
        let url = container.appendingPathComponent("identity_snapshot.json")
        guard let data = try? Data(contentsOf: url),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let pk = dict["publicKey"] as? String else { return nil }
        return pk
    }

    /// GET /api/pending/<recipient> to update server's fetchedBy. Side
    /// effect only — we don't actually use the response here; the main
    /// app will poll its own pending list when it opens.
    private static func markFetched(recipientPubKey: String) async {
        let encoded = base64urlEncode(recipientPubKey)
        let deviceId = sharedDeviceId() ?? "nse"
        guard let url = URL(string: "\(vpsBaseURL)/api/pending/\(encoded)?deviceId=\(deviceId)") else { return }
        var req = URLRequest(url: url, timeoutInterval: 10)
        req.httpMethod = "GET"
        // __BOT_E2E_2026_09_23__ same caps as the app's poll (the app opens bot-seal-v1).
        req.setValue(BotSealedEnvelope.capsHeaderValue, forHTTPHeaderField: BotSealedEnvelope.capsHeaderName)
        do {
            _ = try await URLSession.shared.data(for: req)
            os_log("✅ NSE marked fetched on server", log: .default, type: .info)
        } catch {
            os_log("⚠️ NSE markFetched err: %{public}@", log: .default, type: .info, error.localizedDescription)
        }
    }

    /// Fetch the encrypted IPFS blob and cache it to the App Group so
    /// the main app reads it instantly on next open. We do NOT decrypt;
    /// that requires shared key access (Phase 2).
    private static func prefetchAndCache(ipfsHash: String) async {
        guard let container = sharedContainerURL() else { return }
        let cacheDir = container.appendingPathComponent("nse_prefetch", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        let target = cacheDir.appendingPathComponent("\(ipfsHash).dat")
        if FileManager.default.fileExists(atPath: target.path) { return }  // already cached

        // VPS proxy only. The public-gateway fallbacks (cloudflare-ipfs.com, ipfs.io) were
        // removed 2026-07-17: our blobs live only on the VPS and are never published to the
        // IPFS DHT, so they could never resolve our CIDs — they only burned the NSE's tight
        // 30 s budget and leaked the CID + user IP.
        let urls = [
            "\(ipfsProxyBaseURL)/\(ipfsHash)"
        ].compactMap { URL(string: $0) }

        for url in urls {
            var req = URLRequest(url: url, timeoutInterval: 12)
            req.httpMethod = "GET"
            do {
                let (data, response) = try await URLSession.shared.data(for: req)
                guard let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode),
                      !data.isEmpty else { continue }
                // Chiffré (blob IPFS), mais classe explicite: l'extension écrit téléphone verrouillé.
                try data.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                os_log("✅ NSE prefetched %d bytes for %{public}@",
                       log: .default, type: .info, data.count, ipfsHash.prefix(12) as CVarArg)
                return
            } catch {
                // Try next gateway
                continue
            }
        }
        os_log("⚠️ NSE all gateways failed for %{public}@",
               log: .default, type: .info, ipfsHash.prefix(12) as CVarArg)
    }

    private static func sharedDeviceId() -> String? {
        guard let container = sharedContainerURL() else { return nil }
        let url = container.appendingPathComponent("device_id.txt")
        return try? String(contentsOf: url, encoding: .utf8)
    }

    private static func base64urlEncode(_ s: String) -> String {
        return s.replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
    }
}
