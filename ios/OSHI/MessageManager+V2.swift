//
//  MessageManager+V2.swift
//  OSHI — the integration seam between MessageManager and the v2 E2E stack
//  (own server: /v2/messages relay + /v2/blobs store), replacing Pinata/IPFS
//  as the primary online transport while keeping the legacy path as the
//  fallback for previous app versions.
//
//  HOW IT IS WIRED (both hooks live in MessageManager.swift):
//    * SEND    — `fallbackToIPFS` first calls `v2TrySend`. If the peer has a
//                v2 bundle the message goes through the ratchet + relay (files
//                through the blob store) and the legacy Pinata upload is
//                skipped. Peers on old versions have no bundle → 404 → the
//                legacy path runs exactly as before.
//    * RECEIVE — `checkPendingIPFSMessages` (1 s poll timer + APNs wakes)
//                calls `v2PullTick`: non-destructive pull → ratchet-decrypt →
//                persist through the existing `receiveMessage` funnel (so
//                receipts/reactions/edits/calls/profiles all work unchanged)
//                → `ack(upToSeq:)` → advance the persisted cursor.
//
//  RELIABILITY (Telegram-grade semantics):
//    * relay msgId is the stable message id end-to-end (dedup key).
//    * pull is non-destructive; the queue drops messages only after our ack,
//      so a crash between pull and persist re-delivers instead of losing.
//    * a persisted seen-msgId set + `receiveMessage`'s own duplicate-id check
//      make re-delivery idempotent.
//    * inbound content messages are acked back to the SENDER with the
//      existing "📬DELIVERY_RECEIPT📬<id>" payload (over v2 when possible),
//      flipping the sender's bubble to "delivered" — same UX as mesh/Android.
//
//  KILL SWITCH: set UserDefaults "oshi_v2_enabled" = false to force the
//  legacy Pinata/IPFS path for everything (flag defaults ON).
//

import Foundation
import CryptoKit
import UserNotifications

// MARK: - Feature flag

/// The master switch for the v2 E2E path. Three gates, all must pass:
///   1. LOCAL override (`oshi_v2_enabled`): an explicit `false` is a dev/local
///      kill; absent or `true` defers to the server.
///   2. REMOTE config (`GET /v2/config`, cached): the PRODUCTION lever. Ops can
///      set `v2_enabled=false` to kill v2 for everyone, or `rollout_percent<100`
///      to stage it — WITHOUT an app update. Cached in UserDefaults; the cache
///      bootstraps to on/100% so a fresh install works before the first fetch.
///   3. STAGED rollout: the device's stable bucket (hash of its userKey, 0–99)
///      must fall under `rollout_percent`. Only computed for partial rollouts.
enum V2FeatureFlag {
    static let defaultsKey = "oshi_v2_enabled"
    static let remoteKey = "oshi_v2_remote_config"

    struct Remote: Codable {
        var v2_enabled: Bool
        var rollout_percent: Int
        var min_build: Int

        /// __FORCED_UPDATE_2026_08_29__ En dessous de ce build, l'app BLOQUE et
        /// renvoie vers l'App Store.
        ///
        /// POURQUOI UN CHAMP SEPARE, ET SURTOUT PAS `min_build`
        /// `min_build` existe deja et veut dire tout autre chose: « en dessous
        /// de ce build, n'utilise pas v2 » — il rend `enabled == false`. Le
        /// regler pour forcer une mise a jour COUPERAIT la messagerie v2 des
        /// anciens clients au lieu de leur demander de se mettre a jour: ils
        /// resteraient silencieux, sans rien comprendre. Deux leviers, deux
        /// champs.
        ///
        /// OPTIONNEL, et c'est structurel: `storeRemote` valide le decodage
        /// AVANT de cacher. Un champ obligatoire ferait echouer le decodage de
        /// la reponse actuelle du serveur — qui ne le porte pas — et la config
        /// cesserait silencieusement d'etre mise en cache. Absent == aucun
        /// blocage.
        var min_supported_build: Int?

        /// __PER_DEVICE_MAILBOX_2026_09_23__ `device_mailbox_enabled` — per-device mailboxes
        /// (docs/OSHI_DEVICE_SYNC_DIRECT.md §15). Optional for the same reason as above;
        /// absent == false == today's behaviour, byte for byte.
        var device_mailbox_enabled: Bool? = nil

        /// __LEGACY_COMPAT_2026_09_23__ `group_legacy_compat` — while true (and before the
        /// compiled-in sunset, `GroupLegacyCompat.sunset`), group traffic ALSO goes over the
        /// legacy path to members not proven v2-group-capable. Optional: absent == false ==
        /// v2 only (secure default).
        var group_legacy_compat: Bool? = nil
    }

    /// The cached server config, or the safe bootstrap default (on, 100%).
    static func cachedRemote() -> Remote {
        if let data = UserDefaults.standard.data(forKey: remoteKey),
           let r = try? JSONDecoder().decode(Remote.self, from: data) {
            return r
        }
        return Remote(v2_enabled: true, rollout_percent: 100, min_build: 0, min_supported_build: nil)
    }

    /// Persist a freshly fetched `/v2/config` payload (raw JSON bytes).
    static func storeRemote(_ data: Data) {
        // __SIGNED_CONFIG_2026_09_14__ Une configuration qui RÉTROGRADE doit
        // être prouvée.
        //
        // Cette réponse décide si l'appareil parle v2 ou retombe sur l'ancien
        // chemin: influencer `/v2/config` EST la rétrogradation (M1). On passe
        // donc par `SignedRemoteConfig`, qui refuse une enveloppe signée
        // invalide ou rejouée. Une réponse HÉRITÉE (sans enveloppe) reste
        // acceptée tant que le serveur ne signe pas — sinon on ignorerait la
        // configuration vivante et chaque appareil retomberait sur son défaut,
        // c'est-à-dire précisément ce qu'on veut empêcher.
        //
        // REFUSER VEUT DIRE « NE CHANGE RIEN », pas « repars de zéro »: on garde
        // la dernière configuration acceptée.
        let lastVersion = UInt64(UserDefaults.standard.integer(forKey: SignedRemoteConfig.lastVersionKey))
        let accepted: (payload: Data, version: UInt64)
        do {
            accepted = try SignedRemoteConfig.accept(responseBody: data,
                                                     lastAcceptedVersion: lastVersion)
        } catch {
            OshiLog.storage.info("⛔️ Configuration distante refusée: \(String(describing: error)) — on garde la précédente")
            return
        }
        // Validate it decodes before caching, so a garbage body can't poison it.
        guard (try? JSONDecoder().decode(Remote.self, from: accepted.payload)) != nil else { return }
        UserDefaults.standard.set(accepted.payload, forKey: remoteKey)
        if accepted.version > lastVersion {
            UserDefaults.standard.set(Int(accepted.version), forKey: SignedRemoteConfig.lastVersionKey)
        }
    }

    static var enabled: Bool {
        // 1. Local override — only an explicit `false` disables.
        if UserDefaults.standard.object(forKey: defaultsKey) != nil,
           UserDefaults.standard.bool(forKey: defaultsKey) == false {
            return false
        }
        // 2. Remote kill / min-build gate.
        let r = cachedRemote()
        if !r.v2_enabled { return false }
        if r.min_build > 0, currentBuild() < r.min_build { return false }
        // 3. Staged rollout (skip the Keychain read entirely at 100%).
        if r.rollout_percent >= 100 { return true }
        if r.rollout_percent <= 0 { return false }
        return rolloutBucket() < r.rollout_percent
    }

    /// __FORCED_UPDATE_2026_08_29__ Cette build est-elle trop ancienne pour etre
    /// utilisee ?
    ///
    /// CE QUE CE LEVIER PEUT ET NE PEUT PAS FAIRE, il faut etre clair:
    /// iOS n'offre AUCUN moyen de forcer une app deja installee a se mettre a
    /// jour. Ce qu'on peut faire, c'est refuser de fonctionner et renvoyer vers
    /// l'App Store — mais seulement si la build INSTALLEE contient deja ce test.
    /// Une build livree avant ce commit l'ignorera toujours. Ce levier vaut donc
    /// pour l'avenir, pas retroactivement.
    ///
    /// ECHOUE OUVERT: sans config recuperee, `cachedRemote()` rend le defaut de
    /// demarrage (champ absent) et rien ne bloque. Une panne reseau ne doit
    /// jamais murer une app qui marche.
    ///
    /// GARDE-FOU CONTRE L'ERREUR D'EXPLOITATION: une valeur absurde (au-dela de
    /// la build courante + 1000) est IGNOREE. Un zero de trop dans le fichier de
    /// config murerait sinon toute la flotte d'un coup, y compris les appareils
    /// deja a jour, et il faudrait un deploiement pour la liberer.
    static var updateRequired: Bool {
        shouldRequireUpdate(currentBuild: currentBuild(),
                            minimumSupported: cachedRemote().min_supported_build)
    }

    /// La DECISION, isolee et pure — la seule chose ici qui puisse murer une
    /// flotte entiere doit pouvoir se verifier sans reseau, sans horloge et
    /// sans Bundle.
    ///
    /// - Parameter minimumSupported: `nil` quand le serveur ne dit rien.
    static func shouldRequireUpdate(currentBuild build: Int, minimumSupported: Int?) -> Bool {
        // Echoue OUVERT: rien de dit, rien de bloque.
        guard let minimum = minimumSupported, minimum > 0 else { return false }
        // Garde-fou contre l'erreur d'exploitation: un zero de trop dans le
        // fichier de config murerait TOUTE la flotte, y compris les appareils
        // deja a jour, et il faudrait une livraison App Store pour la liberer.
        //
        // 200, et pas 1000: la marge doit etre plus PETITE que la coquille
        // qu'elle attrape. `min_supported_build` se regle sur la build qu'on
        // vient de livrer, donc l'ecart avec un appareil en retard se compte en
        // dizaines. Avec une marge de 1000, une build 111 devenue 1110 par un
        // zero de trop passait la garde (1110 <= 1111) et murait tout le monde —
        // exactement le cas contre lequel elle est ecrite. Trouve par un test.
        guard minimum <= build + 200 else { return false }
        return build < minimum
    }

    private static func currentBuild() -> Int {
        Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0
    }

    /// Stable 0–99 bucket for this identity, cached per process. Same userKey →
    /// same bucket every launch, so a device doesn't flap in/out of the cohort.
    private static var _bucket: Int?
    private static func rolloutBucket() -> Int {
        if let b = _bucket { return b }
        let key = V2KeychainCrypto().userKey
        guard !key.isEmpty else { return 100 }   // no identity yet → out of cohort
        let digest = SHA256.hash(data: Data(key.utf8))
        let n = digest.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        let b = ((n % 100) + 100) % 100
        _bucket = b
        return b
    }
}

// MARK: - Lazy, once-built transport

/// Builds the v2 router + client exactly once, on first use. Registration
/// (bundle publish) is retried until it succeeds once per process.
private actor V2TransportHolder {
    private var builtRouter: V2MessageRouter?
    private var builtClient: V2Client?
    private var registered = false
    private var lastBootstrapCheckAt: Date?   // __MAILBOX_BOOTSTRAP_2026_09_23__
    /// __CAPS_NEVER_REFRESHED_2026_10_19__ one capability check per process.
    private var capsCheckedThisProcess = false

    func router() async -> V2MessageRouter {
        if let builtRouter { return builtRouter }
        let router = V2MessageRouter()
        await router.attachGroup()
        builtRouter = router
        return router
    }

    func client() -> V2Client {
        if let builtClient { return builtClient }
        let client = V2Client()
        builtClient = client
        return client
    }

    /// Publish our X3DH bundle once per process so peers can start v2
    /// sessions with us (best-effort; retried on the next call after failure).
    func ensureRegistered() async {
        // __CAPS_NEVER_REFRESHED_2026_10_19__ Registration is gated on the
        // bundle being MISSING, so a client that gains a capability never
        // advertises it: the bundle published by an older build stays on the
        // server for the life of the account. Measured on the Mac — it served
        // `caps: 0` while the running build's `Caps.mine` is 3, so every peer
        // read it as unable to do group v2/v3 and group rosters added on
        // another device never reached it. Relaunching could not fix it,
        // because nothing re-publishes a bundle that already exists.
        //
        // A capability set is a fact about the RUNNING build, not about the
        // account, so it is re-asserted whenever it has drifted. One GET per
        // process, and a publish only when the answer actually differs.
        //
        // __NOT_DURING_LAUNCH_2026_10_19__ Awaiting it HERE had it cancelled
        // every time: `NSURLErrorDomain -999 "cancelled"` on
        // `/v2/keys/<self>`, because this runs inside the launch path whose
        // task tree is torn down and rebuilt. Detached and delayed, so the
        // check happens on a settled client; retried because one transient
        // cancellation must not cost the account its capabilities until the
        // next launch.
        scheduleCapabilityDriftCheck()
        guard !registered else { return }
        // __PER_DEVICE_MAILBOX_2026_09_23__ Exactly ONE device keeps publishing the ACCOUNT
        // bundle (spec §3.2): a registered device that does not hold it must stop, or each
        // publish resets the holder's pool and senders land on the wrong device.
        // A device not yet registered defers the decision: publishing now would make a
        // brand-new second device the live generation before it knows it is not the holder.
        if V2DeviceMailbox.serverFlagOn {
            let me = V2KeychainCrypto().userKey
            if !V2DeviceMailbox.isRegistered(for: me) {
                // __MAILBOX_BOOTSTRAP_2026_09_23__ …except a BRAND-NEW account: no account
                // bundle on the server means no TOFU binding userKey→signingKey, and every
                // /v2/devices route answers 401 unbound-grace until the first signed publish.
                // Deferring here was a chicken-and-egg (no publish → no binding → no register
                // → no publish). Nobody else holds that bundle, so this install takes it and
                // registers right after as the first device (ownership then reads `.mine`).
                guard await accountBundleMissing(me) else { return }
            } else if !V2DeviceMailbox.holdsAccountBundle { registered = true; return }
        }
        do {
            try await router().register()
            registered = true
            // A registration attempt that just failed on the missing binding may retry now.
            V2DeviceMailbox.lastRegistrationAttempt = .distantPast
            OshiLog.messaging.info("✅ [V2] X3DH bundle published")
        } catch {
            OshiLog.messaging.info("⚠️ [V2] bundle publish failed (will retry): \(error)")
        }
    }

    /// __MAILBOX_BOOTSTRAP_2026_09_23__ True only when the server positively says it has NO
    /// account bundle for `me` (`GET /v2/keys/<me>/count` → 404 unknown user). The count route
    /// pops nothing. Network errors / any other answer → false (defer, retry next tick).
    /// Re-publish when the server's advertised `caps` no longer match this
    /// build's. Once per process, failures ignored — this is an optimisation of
    /// what peers believe about us, never a blocker for sending.
    private func scheduleCapabilityDriftCheck() {
        guard !capsCheckedThisProcess else { return }
        capsCheckedThisProcess = true
        Task.detached(priority: .utility) { [weak self] in
            for attempt in 1...3 {
                try? await Task.sleep(nanoseconds: UInt64(attempt) * 15_000_000_000)
                if await self?.republishIfCapabilitiesDrifted() == true { return }
            }
        }
    }

    /// Returns true when the question was settled (matched, or republished) —
    /// false only when the attempt itself failed and is worth retrying.
    @discardableResult
    private func republishIfCapabilitiesDrifted() async -> Bool {
        let me = V2KeychainCrypto().userKey
        guard !me.isEmpty else { return true }
        do {
            let bundle = try await client().fetchBundle(userKey: me)
            let advertised = bundle.caps ?? 0
            guard advertised != RatchetHealSignal.Caps.mine else {
                UserDefaults.standard.set("caps ok (\(advertised))", forKey: "capsDrift.step")
                return true
            }
            OshiLog.messaging.info("🔑 [V2] caps drift: server says \(advertised), this build is \(RatchetHealSignal.Caps.mine) — republishing")
            try await router().register()
            registered = true
            UserDefaults.standard.set("republished caps -> \(RatchetHealSignal.Caps.mine)", forKey: "capsDrift.step")
            return true
        } catch {
            // `localizedDescription` on a plain Swift enum prints "error 0",
            // which hides the OSStatus that is the whole diagnosis.
            UserDefaults.standard.set("retrying after: \(String(describing: error))", forKey: "capsDrift.step")
            return false
        }
    }

    private func accountBundleMissing(_ me: String) async -> Bool {
        guard !me.isEmpty else { return false }
        // ensureRegistered runs on every pull tick: a second device waiting for its approval
        // must not turn that into one GET per tick. Fresh identities answer on the first check.
        let now = Date()
        if let last = lastBootstrapCheckAt, now.timeIntervalSince(last) < 30 { return false }
        lastBootstrapCheckAt = now
        do {
            _ = try await client().prekeyCount(userKey: me)
            return false
        } catch let V2.ClientError.http(status, _) where status == 404 {
            return true
        } catch {
            return false
        }
    }

    /// Throttled fetch of the remote client config (kill switch / rollout %).
    /// Runs REGARDLESS of whether v2 is currently enabled — otherwise a client
    /// disabled by the kill switch would stop polling and could never be turned
    /// back on. Only needs the HTTP client, not the router/registration.
    private var lastConfigAt: Date?
    func refreshRemoteConfig() async {
        let now = Date()
        if let last = lastConfigAt, now.timeIntervalSince(last) < 300 { return }
        lastConfigAt = now
        do {
            let data = try await client().fetchClientConfig()
            V2FeatureFlag.storeRemote(data)
        } catch {
            // Unreachable/slow server → keep the last cached config. No log spam.
        }
    }

    /// Republie NOTRE matériel de clés, sans passer par le garde `registered`.
    ///
    /// __OPK_MUST_BE_OURS_2026_08_16__ Un pair qui bootstrape avec une pré-clé
    /// à usage unique que cet appareil ne détient plus décrit un pool qui sert
    /// des clés d'une génération morte. `ensureRegistered` ne peut rien y
    /// faire: il court-circuite après le premier succès du processus. C'est la
    /// seule réaction utile à `unusablePreKey` — et surtout PAS un accusé de
    /// réparation, qui recommencerait le problème.
    func forceRepublishKeys() async {
        if V2DeviceMailbox.suppressesAccountBundle { return }
        do {
            try await router().register()
            registered = true
            lastRefillAt = nil
            try await router().refillOneTimePreKeysIfNeeded()
            OshiLog.messaging.info("🔑 [V2] matériel de clés republié après une pré-clé injoignable")
        } catch {
            OshiLog.messaging.info("⚠️ [V2] republication des clés échouée: \(error)")
        }
    }

    /// Throttled one-time-prekey pool top-up. Called opportunistically from the
    /// pull loop; the server count check is one cheap GET, gated to at most once
    /// every few minutes so it rides the poll without hammering the endpoint.
    private var lastRefillAt: Date?
    func maybeRefillPreKeys() async {
        guard registered else { return }
        if V2DeviceMailbox.suppressesAccountBundle { return }
        // Avoid Date() in a hot loop — a coarse gate is enough.
        let now = Date()
        if let last = lastRefillAt, now.timeIntervalSince(last) < 300 { return }
        lastRefillAt = now
        do {
            try await router().refillOneTimePreKeysIfNeeded()
        } catch {
            OshiLog.messaging.info("⚠️ [V2] OPK refill check failed: \(error)")
        }
    }
}

// MARK: - Per-device mailbox driver (__PER_DEVICE_MAILBOX_2026_09_23__)

extension V2TransportHolder {
    /// Called at the top of every pull cycle. Returns the device signer when this cycle must
    /// poll in DEVICE mode, nil for the legacy view (flag off, not registered, removed).
    /// Keeps the router's device context in step with that answer.
    func deviceTick() async -> V2Device.Signer? {
        let r = await router()
        guard V2DeviceMailbox.serverFlagOn else {
            // Flag off (or turned off while running): legacy polling, no device headers.
            // The local registration is KEPT for when the flag returns (spec §3.1).
            if await r.isDeviceModeActive { await r.setDeviceContext(nil) }
            return nil
        }
        let me = V2KeychainCrypto().userKey
        guard !me.isEmpty else { return nil }
        if V2DeviceMailbox.isRegistered(for: me), let keys = V2Device.loadKeys(),
           keys.deviceId == V2DeviceMailbox.registeredDeviceId {
            await activate(keys: keys, me: me, router: r)
            return keys.signer
        }
        await r.setDeviceContext(nil)
        await attemptRegistration(me: me, router: r)
        return nil
    }

    private func activate(keys: V2Device.Keys, me: String, router r: V2MessageRouter) async {
        let ctx = V2DeviceContext(me: V2Device.normalizeIdentity(me), deviceId: keys.deviceId, signer: keys.signer,
                                  holdsAccountBundle: V2DeviceMailbox.holdsAccountBundle)
        await r.setDeviceContext(ctx)
        let now = Date()
        if !V2DeviceMailbox.bundlePublishedThisProcess {
            do {
                try await r.publishDeviceBundle(signer: keys.signer)
                V2DeviceMailbox.bundlePublishedThisProcess = true
                V2DeviceMailbox.lastDeviceRefill = now
            } catch {
                OshiLog.messaging.info("⚠️ [V2-DEV] device bundle publish failed (will retry): \(error)")
            }
        } else if now.timeIntervalSince(V2DeviceMailbox.lastDeviceRefill) > 300 {
            V2DeviceMailbox.lastDeviceRefill = now
            try? await r.refillDevicePreKeysIfNeeded(signer: keys.signer)
        }
        if now.timeIntervalSince(V2DeviceMailbox.lastListCheck) > 600 {
            V2DeviceMailbox.lastListCheck = now
            if let list = try? await client().listDevices() {
                if !list.contains(where: { $0.deviceId == keys.deviceId }) {
                    // Listed nowhere although we believe we are registered: the next device
                    // request answers 410 and `markRemoved` runs there.
                    OshiLog.messaging.info("⚠️ [V2-DEV] this device is missing from GET /v2/devices")
                }
                V2DeviceMailbox.checkNewlyLinked(list.map(\.deviceId), me: keys.deviceId)
            }
        }
    }

    /// Spec §3.2 steps 1–3, throttled. Outcomes the user must act on are surfaced once.
    private func attemptRegistration(me: String, router r: V2MessageRouter) async {
        let now = Date()
        guard now.timeIntervalSince(V2DeviceMailbox.lastRegistrationAttempt) > V2DeviceMailbox.registrationRetry else { return }
        V2DeviceMailbox.lastRegistrationAttempt = now
        // __MAILBOX_BOOTSTRAP_2026_09_23__ Publish-then-register, in THIS order, on this actor:
        // a brand-new account is only authorisable on /v2/devices once its first signed
        // publish has bound its signing key (otherwise 401 unbound-grace, then 60 s idle).
        await ensureRegistered()
        do {
            let keys = try V2Device.loadOrCreateKeys()
            let list = try await client().listDevices()
            if list.contains(where: { $0.deviceId == keys.deviceId }) {
                await completeRegistration(keys: keys, me: me, firstDevice: list.count == 1, router: r)
                return
            }
            let approval = V2DeviceMailbox.pendingApproval(now: now)
            if !list.isEmpty && approval == nil {
                V2DeviceMailbox.setStatus(.needsApproval)
                return
            }
            let reg = try V2Device.registration(keys: keys, identity: me, approval: approval)
            try await client().registerDevice(reg)
            V2DeviceMailbox.clearPendingApproval()
            await completeRegistration(keys: keys, me: me, firstDevice: list.isEmpty, router: r)
        } catch let V2.ClientError.http(status, reason) {
            let code = reason ?? ""
            OshiLog.messaging.info("⚠️ [V2-DEV] registration refused: \(status) \(code)")
            CallFileLogger.shared.log("DIAG_V2_DEVICE_REGISTER | status=\(status) | \(code)")
            if code.contains("approval-required") { V2DeviceMailbox.setStatus(.needsApproval) }
            else if code.contains("bad-approval") { V2DeviceMailbox.clearPendingApproval(); V2DeviceMailbox.setStatus(.badApproval) }
            else if code.contains("device-conflict") { _ = try? V2Device.regenerateKeys() }
            else if code.contains("too-many-devices") { V2DeviceMailbox.setStatus(.tooManyDevices) }
            else if code.contains("device-mailbox-disabled") { V2DeviceMailbox.serverDisabledUntil = now.addingTimeInterval(300) }
        } catch {
            // Network: the next attempt retries.
        }
    }

    private func completeRegistration(keys: V2Device.Keys, me: String, firstDevice: Bool, router r: V2MessageRouter) async {
        // Which install holds the live ACCOUNT bundle (spec §3.2 "Account bundle ownership").
        // Before any per-device publish, so the answer is about the account pool only.
        let holds: Bool
        switch await r.accountBundleOwnership() {
        case .mine: holds = true
        case .other: holds = false
        case .none, .unknown: holds = firstDevice    // a brand-new account: the first device holds it
        }
        V2DeviceMailbox.markRegistered(deviceId: keys.deviceId, identity: me, holdsAccountBundle: holds)
        LinkedDeviceWatch.noteKnownMailbox(keys.deviceId)
        OshiLog.messaging.info("✅ [V2-DEV] device registered (holder=\(holds))")
        CallFileLogger.shared.log("DIAG_V2_DEVICE_REGISTERED | holder=\(holds)")
        await activate(keys: keys, me: me, router: r)
    }

    /// `410 device-unknown` or `409 device-mailbox-disabled` on a device-mode request.
    func deviceModeRejected(status: Int) async {
        await router().setDeviceContext(nil)
        if status == 410 {
            V2DeviceMailbox.markRemoved()
        } else {
            V2DeviceMailbox.serverDisabledUntil = Date().addingTimeInterval(300)
        }
    }
}

/// App-side state of the per-device mailbox (spec `per_device_mailbox/CLIENT_SPEC.md` §3).
/// Also the API the devsync pairing code calls (see `helloFields`, `approvalFields`,
/// `receiveApproval`).
enum V2DeviceMailbox {
    // Persisted (UserDefaults: identifiers and flags only; the keys live in the Keychain).
    static let registeredIdKey = "oshi.devmbx.registeredDeviceId"
    static let registeredIdentityKey = "oshi.devmbx.registeredIdentity"
    static let holderKey = "oshi.devmbx.holdsAccountBundle"
    static let pendingApprovalKey = "oshi.devmbx.pendingApproval"
    static let statusKey = "oshi.devmbx.status"
    static let removedAtKey = "oshi.devmbx.removedAt"
    static let deviceCursorKey = "oshi_v2_last_acked_seq_device"

    // Process-local throttles.
    static var bundlePublishedThisProcess = false
    static var lastDeviceRefill = Date.distantPast
    static var lastListCheck = Date.distantPast
    static var lastRegistrationAttempt = Date.distantPast
    static var registrationRetry: TimeInterval = 60
    static var serverDisabledUntil = Date.distantPast

    enum Status: String {
        case none, needsApproval, badApproval, tooManyDevices, removed
    }

    /// `/v2/config.device_mailbox_enabled`, minus a server that just said it is off.
    static var serverFlagOn: Bool {
        V2FeatureFlag.cachedRemote().device_mailbox_enabled == true && Date() >= serverDisabledUntil
    }

    static var registeredDeviceId: String? { UserDefaults.standard.string(forKey: registeredIdKey) }
    static var holdsAccountBundle: Bool { UserDefaults.standard.bool(forKey: holderKey) }

    static func isRegistered(for identity: String) -> Bool {
        guard registeredDeviceId != nil,
              let id = UserDefaults.standard.string(forKey: registeredIdentityKey) else { return false }
        return V2Device.normalizeIdentity(id) == V2Device.normalizeIdentity(identity)
    }

    /// Device mode active right now (what the pull loop and the account-publish gate read).
    static var isActive: Bool {
        let me = V2KeychainCrypto().userKey
        return serverFlagOn && !me.isEmpty && isRegistered(for: me)
    }

    /// True while the flag is on and this install is not the registered account-bundle
    /// holder: it must not publish (or refill) the account bundle.
    static var suppressesAccountBundle: Bool {
        guard serverFlagOn else { return false }
        return !(isActive && holdsAccountBundle)
    }

    static var status: Status {
        Status(rawValue: UserDefaults.standard.string(forKey: statusKey) ?? "") ?? .none
    }

    static func markRegistered(deviceId: String, identity: String, holdsAccountBundle: Bool) {
        let d = UserDefaults.standard
        if d.string(forKey: registeredIdKey) != deviceId { d.set(0, forKey: deviceCursorKey) }
        d.set(deviceId, forKey: registeredIdKey)
        d.set(V2Device.normalizeIdentity(identity), forKey: registeredIdentityKey)
        d.set(holdsAccountBundle, forKey: holderKey)
        d.removeObject(forKey: removedAtKey)
        setStatus(.none)
    }

    /// `410 device-unknown`: removed by another device, a reset, or 30 days unseen. Back to
    /// legacy polling and TELL the user, unprompted (spec §3.3).
    static func markRemoved() {
        let d = UserDefaults.standard
        guard d.string(forKey: registeredIdKey) != nil else { return }
        d.removeObject(forKey: registeredIdKey)
        d.removeObject(forKey: holderKey)
        d.set(0, forKey: deviceCursorKey)
        d.set(Date().timeIntervalSince1970, forKey: removedAtKey)
        bundlePublishedThisProcess = false
        setStatus(.removed)
        CallFileLogger.shared.log("DIAG_V2_DEVICE_REMOVED")
        postAlert(id: "device-removed", titleKey: "devices.mailbox.removed.title", bodyKey: "devices.mailbox.removed.body")
    }

    static func setStatus(_ s: Status) {
        let previous = status
        UserDefaults.standard.set(s.rawValue, forKey: statusKey)
        guard s != previous else { return }
        switch s {
        case .needsApproval, .badApproval:
            postAlert(id: "device-approval", titleKey: "devices.mailbox.approval.title", bodyKey: "devices.mailbox.approval.body")
        case .tooManyDevices:
            postAlert(id: "device-too-many", titleKey: "devices.mailbox.approval.title", bodyKey: "devices.mailbox.tooMany.body")
        case .none, .removed:
            break
        }
    }

    /// A registered device that nobody here approved appeared (spec §3.7).
    static func checkNewlyLinked(_ ids: [String], me: String) {
        let outcome = LinkedDeviceWatch.classify(fetched: ids, known: LinkedDeviceWatch.loadKnownMailbox())
        LinkedDeviceWatch.saveKnownMailbox(ids)
        if case .linked(let new) = outcome {
            let others = new.filter { $0 != me }
            if !others.isEmpty {
                DispatchQueue.main.async { NotificationManager.shared.sendLinkedDeviceAlert(count: others.count) }
            }
        }
    }

    private static func postAlert(id: String, titleKey: String, bodyKey: String) {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString(titleKey, comment: "")
        content.body = NSLocalizedString(bodyKey, comment: "")
        content.sound = .default
        content.categoryIdentifier = "LINKED_DEVICE"
        content.userInfo = ["deepLink": "linkedDevices"]
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "oshi-\(id)-\(Int(Date().timeIntervalSince1970))",
                                                                     content: content, trigger: nil))
    }

    // MARK: Approval (devsync pairing, spec §3.2 / design §4.3)
    //
    // FIELD NAMES (agreed in CLIENT_SPEC.md §3.2, "Carrying dsk and the approval"):
    //   • the NEW device adds `"dsk": <std base64 Ed25519 public key>` to its devsync HELLO
    //     (unknown HELLO fields are ignored by older peers);
    //   • the APPROVING device adds `"mailbox": {"by", "ts", "sig"}` to its
    //     `APPROVAL{approved:true}` record — `ts` a decimal-ms STRING, exactly the string
    //     that was signed;
    //   • the new device registers with it within 15 minutes.

    /// Plug this client into devsync's carrier (`DevSyncMailboxBridge`, V2Client+Sync.swift):
    /// devsync only CARRIES `HELLO.dsk` and `APPROVAL.mailbox`; producing and consuming them
    /// is done here. Idempotent.
    static func installDevSyncBridge() {
        guard DevSyncMailboxBridge.signApproval == nil else { return }
        DevSyncMailboxBridge.localDsk = { (try? V2Device.loadOrCreateKeys())?.dskB64 }
        DevSyncMailboxBridge.signApproval = { newDeviceId, newDsk in
            approvalObject(newDeviceId: newDeviceId, newDsk: newDsk)
        }
        DevSyncMailboxBridge.onApprovalReceived = { approver, mailbox in
            onApprovalReceived(peerDeviceId: approver, body: ["approved": true, "mailbox": mailbox])
        }
    }

    // MARK: Hook surface shared with Desktop's `DeviceMailbox.kt` (same names, same semantics)

    static let peerDskKey = "oshi.devmbx.peerDsk"

    /// What a devsync HELLO carries so the peer can approve us in one round: `{dsk}`.
    /// nil while the flag is off.
    static func helloExtras() -> [String: Any]? {
        guard serverFlagOn, let keys = try? V2Device.loadOrCreateKeys() else { return nil }
        return ["dsk": keys.dskB64]
    }

    /// The peer's device signing key, learnt from devsync (HELLO or APPROVAL `dsk`).
    static func notePeerDsk(peerDeviceId: String, dsk: String) {
        guard V2Device.isDeviceId(peerDeviceId), Data(base64Encoded: dsk)?.count == 32 else { return }
        var map = UserDefaults.standard.dictionary(forKey: peerDskKey) as? [String: String] ?? [:]
        guard map[peerDeviceId] != dsk else { return }
        map[peerDeviceId] = dsk
        UserDefaults.standard.set(map, forKey: peerDskKey)
    }

    static func knownPeerDsk(_ peerDeviceId: String) -> String? {
        (UserDefaults.standard.dictionary(forKey: peerDskKey) as? [String: String])?[peerDeviceId]
    }

    /// APPROVER side: `{by, ts, sig}` for a new device, or nil when THIS device is not a
    /// registered, active device (or the new id is ours).
    static func approvalFor(newDeviceId: String, newDsk: String) -> [String: Any]? {
        guard newDeviceId != registeredDeviceId else { return nil }
        return approvalObject(newDeviceId: newDeviceId, newDsk: newDsk)
    }

    /// For devsync `approvalExtras(peerId, peerDk)`: always our own `dsk`, plus
    /// `mailbox:{by,ts,sig}` when the peer's `dsk` is already known. The peer id must be the
    /// one its Noise static key yields. nil while the flag is off.
    static func approvalExtras(peerDeviceId: String, peerDk: Data) -> [String: Any]? {
        guard serverFlagOn, V2Device.deviceId(dk: peerDk) == peerDeviceId,
              let keys = try? V2Device.loadOrCreateKeys() else { return nil }
        var out: [String: Any] = ["dsk": keys.dskB64]
        if let dsk = knownPeerDsk(peerDeviceId), let m = approvalFor(newDeviceId: peerDeviceId, newDsk: dsk) {
            out["mailbox"] = m
        }
        return out
    }

    /// For devsync `onApprovalReceived(peerId, body)`: learn the peer's `dsk`, keep its
    /// `mailbox` approval — only when it is signed by the device that relayed it.
    static func onApprovalReceived(peerDeviceId: String, body: [String: Any]) {
        if let dsk = body["dsk"] as? String, !dsk.isEmpty { notePeerDsk(peerDeviceId: peerDeviceId, dsk: dsk) }
        guard let m = body["mailbox"] as? [String: Any] else { return }
        guard (m["by"] as? String) == peerDeviceId else {
            OshiLog.messaging.info("⚠️ [V2-DEV] ignored an approval relayed by another device")
            return
        }
        acceptApproval(m)
    }

    /// A `{by, ts, sig}` approval for THIS device (`ts` number or string). Registration runs
    /// on the next pull tick.
    @discardableResult
    static func acceptApproval(_ m: [String: Any]) -> Bool {
        receiveApproval(["approved": true, "mailbox": m])
    }

    /// `{by, ts, sig}` for a new device whose deviceId devsync derived from its Noise static
    /// key, or nil when THIS device is not a registered mailbox device.
    static func approvalObject(newDeviceId: String, newDsk: String) -> [String: Any]? {
        guard V2Device.isDeviceId(newDeviceId), Data(base64Encoded: newDsk)?.count == 32,
              let keys = V2Device.loadKeys(), keys.deviceId == registeredDeviceId else { return nil }
        let me = V2KeychainCrypto().userKey
        guard !me.isEmpty, isRegistered(for: me),
              let a = try? V2Device.approve(newDeviceId: newDeviceId, newDsk: newDsk, identity: me, approver: keys) else { return nil }
        LinkedDeviceWatch.noteKnownMailbox(newDeviceId)
        return ["by": a.by, "ts": a.ts, "sig": a.sig]
    }

    /// Extra fields for this device's devsync `HELLO`: `["dsk": …]`.
    static func helloFields() -> [String: Any] {
        guard let keys = try? V2Device.loadOrCreateKeys() else { return [:] }
        return ["dsk": keys.dskB64]
    }

    /// Extra fields for our `APPROVAL{approved:true}` to a new device (`["mailbox": {…}]`),
    /// or nil when THIS device is not registered (then it cannot vouch for anyone) or the
    /// peer's HELLO carried no `dsk`. `peerDk` is the peer's Noise static key; the deviceId
    /// is recomputed from it, never taken from the wire.
    static func approvalFields(peerDk: Data, peerDsk: String?) -> [String: Any]? {
        guard let peerDsk else { return nil }
        return approvalObject(newDeviceId: V2Device.deviceId(dk: peerDk), newDsk: peerDsk).map { ["mailbox": $0] }
    }

    /// The approving device's `APPROVAL` record, as received by the NEW device. Stores the
    /// `mailbox` approval and makes the next pull tick register with it.
    @discardableResult
    static func receiveApproval(_ record: [String: Any]) -> Bool {
        guard (record["approved"] as? Bool) == true,
              let m = record["mailbox"] as? [String: Any],
              let by = m["by"] as? String, V2Device.isDeviceId(by),
              let sig = m["sig"] as? String else { return false }
        let ts: String
        if let s = m["ts"] as? String { ts = s } else if let n = m["ts"] as? NSNumber { ts = n.stringValue } else { return false }
        let a = V2Device.Registration.Approval(by: by, ts: ts, sig: sig)
        if let data = try? JSONEncoder().encode(a) {
            UserDefaults.standard.set(data, forKey: pendingApprovalKey)
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: pendingApprovalKey + ".at")
        }
        lastRegistrationAttempt = .distantPast    // register on the very next tick
        return true
    }

    /// The stored approval if still inside its 15-minute window.
    static func pendingApproval(now: Date = Date()) -> V2Device.Registration.Approval? {
        // devsync also persists what it carried (in case this client was not listening yet).
        if UserDefaults.standard.data(forKey: pendingApprovalKey) == nil,
           let raw = UserDefaults.standard.data(forKey: DevSyncMailboxBridge.lastApprovalKey),
           let rec = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
           let mb = rec["mailbox"] as? [String: Any] {
            receiveApproval(["approved": true, "mailbox": mb])
            UserDefaults.standard.removeObject(forKey: DevSyncMailboxBridge.lastApprovalKey)
        }
        guard let data = UserDefaults.standard.data(forKey: pendingApprovalKey),
              let a = try? JSONDecoder().decode(V2Device.Registration.Approval.self, from: data) else { return nil }
        let signedAt = (Double(a.ts) ?? 0) / 1000
        guard now.timeIntervalSince1970 - signedAt < 15 * 60 else { clearPendingApproval(); return nil }
        return a
    }

    static func clearPendingApproval() {
        UserDefaults.standard.removeObject(forKey: pendingApprovalKey)
        UserDefaults.standard.removeObject(forKey: pendingApprovalKey + ".at")
    }

    // MARK: Removal

    /// "Remove device" from the linked-devices screen: any registered device may remove any
    /// other. Signed by THIS device.
    static func unregister(deviceId: String) async throws {
        guard let keys = V2Device.loadKeys() else { throw V2.ClientError.missingSigningIdentity }
        try await V2Client().unregisterDevice(deviceId, signer: keys.signer)
    }

    /// Sign-out / identity switch: the device removes itself. The request is signed NOW,
    /// before the caller wipes the identity keys, and sent afterwards.
    static func unregisterSelfOnSignOut() {
        let d = UserDefaults.standard
        defer {
            for k in [registeredIdKey, registeredIdentityKey, holderKey, statusKey, removedAtKey, deviceCursorKey] { d.removeObject(forKey: k) }
            clearPendingApproval()
            LinkedDeviceWatch.saveKnownMailbox([])
            d.removeObject(forKey: LinkedDeviceWatch.mailboxKey)
            bundlePublishedThisProcess = false
        }
        guard let id = registeredDeviceId, let keys = V2Device.loadKeys(), keys.deviceId == id else { return }
        let client = V2Client()
        guard let req = try? client.unregisterRequest(id, signer: keys.signer) else { return }
        Task.detached { try? await client.perform(req) }
    }
}

// MARK: - Persistent receive-loop state

/// Cursor + dedup bookkeeping for the pull loop. All access happens on the
/// MainActor (the extension methods below are MainActor-isolated under the
/// project's default actor isolation), so plain statics are race-free.
/// Internal rather than private so the retry rules can be exercised directly in
/// tests. They decide whether a message that failed to decrypt is kept for
/// another attempt or dropped, and getting either side wrong is invisible until
/// users report missing messages or a frozen mailbox.
enum V2SyncState {
    static let cursorKey = "oshi_v2_last_acked_seq"
    static let seenKey = "oshi_v2_seen_msgids"
    static let seenCap = 500

    static var pullInProgress = false

    /// A pull was requested while one was already running.
    ///
    /// Exists so a push-driven request is never dropped — see `v2PullTick`. A
    /// plain boolean is enough: coalescing N requests into one extra pass is the
    /// desired behaviour, not a loss.
    static var pullRequestedDuringFlight = false
    /// Cover-mode admission for non-forced pulls (see `v2PullTick`).
    static var coverPullGate = CoverPullGate()

    /// __PER_DEVICE_MAILBOX_2026_09_23__ Whether the CURRENT pull cycle reads the device
    /// view. Fixed at the start of a cycle, so a flag flip mid-cycle cannot mix cursors.
    static var deviceModeCycle = false

    /// One cursor per mode (spec §3.3): `seq` is global, so each view resumes where IT
    /// stopped, and switching back to legacy picks the legacy cursor up unchanged.
    static var cursor: Int {
        get { UserDefaults.standard.integer(forKey: deviceModeCycle ? V2DeviceMailbox.deviceCursorKey : cursorKey) }
        set {
            UserDefaults.standard.set(newValue, forKey: deviceModeCycle ? V2DeviceMailbox.deviceCursorKey : cursorKey)
            // Le curseur a bougé: la boîte se vide, l'horloge de blocage repart.
            cursorStalledSince = Date().timeIntervalSince1970
        }
    }

    /// __ACK_HOLD_SURVIVES_EVICTION_2026_10_20__ Depuis quand le curseur n'a pas
    /// avancé. **C'est ICI que vit la garantie de déblocage, et nulle part
    /// ailleurs.**
    ///
    /// Première tentative, et pourquoi elle était fausse: j'avais mis la borne de
    /// temps sur l'enveloppe (`firstFailures[msgId]`). Mais une enveloppe
    /// expulsée du cache revient avec un compteur neuf ET un horodatage neuf —
    /// donc un budget remboursé. Avec un arriéré plus grand que `seenCap`, c'est
    /// le cas NORMAL, pas le cas rare. Aucune borne par enveloppe ne peut tenir
    /// dans un état plafonné: la propriété qu'on veut n'appartient pas à
    /// l'enveloppe, elle appartient au CURSEUR.
    ///
    /// Un seul nombre, jamais expulsable: si le curseur n'a pas avancé depuis
    /// `maxAckHoldSeconds`, plus rien ne retient l'ack. Une boîte ne peut donc
    /// plus rester bloquée, quel que soit l'arriéré et quel que soit l'état des
    /// compteurs. C'est ce qui débloque une boîte DÉJÀ gelée (mesuré: 7453
    /// enveloppes, 13 jours).
    static let cursorStalledSinceKey = "oshi_v2_cursor_stalled_since"
    static var cursorStalledSince: Double {
        get { UserDefaults.standard.double(forKey: cursorStalledSinceKey) }
        set { UserDefaults.standard.set(newValue, forKey: cursorStalledSinceKey) }
    }

    /// Le curseur est-il bloqué depuis trop longtemps pour qu'on retienne encore
    /// quoi que ce soit ? Amorce l'horloge au premier appel: une install neuve ne
    /// doit pas se croire bloquée depuis 1970.
    static func cursorStalledTooLong(now: Double) -> Bool {
        let since = cursorStalledSince
        guard since > 0 else { cursorStalledSince = now; return false }
        return now - since > maxAckHoldSeconds
    }

    static var seen: [String] {
        get { UserDefaults.standard.stringArray(forKey: seenKey) ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: seenKey) }
    }

    static func markSeen(_ msgId: String) {
        var s = seen
        s.append(msgId)
        if s.count > seenCap { s.removeFirst(s.count - seenCap) }
        seen = s
    }

    // MARK: Undecryptable-envelope retries
    //
    // 🔧 FIX (2026-08-05) — messages that produced a notification and never
    // appeared in the chat.
    //
    // A push tells the recipient "you have a message" and carries no content;
    // the app then pulls the envelope and decrypts it. When the Double Ratchet
    // is momentarily out of step — an out-of-order arrival, a session reset
    // race, a second device consuming a step — decryption fails. That case used
    // to log one line and nothing else: the envelope was NOT held back, so the
    // ack advanced past it and the relay deleted a message the user had already
    // been notified about and would now never see.
    //
    // Holding the ack unconditionally is not the fix either. An envelope that is
    // permanently undecryptable would then freeze the cursor forever and block
    // every later message — trading an occasional lost message for a dead
    // conversation. So retries are BOUNDED: hold the batch while there is still
    // budget, and once it is spent let the ack pass so the queue drains.
    static let retryKey = "oshi_v2_undecryptable_retries"
    /// Première fois qu'une enveloppe a échoué, en secondes Unix.
    ///
    /// __ACK_HOLD_SURVIVES_EVICTION_2026_10_20__ Le compteur de tics ci-dessous
    /// ne suffit PAS à borner la retenue, pour deux raisons mesurées sur un vrai
    /// compte (7438 enveloppes en attente sur le relais, la plus vieille de 13
    /// jours, curseur gelé, soit 3,8 Mo re-téléchargés à chaque sondage):
    ///
    ///  1. Le dictionnaire est plafonné à `seenCap` (500) et l'expulsion
    ///     s'écrivait `Array(r).suffix(seenCap)`. **Un Dictionary Swift n'a pas
    ///     d'ordre**: `suffix` garde donc 500 entrées ARBITRAIRES, pas les plus
    ///     récentes. Le compteur de l'enveloppe qui retient l'ack pouvait être
    ///     jeté à n'importe quel tic.
    ///  2. Avec un arriéré plus long que le plafond, ça n'était même plus une
    ///     malchance mais la règle: le compteur repartait de 1 à chaque tic, le
    ///     budget ne s'épuisait jamais, l'ack était retenu POUR TOUJOURS — et un
    ///     ack jamais envoyé garde l'arriéré au-dessus du plafond. La boucle
    ///     s'alimente elle-même, et elle bloque TOUTES les conversations, pas
    ///     seulement celle du pair cassé.
    ///
    /// Un horodatage ne se discute pas: une enveloppe qui échoue depuis
    /// `maxAckHoldSeconds` ne se déchiffrera pas maintenant, et la retenir prive
    /// l'utilisateur de tout ce qui est arrivé après elle.
    static let firstFailureKey = "oshi_v2_undecryptable_first_failure"
    /// Plafond DUR de la retenue, indépendant de tout cache. `maxRetries` tics
    /// d'un sondage par minute visent ~8 min; ce garde-fou laisse de la marge
    /// pour un sondage lent sans jamais laisser la boîte se figer.
    static let maxAckHoldSeconds: TimeInterval = 20 * 60
    /// Pull ticks to keep an undecryptable envelope for. The pull runs on the
    /// message poll, so this is several minutes of chances — long enough for a
    /// ratchet to resynchronise, short enough that one poisoned envelope cannot
    /// stall the mailbox for a whole session.
    static let maxRetries = 8

    private static var retries: [String: Int] {
        get { (UserDefaults.standard.dictionary(forKey: retryKey) as? [String: Int]) ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: retryKey) }
    }

    private static var firstFailures: [String: Double] {
        get { (UserDefaults.standard.dictionary(forKey: firstFailureKey) as? [String: Double]) ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: firstFailureKey) }
    }

    /// - Returns: true while this envelope still deserves another attempt.
    ///
    /// __V2_FAILURE_IS_TYPED_2026_08_28__ Le budget est celui de l'ACK, et
    /// rien d'autre. Retenir l'ack ne retarde AUCUNE livraison — les messages
    /// sains du même lot sont livrés dans la même boucle — et le relais garde
    /// 30 jours (`ServerVPS/v2/relay_v2.js`, `RETENTION_DAYS`). C'est donc une
    /// sûreté quasi gratuite qu'il n'y a aucune raison d'échanger contre de la
    /// vitesse. La RÉACTION (effacer + soigner), elle, ne passe plus par ce
    /// compteur: voir `breakEvidence`.
    ///
    /// Deux classes font exception et n'ont droit qu'à UNE tentative: des
    /// octets malformés et une identité usurpée ne changeront pas au re-pull,
    /// et les retenir ne fait que geler le curseur pour rien.
    /// - Parameter clock: injectable pour que `maxAckHoldSeconds` soit TESTABLE.
    ///   Une garantie dure dont le test demanderait d'attendre 20 minutes n'est
    ///   pas testée, et c'est précisément la garantie qui manquait.
    static func shouldRetryUndecryptable(_ msgId: String,
                                         kind: V2DecryptFailureKind = .transient,
                                         clock: Date = Date()) -> Bool {
        let now = clock.timeIntervalSince1970
        var r = retries
        var first = firstFailures
        let n = (r[msgId] ?? 0) + 1
        let budget = (kind == .malformedEnvelope || kind == .spoofedIdentity) ? 1 : maxRetries
        r[msgId] = n
        // L'horodatage ne sert QU'À ordonner l'expulsion — la garantie de
        // déblocage, elle, est portée par `cursorStalledSince`, qui n'est pas
        // expulsable. Voir sa documentation pour pourquoi aucune borne par
        // enveloppe ne peut tenir ici.
        first[msgId] = first[msgId] ?? now
        // Bound the dictionaries — c'est UserDefaults, pas une base, et un
        // install de longue durée ne doit pas accumuler une entrée par échec.
        //
        // __ACK_HOLD_SURVIVES_EVICTION_2026_10_20__ L'expulsion garde les
        // échecs les plus RÉCENTS, par horodatage, au lieu d'un `suffix` sur un
        // dictionnaire non ordonné. `msgId` vient d'être écrit avec `now`, donc
        // l'enveloppe du tic courant survit toujours à sa propre expulsion —
        // c'est l'invariant que l'ancien code cassait.
        if r.count > seenCap {
            let keep = Set(first.sorted { $0.value > $1.value }.prefix(seenCap).map(\.key))
            r = r.filter { keep.contains($0.key) }
            first = first.filter { keep.contains($0.key) }
        }
        retries = r
        firstFailures = first
        if cursorStalledTooLong(now: now) { return false }
        return n <= budget
    }

    /// Called once an envelope finally decodes, so a message that recovered does
    /// not carry its failure count into a future id collision.
    static func clearRetries(_ msgId: String) {
        var f = firstFailures
        if f.removeValue(forKey: msgId) != nil { firstFailures = f }
        var r = retries
        guard r.removeValue(forKey: msgId) != nil else { return }
        retries = r
    }

    // MARK: Accusés de réparation de ratchet (RatchetHealSignal)
    //
    // Quand émettre est décidé par `RatchetHealSignal.Policy`, qui est PURE.
    // Ici on ne garde que la date, parce que le délai doit survivre au
    // relancement: deux appareils qui cassent en même temps et redémarrent
    // aussitôt reprendraient la boucle exacte que le délai existe pour briser.
    static let healSentKey = "oshi_v2_heal_sent_at"

    private static var healSentAt: [String: Double] {
        get { (UserDefaults.standard.dictionary(forKey: healSentKey) as? [String: Double]) ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: healSentKey) }
    }

    static func lastHealSent(to peer: String) -> Date? {
        healSentAt[peer].map { Date(timeIntervalSince1970: $0) }
    }

    // __HEAL_V1_SAFETY_2026_08_28__ Pairs ayant PROUVE qu'ils savent lire une
    // liste de renvoi. Rien d'autre ne permet de le savoir: le protocole ne
    // porte aucune version client, et `capabilities()` ne repond que « v2
    // disponible ». Joindre une liste a un pair en 1.0.39 lui afficherait du
    // JSON brut en pleine conversation.
    static let peerResendCapableKey = "oshi_v2_peer_resend_capable"

    /// __RESEND_AMPLIFICATION_2026_08_28__ Dernier instant ou l'on a SERVI une
    /// demande de renvoi a ce pair. Persiste, comme le delai des accuses: deux
    /// appareils qui redemarrent aussitot reprendraient sinon la rafale exacte
    /// que ce delai existe pour couper.
    static let resendServedKey = "oshi_v2_resend_served_at"

    private static var resendServedAt: [String: Double] {
        get { (UserDefaults.standard.dictionary(forKey: resendServedKey) as? [String: Double]) ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: resendServedKey) }
    }

    static func lastResendServed(to peer: String) -> Date? {
        resendServedAt[peer].map { Date(timeIntervalSince1970: $0) }
    }

    static func markResendServed(to peer: String, at date: Date = Date()) {
        var m = resendServedAt
        m[peer] = date.timeIntervalSince1970
        if m.count > seenCap { m = Dictionary(uniqueKeysWithValues: Array(m).suffix(seenCap)) }
        resendServedAt = m
    }

    static func markPeerUnderstandsResendList(_ peer: String) {
        var l = Set(UserDefaults.standard.stringArray(forKey: peerResendCapableKey) ?? [])
        guard l.insert(peer).inserted else { return }
        // Borne comme ses voisins: un carnet de contacts, pas une base.
        var arr = Array(l)
        if arr.count > seenCap { arr = Array(arr.suffix(seenCap)) }
        UserDefaults.standard.set(arr, forKey: peerResendCapableKey)
    }

    /// __CLIENT_CAPS_2026_08_31__ DEUX sources, et la premiere est la seule qui
    /// fonctionne dans le cas reel.
    ///
    /// La preuve par accusé RECU ne suffisait pas: pour redemander mes messages
    /// perdus je dois savoir que TU es capable, mais je ne l'apprenais que le
    /// jour ou TA session cassait. Si c'est toujours mon cote qui casse, la
    /// preuve n'arrive jamais et mes identifiants expirent sans avoir servi.
    /// Le bundle, lui, est lu AVANT qu'on s'ecrive, dans les deux sens.
    ///
    /// L'ancienne source est conservee: elle reste vraie, elle coute un
    /// booleen, et elle couvre le cas ou le bundle du pair est anterieur a sa
    /// mise a jour (publie sans `caps`, donc lu comme 0) alors que son accuse,
    /// lui, prouve qu'il sait lire.
    static func peerUnderstandsResendList(_ peer: String) -> Bool {
        if RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.of(peer: peer),
                                      RatchetHealSignal.Caps.resendList) { return true }
        return (UserDefaults.standard.stringArray(forKey: peerResendCapableKey) ?? []).contains(peer)
    }

    static func markHealSent(to peer: String, at date: Date = Date()) {
        var m = healSentAt
        m[peer] = date.timeIntervalSince1970
        // Borné comme les voisins: un carnet de contacts n'est pas une base.
        if m.count > seenCap { m = Dictionary(uniqueKeysWithValues: Array(m).suffix(seenCap)) }
        healSentAt = m
    }

    // MARK: - Preuve de casse, par PAIR
    //
    // __V2_FAILURE_IS_TYPED_2026_08_28__ `retries` est un COMPTEUR DE TICS par
    // enveloppe: il ne dit pas si la SESSION est morte, et il n'est même pas
    // indexé par pair. Il ne peut donc pas porter la décision « effacer et
    // soigner ». C'est ce registre-ci qui la porte.
    //
    // LES CHIFFRES, ET D'OÙ ILS VIENNENT (aucun n'est une intuition):
    //  • `noSession` — le magasin n'a AUCUN enregistrement. `deleteSession`
    //    est un no-op, l'accusé ne peut donc détruire aucun message en vol, et
    //    attendre n'ajoutera jamais ni session ni en-tête. Conclusif à 1.
    //  • `gapTooLarge` — le relais renvoie la queue ENTIÈRE après le curseur,
    //    sans plafond: les 1000+ messages manquants ne sont pas en route.
    //    Rien à attendre. Conclusif à 1.
    //  • `unusablePreKey` — le pool a servi une pré-clé d'une génération morte
    //    (`__OPK_MUST_BE_OURS_2026_08_16__`). Conclusif à 1, mais l'action
    //    n'est PAS un accusé: c'est republier notre matériel de clés.
    //  • `ratchetBreak` — la SEULE classe ambiguë. Une enveloppe isolée peut
    //    être corrompue en propre; une SECONDE, chiffrée indépendamment sous
    //    la même session, élimine cette hypothèse. D'où 2 msgId DISTINCTS.
    //    Le variant « 1 msgId sur 3 tics » couvre la dernière course réelle
    //    (deux processus sur le même enregistrement, déjà refermée par
    //    `withPeerSession`) avec une marge. 3 tics ≈ 3 s en avant-plan: le
    //    timer de relève est à 1 s. Au passage, le commentaire de `maxRetries`
    //    qui promet « several minutes of chances » est FAUX en avant-plan.
    //  • `malformedEnvelope` / `spoofedIdentity` — JAMAIS. Des octets abîmés
    //    ou une identité qui ne colle pas ne sont pas une preuve que la
    //    session est morte, et effacer sur cette base est précisément
    //    l'attaque qu'on ne veut pas offrir.
    static let breakEvidenceKey = "oshi_v2_break_evidence"

    struct BreakEvidence: Codable, Equatable {
        /// Premier instant où l'on a voulu réparer ce pair. Sert au départage
        /// symétrique: le côté NON initiateur attend un délai complet à partir
        /// de là avant d'émettre à son tour.
        var since: Double
        /// msgId → nombre de tics CONSÉCUTIFS où il a échoué.
        var ids: [String: Int]
    }

    private static var breakEvidenceStore: [String: BreakEvidence] {
        get {
            guard let data = UserDefaults.standard.data(forKey: breakEvidenceKey),
                  let m = try? JSONDecoder().decode([String: BreakEvidence].self, from: data)
            else { return [:] }
            return m
        }
        set {
            var m = newValue
            // Borné comme `retries` et `healSentAt`: un carnet par pair ne doit
            // pas grossir à vie dans UserDefaults.
            if m.count > seenCap { m = Dictionary(uniqueKeysWithValues: Array(m).suffix(seenCap)) }
            guard let data = try? JSONEncoder().encode(m) else { return }
            UserDefaults.standard.set(data, forKey: breakEvidenceKey)
        }
    }

    /// Depuis quand on cherche à réparer ce pair (`nil` si on ne cherche pas).
    static func breakEvidenceSince(peer: String) -> Date? {
        breakEvidenceStore[peer].map { Date(timeIntervalSince1970: $0.since) }
    }

    /// Date le PREMIER moment où l'on a voulu réparer ce pair — y compris pour
    /// une classe qui n'apporte aucune preuve (`.transient`, arrivée là par
    /// épuisement du budget d'ack). C'est cette date, et pas celle d'un accusé
    /// jamais émis, que le côté NON initiateur laisse s'écouler avant de
    /// prendre la main à son tour.
    static func noteRepairWanted(peer: String, now: Date = Date()) {
        var m = breakEvidenceStore
        if m[peer] == nil { m[peer] = BreakEvidence(since: now.timeIntervalSince1970, ids: [:]) }
        breakEvidenceStore = m
    }

    /// - Returns: vrai quand la session avec `peer` est PROUVÉE morte.
    static func breakEvidence(peer: String,
                              kind: V2DecryptFailureKind,
                              msgId: String,
                              now: Date = Date()) -> Bool {
        switch kind {
        case .malformedEnvelope, .spoofedIdentity, .transient:
            return false

        case .noSession, .gapTooLarge, .unusablePreKey:
            var m = breakEvidenceStore
            if m[peer] == nil { m[peer] = BreakEvidence(since: now.timeIntervalSince1970, ids: [:]) }
            breakEvidenceStore = m
            return true

        case .ratchetBreak:
            var m = breakEvidenceStore
            var e = m[peer] ?? BreakEvidence(since: now.timeIntervalSince1970, ids: [:])
            e.ids[msgId] = (e.ids[msgId] ?? 0) + 1
            // 64 enveloppes distinctes suffisent très largement à prouver une
            // casse; au-delà on ne garderait qu'un journal.
            if e.ids.count > 64 {
                e.ids = Dictionary(uniqueKeysWithValues: Array(e.ids).suffix(64))
            }
            m[peer] = e
            breakEvidenceStore = m
            return e.ids.count >= 2 || (e.ids[msgId] ?? 0) >= 3
        }
    }

    /// Un déchiffrement RÉUSSI de ce pair est la preuve DIRECTE que la session
    /// est vivante: l'enveloppe fautive était un défaut isolé.
    static func clearBreakEvidence(peer: String) {
        var m = breakEvidenceStore
        guard m.removeValue(forKey: peer) != nil else { return }
        breakEvidenceStore = m
    }

    // MARK: - Registre des enveloppes ILLISIBLES, par pair
    //
    // __RATCHET_RESEND_2026_08_28__ Refaire X3DH répare le CANAL, pas les
    // messages déjà détruits. MESURÉ: 176 enveloppes abandonnées sur les deux
    // téléphones de l'utilisateur — 176 pushs suivis de rien.
    //
    // Cette liste n'existait nulle part. `retries` est un compteur, pas une
    // liste d'abandons, il n'est pas indexé par pair, et il est élagué à 500
    // entrées. L'instant de l'abandon est le SEUL endroit du code où `msgId`
    // ET `from` sont tous deux connus.
    //
    // ATTENTION: `V2IncomingMessage.failed` ne porte PAS de `groupId`, donc
    // une enveloppe de GROUPE abandonnée entre aussi ici. Côté expéditeur
    // l'id ne correspondra à aucun message 1:1 stocké — c'est un cas NORMAL,
    // pas une erreur (voir `v2ResendRequested`).
    static let unreadableKey = "oshi_v2_unreadable_by_peer"
    static let unreadablePerPeerCap = 64

    /// pair → (msgId → horodatage de l'ENVELOPPE, en epoch).
    private static var unreadableByPeer: [String: [String: Double]] {
        get { (UserDefaults.standard.dictionary(forKey: unreadableKey) as? [String: [String: Double]]) ?? [:] }
        set {
            var m = newValue
            if m.count > seenCap { m = Dictionary(uniqueKeysWithValues: Array(m).suffix(seenCap)) }
            UserDefaults.standard.set(m, forKey: unreadableKey)
        }
    }

    static func markUnreadable(msgId: String, from peer: String, envelopeTs: Date?) {
        var m = unreadableByPeer
        var forPeer = m[peer] ?? [:]
        forPeer[msgId] = (envelopeTs ?? Date()).timeIntervalSince1970
        if forPeer.count > unreadablePerPeerCap {
            // On garde les PLUS RÉCENTS: ce sont ceux dont le renvoi a encore
            // une valeur pour l'utilisateur.
            let keep = forPeer.sorted { $0.value > $1.value }.prefix(unreadablePerPeerCap)
            forPeer = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
        }
        m[peer] = forPeer
        unreadableByPeer = m
    }

    /// Les ids à redemander à `peer`: les plus RÉCENTS d'abord, plafonnés, et
    /// purgés de tout ce qui a dépassé l'âge de renvoi.
    ///
    /// NE PURGE PAS le registre: on n'oublie un id qu'après un envoi RÉUSSI
    /// (`clearUnreadable`), sinon un accusé qui échoue emporte la liste avec
    /// lui et l'utilisateur perd les messages ET le déclencheur.
    static func drainUnreadable(for peer: String, now: Date = Date()) -> [String] {
        guard let forPeer = unreadableByPeer[peer] else { return [] }
        let cutoff = now.timeIntervalSince1970 - RatchetHealSignal.Policy.maxResendAge
        return forPeer
            .filter { $0.value >= cutoff }
            .sorted { $0.value > $1.value }
            .prefix(RatchetHealSignal.Policy.maxResendPerRequest)
            .map { $0.key }
    }

    /// Retire un id du registre. Appelé au même endroit que `clearRetries`
    /// (un message finalement lu ne doit plus être redemandé) et après un
    /// accusé parti avec succès.
    static func clearUnreadable(_ msgId: String) {
        var m = unreadableByPeer
        var touched = false
        for (peer, var ids) in m where ids.removeValue(forKey: msgId) != nil {
            touched = true
            if ids.isEmpty { m.removeValue(forKey: peer) } else { m[peer] = ids }
        }
        guard touched else { return }
        unreadableByPeer = m
    }

    // MARK: - Freins du RENVOI (côté expéditeur)
    //
    // Le cycle à casser: renvoi → échec → abandon → `deleteSession` → accusé →
    // renvoi des MÊMES ids. Le délai de 60 s ne le termine pas, il l'espace.
    // Seul un compteur PAR msgId le termine, et il doit être PERSISTÉ: un
    // relancement le remettrait à zéro sinon, et la boucle repartirait.
    static let resentCountsKey = "oshi_v2_resent_counts"
    static let resendBudgetKey = "oshi_v2_resend_budget"

    private static var resentCounts: [String: Int] {
        get { (UserDefaults.standard.dictionary(forKey: resentCountsKey) as? [String: Int]) ?? [:] }
        set {
            var m = newValue
            if m.count > seenCap { m = Dictionary(uniqueKeysWithValues: Array(m).suffix(seenCap)) }
            UserDefaults.standard.set(m, forKey: resentCountsKey)
        }
    }

    static func resendAttempts(_ msgId: String) -> Int { resentCounts[msgId] ?? 0 }

    static func noteResent(_ msgId: String) {
        var m = resentCounts
        m[msgId] = (m[msgId] ?? 0) + 1
        resentCounts = m
    }

    /// pair → [début de fenêtre (epoch), nombre de renvois dans la fenêtre].
    private static var resendBudget: [String: [Double]] {
        get { (UserDefaults.standard.dictionary(forKey: resendBudgetKey) as? [String: [Double]]) ?? [:] }
        set {
            var m = newValue
            if m.count > seenCap { m = Dictionary(uniqueKeysWithValues: Array(m).suffix(seenCap)) }
            UserDefaults.standard.set(m, forKey: resendBudgetKey)
        }
    }

    /// Renvois encore permis vers ce pair sur l'heure glissante.
    static func peerResendBudgetLeft(_ peer: String, now: Date = Date()) -> Int {
        let max = RatchetHealSignal.Policy.maxResendsPerPeerPerHour
        guard let w = resendBudget[peer], w.count == 2 else { return max }
        if now.timeIntervalSince1970 - w[0] >= 3600 { return max }
        return Swift.max(0, max - Int(w[1]))
    }

    static func noteResendSpent(_ peer: String, now: Date = Date()) {
        var m = resendBudget
        let t = now.timeIntervalSince1970
        if let w = m[peer], w.count == 2, t - w[0] < 3600 {
            m[peer] = [w[0], w[1] + 1]
        } else {
            m[peer] = [t, 1]
        }
        resendBudget = m
    }

    // MARK: - Blobs média en attente
    //
    // __V2_BLOB_RETRY_IS_NOT_A_RATCHET_BREAK_2026_08_28__ PRÉREQUIS BLOQUANT
    // de la réaction immédiate.
    //
    // Quand le téléchargement d'un blob échouait, l'enveloppe était RETIRÉE de
    // `seen` pour être rejouée. Mais elle a DÉJÀ été déchiffrée avec succès et
    // `attempt` a persisté l'état avancé. Au tic suivant elle repasse par le
    // ratchet: `trySkipped` ne trouve rien (la clé courante a été consommée
    // par `kdfCK`, jamais rangée dans MKSKIPPED), `hdr.dh == DHr` donc pas de
    // `dhRatchet`, `skipMessageKeys` ne fait rien car `hdr.n < Nr`, et `kdfCK`
    // produit une mauvaise clé ⇒ échec AEAD ⇒ `.failed`, classé
    // `ratchetBreak`. C'est EXACTEMENT la signature qu'on veut utiliser comme
    // déclencheur de casse: un seul blob raté hors ligne effacerait une
    // session parfaitement saine.
    //
    // Le msgId reste donc dans `seen` (filtré par `alreadySeen` AVANT le
    // ratchet), et la reprise du téléchargement vit ici.
    static let blobPendingKey = "oshi_v2_blob_pending"
    /// Reprises avant d'abandonner le blob. Même ordre de grandeur que le
    /// budget d'ack: au-delà, la boîte ne doit plus être retenue.
    static let blobMaxAttempts = 8

    struct PendingBlob: Codable, Equatable {
        let msgId: String
        let from: String
        let groupId: String?
        let key: V2FileKeyMessage
        let ts: Double
        var attempts: Int
    }

    // __LEAK_FIX_2026_09_25__ Each entry carries a `V2FileKeyMessage` — the AES key of a
    // received attachment. It used to sit in UserDefaults (plist, in iCloud/Finder
    // backups). It now lives in the Keychain (AfterFirstUnlockThisDeviceOnly, never
    // synchronised, app-private group); the UserDefaults copy is migrated on first read
    // and then deleted.
    static var blobPending: [String: PendingBlob] {
        get { V2SecretBlobStore.load([String: PendingBlob].self, account: blobPendingAccount, legacyDefaultsKey: blobPendingKey) ?? [:] }
        set { V2SecretBlobStore.save(newValue, isEmpty: newValue.isEmpty, account: blobPendingAccount, legacyDefaultsKey: blobPendingKey) }
    }
    static let blobPendingAccount = "oshi.v2.blobPending.v1"

    static func rememberPendingBlob(_ p: PendingBlob) {
        var m = blobPending
        // Une reprise ne remet pas le compteur à zéro.
        var entry = p
        entry.attempts = (m[p.msgId]?.attempts ?? 0)
        m[p.msgId] = entry
        blobPending = m
    }

    /// - Returns: false quand le blob a épuisé ses reprises et vient d'être
    ///   abandonné (l'entrée est retirée, l'ack peut passer).
    @discardableResult
    static func notePendingBlobAttempt(_ msgId: String) -> Bool {
        var m = blobPending
        guard var entry = m[msgId] else { return false }
        entry.attempts += 1
        if entry.attempts >= blobMaxAttempts {
            m.removeValue(forKey: msgId)
            blobPending = m
            return false
        }
        m[msgId] = entry
        blobPending = m
        return true
    }

    static func clearPendingBlob(_ msgId: String) {
        var m = blobPending
        guard m.removeValue(forKey: msgId) != nil else { return }
        blobPending = m
    }
}

/// __V2_FAILURE_IS_TYPED_2026_08_28__ Ce qu'un tic a appris sur UN pair, une
/// fois toutes ses enveloppes cassées examinées.
///
/// La réparation ne peut pas se décider par enveloppe: le lot NORMAL d'une
/// session morte en contient plusieurs, et agir k fois est précisément le
/// défaut qui détruisait la session neuve créée par l'action précédente.
struct V2RepairPlan {
    var kind: V2DecryptFailureKind
    /// Plus petite seq échouée de ce pair — ce qu'il faut retenir si la
    /// réparation ne part pas.
    var lowestSeq: Int
}

/// Quand un même pair casse de plusieurs façons dans le même tic, on garde la
/// plus CONCLUSIVE — celle dont l'action est la plus spécifique.
func v2MergeRepairKind(_ a: V2DecryptFailureKind, _ b: V2DecryptFailureKind) -> V2DecryptFailureKind {
    func rank(_ k: V2DecryptFailureKind) -> Int {
        switch k {
        case .unusablePreKey:    return 5   // action propre: republier les clés
        case .noSession:         return 4
        case .gapTooLarge:       return 3
        case .ratchetBreak:      return 2
        case .transient:         return 1
        case .malformedEnvelope, .spoofedIdentity: return 0
        }
    }
    return rank(a) >= rank(b) ? a : b
}

// MARK: - Gated routing entry point

extension MessageManager {

    /// The sentinel the send flow branches on. `.useLegacyIPFS` means "do exactly
    /// what you do today"; `.handledByV2` means the message was already delivered
    /// via the v2 relay and the legacy path must be skipped.
    enum V2RouteDecision: Sendable {
        case useLegacyIPFS
        case handledByV2(msgIds: [String])
    }

    private static let v2Transport = V2TransportHolder()

    /// The flag-gated routing decision for one outgoing message.
    ///
    /// - While `V2FeatureFlag.enabled == false` this returns `.useLegacyIPFS`
    ///   with no side effects.
    /// - When enabled, it negotiates v2 capability with the peer and either sends
    ///   via v2 (`.handledByV2`) or signals legacy fallback (`.useLegacyIPFS`).
    func routeOutgoing(recipientUserKey: String,
                       text: String,
                       fileData: Data? = nil,
                       fileName: String? = nil,
                       fileMime: String? = nil,
                       mediaType: String? = nil,
                       isViewOnce: Bool = false,
                       appMessageId: String? = nil,
                       reveal: RevealSendContext? = nil,
                       selfCopy: Bool = false,
                       videoNote: VideoNoteMetadata? = nil,
                       originalTimestamp: Date? = nil) async -> V2RouteDecision {
        // Fast path: flag off → legacy behaviour, no router, no allocation, no I/O.
        guard V2FeatureFlag.enabled else {
            CallFileLogger.shared.log("DIAG_V2_DECLINE | why=flag_off")
            return .useLegacyIPFS
        }

        let router = await MessageManager.v2Transport.router()
        await MessageManager.v2Transport.ensureRegistered()

        switch await router.capabilities(peerUserKey: recipientUserKey) {
        case .available:
            do {
                let msgIds: [String]
                if let fileData, let fileName {
                    msgIds = try await router.sendFile(
                        peer: recipientUserKey,
                        data: fileData,
                        filename: fileName,
                        mime: fileMime ?? "application/octet-stream",
                        mediaType: mediaType,
                        isViewOnce: isViewOnce,
                        msgId: appMessageId,
                        reveal: reveal,
                        selfCopy: selfCopy && reveal == nil,
                        videoNote: videoNote,
                        originalTimestamp: originalTimestamp)
                } else {
                    // Carry the app's stable message id as the v2 envelope id so
                    // the receiver dedups it against the same message arriving over
                    // mesh/IPFS (all transports then share one id).
                    msgIds = try await router.sendText(peer: recipientUserKey, text, msgId: appMessageId,
                                                       originalTimestamp: originalTimestamp,
                                                       selfCopy: selfCopy)
                }
                return .handledByV2(msgIds: msgIds)
            } catch {
                // Any v2 failure → transparently fall back to the legacy path.
                OshiLog.messaging.info("⚠️ [V2] send failed, falling back to legacy: \(error)")
                // 🔧 DIAG (2026-08-11): this went ONLY to os_log, which is not in the
                // exportable diagnostic file — so "why is my photo on the slow path?"
                // could not be answered from a device. Every media send is currently
                // taking this fallback and nothing recorded the cause.
                CallFileLogger.shared.log("DIAG_V2_DECLINE | why=send_threw | err=\(error)")
                return .useLegacyIPFS
            }
        case .unavailable:
            CallFileLogger.shared.log("DIAG_V2_DECLINE | why=peer_has_no_bundle | peer=\(recipientUserKey.prefix(12))")
            return .useLegacyIPFS
        case .unknown:
            CallFileLogger.shared.log("DIAG_V2_DECLINE | why=capability_probe_failed | peer=\(recipientUserKey.prefix(12))")
            return .useLegacyIPFS
        }
    }

    // MARK: - Send hook (called from fallbackToIPFS)

    /// Try to deliver an outgoing 1:1 message via the v2 server. Returns `true`
    /// when v2 handled it (bubble flipped to sent, legacy upload must be
    /// skipped); `false` when the caller should run the legacy Pinata path
    /// (peer has no v2 bundle — i.e. a previous app version — or v2 errored).
    func v2TrySend(message: SecureMessage, messageId: String, recipientPublicKey: String,
                   reveal: RevealSendContext? = nil) async -> Bool {
        guard V2FeatureFlag.enabled else { return false }
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) The gate lives HERE, at the
        // transport, not at the six places that call it. `fallbackToIPFS` checks
        // too, for the bookkeeping, but this is the line that makes it a
        // guarantee rather than a preference: a new caller added next month
        // cannot route around a promise it has never heard of.
        //
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ It now asks about THIS
        // conversation as well as about the app — and that is not a tidying-up.
        // Caught on a Mac, 2026-10-23: with the conversation switched to mesh
        // and radio only, the request itself showed "Mesh" and then flipped to
        // "Internet" a second later. `sendMessage` posts a DUPLICATE to the
        // relay after a SUCCESSFUL mesh delivery (MessageManager.swift, the
        // `duplicationAffordable` branch) as store-and-forward insurance, and
        // that branch knew only the global switch. So the one message whose
        // whole purpose is to say "stop using the relay here" travelled over
        // the relay. Closing it at the caller would have left the next caller
        // open; closing it here is what the paragraph above promised.
        guard await TransportPolicy.internetAllowedForSending(inConversation: recipientPublicKey) else {
            OshiLog.messaging.infoPublic("🔒 relay refused for a conversation that is mesh/radio only")
            return false
        }

        let isMedia = message.mediaAttachment != nil
        // __PER_DEVICE_MAILBOX_2026_09_23__ user-visible messages also go to this account's
        // other devices as a `sent-copy` (device mode only; receipts, typing and the other
        // silent control payloads never do).
        let wantsSelfCopy = !(message.plaintextContent.map { pt in
            OSHIControlPayload.isSilent(pt) || OSHIControlPayload.suppressesPush(pt) || LocationMessage.isSilentPushContent(pt)
        } ?? false)
        let decision = await routeOutgoing(
            recipientUserKey: recipientPublicKey,
            text: message.plaintextContent ?? "",
            fileData: message.mediaAttachment,
            fileName: isMedia ? MessageManager.v2FileName(for: message) : nil,
            fileMime: isMedia ? MessageManager.v2Mime(for: message.mediaType) : nil,
            mediaType: message.mediaType?.rawValue,
            isViewOnce: message.isViewOnce,
            appMessageId: messageId,
            reveal: reveal,
            selfCopy: wantsSelfCopy,
            // __VIDEO_NOTE_2026_09_24__ read off the persisted message, so a retry
            // after a relaunch still sends a round video, not a square one.
            videoNote: message.videoNoteMetadata,
            // __RESEND_KEEPS_ORDER_2026_11_18__ a retry (cover block, portal, kill)
            // keeps the date the message was written: the receiver sorts by it.
            originalTimestamp: message.timestamp)

        // 🔧 DIAG (2026-08-11): a photo stuck at 30 % means v2 declined and the
        // send fell through to the legacy IPFS path — 0.30 is set only there.
        // Until now that fallback was SILENT, so "why is my photo slow" could not
        // be answered from a log. Say which branch ran, and why.
        guard case .handledByV2 = decision else {
            let line = "DIAG_MEDIA_ROUTE | v2=declined | decision=\(decision) | isMedia=\(isMedia) | bytes=\(message.mediaAttachment?.count ?? 0) | id=\(messageId.prefix(8)) — falling back to legacy IPFS"
            OshiLog.messaging.info("\(line)")
            CallFileLogger.shared.log(line)
            return false
        }
        if isMedia {
            let line = "DIAG_MEDIA_ROUTE | v2=handled | bytes=\(message.mediaAttachment?.count ?? 0) | background=\((message.mediaAttachment?.count ?? 0) >= V2MessageRouter.backgroundUploadMinBytes) | id=\(messageId.prefix(8))"
            OshiLog.messaging.info("\(line)")
            CallFileLogger.shared.log(line)
        }

        // The legacy path's notifyVPS fired the APNs wake+alert push; the v2
        // relay doesn't push, so fire the SAME push here or a recipient with
        // the app closed is never notified. Silent control payloads (receipts,
        // typing, profile sync…) queue without alerting, mirroring ?nopush=1.
        // __PUSH_LISTS_DRIFTED_2026_09_02__ voir `OSHIControlPayload.suppressesPush`:
        // ce chemin lisait sa PROPRE copie de la liste, à 8 entrées contre 10
        // `.silent` au catalogue.
        let isSilentControl = message.plaintextContent.map { pt in
            OSHIControlPayload.suppressesPush(pt)
            // Live-location update/stop pings are silent — only the initial
            // share notifies (kills the every-10s duplicate banners).
            || LocationMessage.isSilentPushContent(pt)
        } ?? false
        if !isSilentControl {
            let extra: [String: String] = [
                "senderPublicKey": message.senderPublicKey,
                "senderKey": message.senderPublicKey,
                "messageId": messageId,
                "v2": "1",
                "bodyLocKey": "notification.push.body.encrypted",
                "titleLocKey": "notification.push.title.message",
            ]
            do {
                try await VoIPPushManager.requestPushNotification(
                    to: recipientPublicKey,
                    type: "message",
                    title: "New Message",
                    body: "You have a new encrypted message",
                    additionalData: extra)
            } catch {
                OshiLog.messaging.info("⚠️ [V2] push failed: \(error.localizedDescription) — enqueuing for retry")
                await MainActor.run {
                    PushRetryQueue.shared.enqueue(
                        recipientPublicKey: recipientPublicKey,
                        type: "message",
                        title: "New Message",
                        body: "You have a new encrypted message",
                        additionalData: extra)
                }
            }
        }

        await MainActor.run {
            self.uploadStatus[messageId] = .completed
            self.uploadProgress[messageId] = 1.0

            guard let manager = MessageManager.shared,
                  let index = manager.messages.firstIndex(where: { $0.id == messageId }) else { return }
            manager.messages[index].deliveryStatus = .sent
            // "Cloud" badge. A dedicated .server case would make [SecureMessage]
            // decoding fail wholesale on previous versions (multi-device sync).
            manager.messages[index].deliveryMethod = .ipfs
            manager.saveMessages()
            // The relay accepted it — drop the pre-flight retry entry (see the
            // DURABILITY FIX in sendMessage; the enqueue happens before transport).
            manager.removeFromRetryQueue(messageId: messageId)
            OshiLog.messaging.info("✅ [V2] Sent via OSHI server (relay\(isMedia ? "+blob" : ""))")

            let messageIdCopy = messageId
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                self?.uploadStatus.removeValue(forKey: messageIdCopy)
                self?.uploadProgress.removeValue(forKey: messageIdCopy)
            }
        }
        return true
    }

    // MARK: - Group send bridge (called from MessageGroupManager)

    /// Fan a group text payload out to the v2-capable members and wake them with
    /// a push. Returns the set of member userKeys v2 delivered to; the caller
    /// (broadcastGroupMessageViaRelay) then runs the legacy Pinata path only for
    /// the members NOT in this set (old app versions). `text` is the exact
    /// payload the legacy path would carry (the base64 GroupMessage), so the
    /// receive side reconstructs an identical GroupMessage. Returns empty when
    /// the flag is off or nothing was v2-deliverable → full legacy fan-out.
    /// - Parameter messageId: the GroupMessage's own UUID string. It rides the
    ///   push so the recipient can recognise the generic remote banner as being
    ///   ABOUT the message it is a moment later about to show a rich local
    ///   banner for, and sweep the first away. Without it the recipient gets two
    ///   notifications for every single group message — see the note on `extra`.
    /// __PUSH_LISTS_DRIFTED_2026_09_02__ `groupName:` a été RETIRÉ de la
    /// signature plutôt que laissé inutilisé: un paramètre qui s'appelle comme
    /// ça, à côté d'un appel de poussée, se rebranche tout seul sur le titre — et
    /// c'est précisément la fuite qu'on vient de refermer.
    /// __GROUP_E2E_V2_2026_09_23__ `push`: §2.5 — reactions, edits, deletes and typing wake
    /// nobody. `sentAt`/`selfCopy`: §2.1 envelope `ts` and §7.2 group self copy.
    func v2TrySendGroup(groupId: String, memberKeys: [String], senderKey: String,
                        text: String,
                        messageId: String,
                        push: Bool = true,
                        sentAt: Date? = nil,
                        selfCopy: Bool = false,
                        pushOnly: [String]? = nil) async -> Set<String> {
        guard V2FeatureFlag.enabled else { return [] }
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) One of the doors to the
        // internet. Every one of them is closed by the same line, because the
        // promise is "nothing of mine leaves over the network", and a door
        // somebody forgot is the whole of the broken promise.
        //
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ A group is a conversation
        // too, filed under its `uuidString` like every other per-conversation
        // thing in this app. The group UI does not offer the switch yet; the
        // door is closed first so that it cannot ship open.
        guard await TransportPolicy.internetAllowedForSending(inConversation: groupId) else { return [] }
        let router = await MessageManager.v2Transport.router()
        await MessageManager.v2Transport.ensureRegistered()

        let at = sentAt ?? Date()
        let copy: (msgId: String, plaintext: Data)? = selfCopy
            ? GroupWireV2.groupSentCopyPayload(groupId: UUID(uuidString: groupId) ?? UUID(), message: text,
                                               msgId: messageId, sentAt: at).map { (messageId, $0) }
            : nil
        let (handled, _) = await router.sendGroupText(groupId: groupId, members: memberKeys, text,
                                                      ts: Int64(at.timeIntervalSince1970 * 1000),
                                                      selfCopy: copy)
        guard !handled.isEmpty else { return [] }
        guard push else { return Set(handled) }

        // __GROUP_COMMENTS_2026_11_21__ GROUP_INFO_SETTINGS_SPEC §2.12.3: a comment
        // wakes only the author of the comment it answers (`pushOnly`); every other
        // member receives it silently and sees it at the next fetch.
        let wakeSet: [String]
        if let pushOnly {
            let only = Set(pushOnly.map { MessageManager.canonicalIdentity($0) })
            wakeSet = handled.filter { only.contains(MessageManager.canonicalIdentity($0)) }
        } else {
            wakeSet = handled
        }

        // Wake each delivered member (group messages are user-visible → alert).
        for member in wakeSet {
            // `messageId` is NOT decoration. `NotificationManager
            // .scheduleGroupNotification` sweeps already-delivered banners whose
            // `userInfo["messageId"]` matches the message it is about to post,
            // so the generic push banner is REPLACED by the rich one rather than
            // stacking with it. Omitting the key here made that sweep match
            // nothing, and every group message delivered over v2 produced TWO
            // notifications: "OSHI — You have a new group message" from the push,
            // then "<group> — <sender>: <text>" from the app. The 1:1 v2 path has
            // always included it, which is why only groups were affected.
            let extra: [String: String] = [
                "senderPublicKey": senderKey, "senderKey": senderKey,
                "groupId": groupId, "v2": "1",
                "messageId": messageId,
                "bodyLocKey": "notification.push.body.encrypted",
                "titleLocKey": "notification.push.title.message",
            ]
            // 🔒 PRIVACY (2026-07-12, appliqué ici le 2026-09-02): le NOM du
            // groupe ne doit pas partir dans le titre. La règle est écrite dans
            // le chemin legacy (`GroupMessaging.broadcastGroupMessageViaRelay`),
            // qui envoie un `"OSHI"` générique « do NOT put the group NAME in
            // the push title/body — it would expose the group's name to the
            // APNs relay and on the lock screen ». Le chemin v2 est plus récent
            // que la note et ne l'a jamais reçue: il postait `title: groupName`,
            // donc chaque message de groupe livré en v2 donnait le nom du groupe
            // au relais. `groupId` reste dans `additionalData` — un identifiant
            // opaque suffit à router; l'app résout le nom localement.
            // __GROUP_PUSH_TYPE_2026_09_23__ `group_message`, not `group`: Android's
            // FCMService only routes `group_message` (Desktop sends it too); iOS
            // receivers accept both spellings.
            do {
                try await VoIPPushManager.requestPushNotification(
                    to: member, type: "group_message", title: "OSHI",
                    body: "You have a new encrypted message", additionalData: extra)
            } catch {
                await MainActor.run {
                    PushRetryQueue.shared.enqueue(
                        recipientPublicKey: member, type: "group_message", title: "OSHI",
                        body: "You have a new encrypted message", additionalData: extra)
                }
            }
        }
        OshiLog.messaging.info("✅ [V2] group message delivered to \(handled.count)/\(memberKeys.count) member(s) via server")
        return Set(handled)
    }

    /// Group MEDIA bridge — the blob-store counterpart of `v2TrySendGroup`.
    ///
    /// Before this existed, group media was inlined as base64 inside the group TEXT
    /// payload, so it had to squeeze through the relay's 256 KB per-envelope
    /// ciphertext cap (`ServerVPS/v2/relay_v2.js:89`). After the ~3.2× stacked
    /// base64 of the group encoding that is roughly **81 KB of actual media**;
    /// anything larger made `sendBatch` fail and dropped the WHOLE group — every
    /// member, not just the big ones — onto the legacy Pinata chain and its
    /// `ResumableUploader`. Now the bytes ride the blob store and only the small
    /// key envelope goes through the relay, so the cap is irrelevant.
    ///
    /// Returns the member userKeys v2 delivered to; the caller Pinata-falls-back for
    /// the rest, exactly as with text.
    /// __PUSH_LISTS_DRIFTED_2026_09_02__ `groupName:` retiré — voir `v2TrySendGroup`.
    func v2TrySendGroupFile(groupId: String, memberKeys: [String], senderKey: String,
                            data: Data, filename: String, mime: String,
                            mediaType: String?, groupMessageB64: String,
                            msgId: String,
                            videoNote: VideoNoteMetadata? = nil) async -> Set<String> {
        guard V2FeatureFlag.enabled else { return [] }
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ (a) One of the doors to the
        // internet. Every one of them is closed by the same line, because the
        // promise is "nothing of mine leaves over the network", and a door
        // somebody forgot is the whole of the broken promise.
        //
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ A group is a conversation
        // too, filed under its `uuidString` like every other per-conversation
        // thing in this app. The group UI does not offer the switch yet; the
        // door is closed first so that it cannot ship open.
        guard await TransportPolicy.internetAllowedForSending(inConversation: groupId) else { return [] }
        let router = await MessageManager.v2Transport.router()
        await MessageManager.v2Transport.ensureRegistered()

        let (handled, _) = await router.sendGroupFile(groupId: groupId,
                                                      members: memberKeys,
                                                      data: data,
                                                      filename: filename,
                                                      mime: mime,
                                                      mediaType: mediaType,
                                                      groupMessageB64: groupMessageB64,
                                                      msgId: msgId,
                                                      videoNote: videoNote)
        guard !handled.isEmpty else { return [] }

        for member in handled {
            // `messageId` is NOT decoration. `NotificationManager
            // .scheduleGroupNotification` sweeps already-delivered banners whose
            // `userInfo["messageId"]` matches the message it is about to post,
            // so the generic push banner is REPLACED by the rich one rather than
            // stacking with it. Omitting the key here made that sweep match
            // nothing, and every group message delivered over v2 produced TWO
            // notifications: "OSHI — You have a new group message" from the push,
            // then "<group> — <sender>: <text>" from the app. The 1:1 v2 path has
            // always included it, which is why only groups were affected.
            let extra: [String: String] = [
                "senderPublicKey": senderKey, "senderKey": senderKey,
                "groupId": groupId, "v2": "1",
                "messageId": msgId,
                "bodyLocKey": "notification.push.body.encrypted",
                "titleLocKey": "notification.push.title.message",
            ]
            // 🔒 PRIVACY (2026-07-12, appliqué ici le 2026-09-02): le NOM du
            // groupe ne doit pas partir dans le titre. La règle est écrite dans
            // le chemin legacy (`GroupMessaging.broadcastGroupMessageViaRelay`),
            // qui envoie un `"OSHI"` générique « do NOT put the group NAME in
            // the push title/body — it would expose the group's name to the
            // APNs relay and on the lock screen ». Le chemin v2 est plus récent
            // que la note et ne l'a jamais reçue: il postait `title: groupName`,
            // donc chaque message de groupe livré en v2 donnait le nom du groupe
            // au relais. `groupId` reste dans `additionalData` — un identifiant
            // opaque suffit à router; l'app résout le nom localement.
            // __GROUP_PUSH_TYPE_2026_09_23__ `group_message`, not `group`: Android's
            // FCMService only routes `group_message` (Desktop sends it too); iOS
            // receivers accept both spellings.
            do {
                try await VoIPPushManager.requestPushNotification(
                    to: member, type: "group_message", title: "OSHI",
                    body: "You have a new encrypted message", additionalData: extra)
            } catch {
                await MainActor.run {
                    PushRetryQueue.shared.enqueue(
                        recipientPublicKey: member, type: "group_message", title: "OSHI",
                        body: "You have a new encrypted message", additionalData: extra)
                }
            }
        }
        OshiLog.messaging.info("✅ [V2] group MEDIA delivered to \(handled.count)/\(memberKeys.count) member(s) via blob store")
        return Set(handled)
    }

    // MARK: - __GROUP_E2E_V2_2026_09_23__ Group state channel (spec §5)

    /// Send one framed `📢GROUP_UPDATE📢` plaintext to each recipient as an ordinary v2 1:1
    /// message (`type:"1to1"`, no `groupId`), per-device fan-out included. Nothing else carries
    /// group state any more: no IPFS pin, no `/api/queue`, no mesh. A recipient with no v2
    /// bundle gets nothing (§6). `wake`: a SILENT push (content-available, no banner, no group
    /// id) so a phone in the background picks the change up.
    /// - Returns: the recipients the relay accepted a copy for.
    @discardableResult
    func v2SendGroupState(_ framed: String, to recipients: [String], wake: Bool = true) async -> Set<String> {
        guard V2FeatureFlag.enabled, !recipients.isEmpty else { return [] }
        let router = await MessageManager.v2Transport.router()
        await MessageManager.v2Transport.ensureRegistered()
        var reached: Set<String> = []
        for peer in recipients {
            guard case .available = await router.capabilities(peerUserKey: peer) else { continue }
            do {
                let ids = try await router.sendText(peer: peer, framed)
                if !ids.isEmpty { reached.insert(peer) }
            } catch {
                OshiLog.messaging.info("⚠️ [V2] group state to \(peer.prefix(8))… failed: \(error.localizedDescription)")
            }
        }
        if wake, let me = MessageManager.sharedIdentityManager?.publicKey {
            for peer in reached {
                try? await VoIPPushManager.requestPushNotification(
                    to: peer, type: "group_update", title: "", body: "",
                    additionalData: ["senderPublicKey": me, "v2": "1"], silent: true)
            }
        }
        CallFileLogger.shared.log("DIAG_GROUPV2_STATE_TX | reached=\(reached.count)/\(recipients.count) | bytes=\(framed.utf8.count)")
        OshiLog.messaging.info("📡 [V2] group state delivered to \(reached.count)/\(recipients.count) member(s)")
        return reached
    }

    /// §6: the members with NO v2 bundle (404) — the ones who must update. A transient error
    /// (`unknown`) is not reported: the next send retries them.
    func v2UnavailableMembers(_ keys: [String]) async -> Set<String> {
        guard V2FeatureFlag.enabled else { return [] }
        let router = await MessageManager.v2Transport.router()
        var out: Set<String> = []
        for k in keys {
            if case .unavailable = await router.capabilities(peerUserKey: k) { out.insert(k) }
        }
        return out
    }

    // MARK: - Receive loop (called from checkPendingIPFSMessages)

    /// Refresh the remote config (kill switch / rollout %). UNGATED — must run
    /// even when v2 is disabled so the fleet can be re-enabled or a staged
    /// rollout can widen. Cheap (throttled to once per 5 min inside the holder).
    func v2ConfigTick() {
        V2DeviceMailbox.installDevSyncBridge()
        Task { await MessageManager.v2Transport.refreshRemoteConfig() }
    }

    /// Kick one v2 pull cycle. Cheap no-op while a cycle is already running or
    /// the flag is off. Rides the existing 1 s poll timer and APNs wakes.
    ///
    /// __COVER_PULL_JITTER_2026_11_18__ (audit finding 3) In cover mode the 1 s
    /// foreground timer used to fire a cover request every second, on the dot:
    /// a period any passive observer reads off the gaps between requests without
    /// decrypting anything, and one that no analytics SDK has. Non-forced ticks
    /// now pass through `CoverPullGate`, which admits one pull per jittered
    /// interval (uniform 2.5–7.5 s, mean 5 s). `force` is for the receiver's own
    /// already-jittered timer (`CovertChannelReceiver`).
    func v2PullTick(force: Bool = false) {
        guard V2FeatureFlag.enabled else { return }
        if !force, CoverTransport.mode != nil, !V2SyncState.coverPullGate.admit(now: Date()) { return }

        // 🔧 FIX (2026-08-10, "a message takes seconds to appear with the chat
        // open"): this used to `return` when a pull was already in flight, which
        // SILENTLY DISCARDED the request.
        //
        // That is exactly backwards for the case that matters. The 1 s polling
        // timer keeps a pull in flight a good fraction of the time, so the
        // PUSH-driven pull — the fast one, fired the instant a message arrives —
        // landed in that window and was thrown away. The message then waited for
        // the next timer tick plus a round trip: hundreds of milliseconds to
        // over a second, for a message that had already been announced.
        //
        // Coalesce instead of drop. A request arriving mid-flight sets a flag,
        // and the running pull loops once more when it finishes. At most one
        // extra pull is queued no matter how many requests arrive, so a burst of
        // pushes cannot stampede the relay.
        guard !V2SyncState.pullInProgress else {
            V2SyncState.pullRequestedDuringFlight = true
            return
        }
        V2SyncState.pullInProgress = true
        Task { [weak self] in
            defer { V2SyncState.pullInProgress = false }
            await self?.v2PullAndProcess()

            // Drain requests that arrived while we were busy. Bounded so a
            // pathological push rate cannot spin here forever — after this the
            // 1 s timer picks up anything still outstanding.
            var extraPasses = 0
            while V2SyncState.pullRequestedDuringFlight && extraPasses < 3 {
                V2SyncState.pullRequestedDuringFlight = false
                extraPasses += 1
                await self?.v2PullAndProcess()
            }
            V2SyncState.pullRequestedDuringFlight = false
        }
    }

    private func v2PullAndProcess() async {
        let me = V2KeychainCrypto().userKey
        guard !me.isEmpty else { return }

        let client = await MessageManager.v2Transport.client()
        let router = await MessageManager.v2Transport.router()

        // 🔧 FIX (2026-08-10, "the message takes a moment to appear after tapping
        // a notification"): these two used to be AWAITED here, so the pull the
        // user is waiting on was the THIRD network round trip, not the first.
        //
        // Both guards (`registered`, `lastRefillAt`) are per-process actor state,
        // so every app relaunch paid the full ladder — and a notification tap on a
        // suspended app is exactly that case. MEASURED against the live host:
        // 180–290 ms for a cold connection, 78–106 ms warm. Two extra serial
        // requests is 350–650 ms the user spends looking at an empty conversation.
        //
        // Neither is a precondition for reading your OWN mailbox. `pullMessages`
        // needs the user key and the request signature, nothing else.
        // `ensureRegistered` publishes a prekey bundle so PEERS can start sessions
        // with us, and the refill tops up a pool; both are correct one runloop
        // later. They still run on every tick until `registered` flips, so a fresh
        // install still publishes — it just no longer blocks reading.
        Task { await MessageManager.v2Transport.ensureRegistered() }
        Task { await MessageManager.v2Transport.maybeRefillPreKeys() }

        // __V2_BLOB_RETRY_IS_NOT_A_RATCHET_BREAK_2026_08_28__ Les blobs dont le
        // téléchargement a échoué se rejouent ICI, en tête de tic, et NON en
        // relâchant leur enveloppe dans le ratchet — qui ne peut plus l'ouvrir
        // et produirait une fausse preuve de casse. Voir `V2SyncState.blobPending`.
        await v2ReplayPendingBlobs(client: client, me: me)

        // __PER_DEVICE_MAILBOX_2026_09_23__ Device view when the flag is on and this install
        // is registered; the legacy view otherwise (byte-for-byte the old requests).
        let deviceSigner = await MessageManager.v2Transport.deviceTick()
        V2SyncState.deviceModeCycle = deviceSigner != nil

        let pull: V2.PullResult
        do {
            if let deviceSigner {
                pull = try await client.pullMessages(userKey: me, afterSeq: V2SyncState.cursor, signer: deviceSigner)
            } else {
                pull = try await client.pullMessages(userKey: me, afterSeq: V2SyncState.cursor)
            }
        } catch let V2.ClientError.http(status, _) where deviceSigner != nil && (status == 410 || status == 409) {
            // 410 device-unknown (removed) / 409 device-mailbox-disabled: legacy view from
            // the next tick; the server shows every item to it, so nothing is lost.
            await MessageManager.v2Transport.deviceModeRejected(status: status)
            V2SyncState.deviceModeCycle = false
            return
        } catch {
            return   // transient network error — next tick retries
        }
        // __COVER_RETRY_2026_11_18__ The disguised path answered: parked sends need
        // not wait out their backoff.
        if CoverTransport.mode != nil { MessageManager.shared?.coverRetryKick() }
        guard !pull.messages.isEmpty else { return }

        OshiLog.messaging.info("📥 [V2] pulled \(pull.messages.count) envelope(s) (cursor \(V2SyncState.cursor) → \(pull.maxSeq))")
        // __V2_PULL_IS_VISIBLE_2026_08_16__ The v2 receive path wrote NOTHING to the
        // exportable diagnostic file — every line below went to os_log only. So a v2
        // envelope that failed to decrypt left exactly the same trace as no envelope
        // at all: a push with no message and an empty log. That blind spot is what
        // made "notification but nothing in the conversation" take days instead of
        // minutes, and it is why "0 DIAG_MSG_DECRYPT_FAIL" could never mean "nothing
        // failed" — DECRYPT_FAIL is emitted by the LEGACY funnel only.
        CallFileLogger.shared.log("DIAG_V2_PULL | count=\(pull.messages.count) | cursor=\(V2SyncState.cursor) | max=\(pull.maxSeq)")

        // msgId → sender timestamp (the decrypted enum cases don't carry ts)
        // and msgId → relay seq (so a failed blob can hold back the batch ack).
        var tsByMsgId: [String: Date] = [:]
        var seqByMsgId: [String: Int] = [:]
        // __PER_DEVICE_MAILBOX_2026_09_23__ msgId → sending device, for the envelopes that
        // were addressed to THIS device. Their failures never feed the legacy repair.
        var deviceAddressed: [String: String] = [:]
        for env in pull.messages {
            tsByMsgId[env.msgId] = Date(timeIntervalSince1970: Double(env.ts) / 1000)
            seqByMsgId[env.msgId] = env.seq
            if env.toDevice != nil { deviceAddressed[env.msgId] = env.fromDevice ?? V2MessageRouter.noDeviceSlot }
        }

        // Dedup is passed IN so it happens before the ratchet, not after — see
        // receiveAndDecrypt. Re-decrypting an already-consumed envelope cannot
        // succeed and costs a spurious `.failed` that holds the ack.
        let seenBefore = Set(V2SyncState.seen)
        let incoming = await router.receiveAndDecrypt(pull, alreadySeen: seenBefore)
        // Earliest seq whose media blob failed to download this tick. We must NOT
        // ack past it, or the relay drops the still-undelivered media forever.
        var lowestFailedSeq: Int? = nil

        // __V2_FAILURE_IS_TYPED_2026_08_28__ La réparation s'ACCUMULE ici et
        // ne s'exécute qu'APRÈS la boucle, une fois par pair. Voir la passe
        // plus bas pour le défaut précis que cette forme corrige.
        var repairPlans: [String: V2RepairPlan] = [:]
        var ratchetHeldSeqByPeer: [String: Int] = [:]
        // __RESEND_HAD_ONE_VEHICLE_2026_10_28__ Peers that proved, in THIS batch, that they
        // can read a resend list. Accumulated and acted on AFTER the loop, for the same
        // reason the repair plans are: `RatchetHealCooldownTests` guards that nothing is
        // emitted per item, and it is right to — one send inside the loop is one send per
        // batch, carrying whatever single id happened to be first.
        var newlyResendCapable: Set<String> = []

        // Un blob encore en attente RETIENT l'ack. Son enveloppe est désormais
        // filtrée par `alreadySeen` — elle ne repasse plus par le ratchet —
        // donc plus rien d'autre ne la retiendrait, et le relais la
        // supprimerait alors que ses octets ne sont pas encore chez nous.
        for id in V2SyncState.blobPending.keys {
            if let s = seqByMsgId[id] { lowestFailedSeq = min(lowestFailedSeq ?? s, s) }
        }

        for item in incoming {
            switch item {
            case .text(let from, let msgId, _, let text, let groupId):
                guard !seenBefore.contains(msgId) else { continue }
                V2SyncState.markSeen(msgId)

                // __RATCHET_HEAL_OVER_V2_2026_08_28__ L'accusé de réparation a
                // déjà fait son travail: son en-tête X3DH a été adopté par
                // `decrypt1to1` pour qu'on puisse lire CETTE ligne. Il ne reste
                // qu'à ne pas l'afficher — son texte n'a jamais été destiné à
                // être lu par un humain.
                //
                // __RATCHET_RESEND_2026_08_28__ Et depuis la v2 de la charge,
                // il peut NOMMER les enveloppes que le pair n'a pas pu ouvrir.
                //
                // 🔴 LE `continue` EST LA GARDE DE VIE PRIVÉE LA PLUS FRAGILE
                // DU CHEMIN. Sans lui la charge descend dans `v2Deliver` →
                // `receiveMessage`, dont la toute première ligne écrit
                // `preview=` — les 24 premiers caractères du texte — dans
                // `CallFileLogger`, qui est EXPORTABLE. Le JSON de l'accusé,
                // c'est-à-dire une liste d'identifiants de messages, ne doit
                // jamais franchir cette ligne.
                // __RESEND_ENVELOPE_2026_08_28__ Un renvoi est déballé ici: livré
                // sous son identifiant d'ORIGINE (sinon un message redemandé
                // deux fois formerait deux bulles) et SANS NOTIFICATION.
                //
                // Le commentaire précédent affirmait qu'aucune bannière ne
                // partait. C'était vrai du push APNs, et faux du chemin local:
                // `receiveMessage` programme sa propre notification. 32 messages
                // rattrapés auraient donc produit 32 bannières.
                // __PER_DEVICE_MAILBOX_2026_09_23__ "Sent from another of my devices": only
                // ever from our own identity (nobody else can sign as us to the relay, and
                // the ratchet authenticates the sender). Inserted as OUTGOING: no
                // notification, no receipt, no control processing (spec §3.6).
                // __GROUP_E2E_V2_2026_09_23__ §7.2 group self copy: our own group message,
                // sent from another of our devices. Inserted as OUTGOING (no notification).
                if groupId == nil, V2Device.normalizeIdentity(from) == V2Device.normalizeIdentity(me),
                   let gcopy = GroupWireV2.parseGroupSentCopy(text) {
                    V2SyncState.clearRetries(msgId)
                    repairPlans[from] = nil
                    await MainActor.run {
                        MessageGroupManager.shared.receiveV2GroupSentCopy(gcopy, me: me)
                    }
                    continue
                }
                if groupId == nil, V2Device.normalizeIdentity(from) == V2Device.normalizeIdentity(me),
                   let copy = V2MessageRouter.parseSentCopy(text) {
                    V2SyncState.clearRetries(msgId)
                    repairPlans[from] = nil
                    await self.v2DeliverSentCopy(copy, envelopeTs: tsByMsgId[msgId] ?? Date(), me: me, client: client)
                    continue
                }
                if groupId == nil, let resent = RatchetHealSignal.Resent.parse(text) {
                    guard !seenBefore.contains(resent.id),
                          !V2SyncState.seen.contains(resent.id) else { continue }
                    V2SyncState.markSeen(resent.id)
                    V2SyncState.clearRetries(resent.id)
                    CallFileLogger.shared.log("DIAG_V2_RESENT_RX | id=\(resent.id.prefix(8)) | from=\(from.prefix(12))")
                    // __MAIN_THREAD_ONLY_2026_09_06__ (see receiveMessage)
                    await MainActor.run {
                        if resent.body.hasPrefix(GroupWireV2.groupUpdatePrefix) {
                            // __GROUP_E2E_V2_2026_09_23__ a re-sent group state message is
                            // still an authenticated v2 one.
                            MessageGroupManager.shared.receiveV2GroupState(
                                String(resent.body.dropFirst(GroupWireV2.groupUpdatePrefix.count)), from: from)
                        } else {
                            self.v2Deliver(text: resent.body, from: from, msgId: resent.id,
                                           timestamp: tsByMsgId[msgId] ?? Date(), me: me,
                                           silent: true)
                        }
                    }
                    continue
                }

                if groupId == nil, let heal = RatchetHealSignal.parse(text) {
                    // __HEAL_V1_SAFETY_2026_08_28__ Un accuse dont l'identifiant
                    // porte l'annonce prouve que son emetteur sait lire une
                    // liste. On le retient: c'est la SEULE condition qui nous
                    // autorisera a lui en joindre une. Un pair en 1.0.39 ne
                    // l'annoncera jamais, donc il ne recevra jamais de JSON.
                    if RatchetHealSignal.announcesListSupport(msgId) {
                        // __RESEND_HAD_ONE_VEHICLE_2026_10_28__ Record the TRANSITION to
                        // capable; the request itself goes out after the loop.
                        if !V2SyncState.peerUnderstandsResendList(from) {
                            newlyResendCapable.insert(from)
                        }
                        V2SyncState.markPeerUnderstandsResendList(from)
                    }
                    V2SyncState.clearRetries(msgId)
                    V2SyncState.clearBreakEvidence(peer: from)
                    // __REPAIR_PLAN_REVALIDATE_2026_08_28__ Retirer la PREUVE ne
                    // suffisait pas: le plan pose plus tot dans CE MEME lot
                    // survivait et s'executait quand meme apres la boucle.
                    //
                    // Scenario reel: le lot contient d'abord des enveloppes
                    // illisibles (sous l'ancienne session), puis l'accuse du
                    // pair — qui porte un en-tete X3DH, donc s'ouvre et GUERIT
                    // la session. La preuve etait bien annulee, et le plan
                    // effacait ensuite la session neuve que l'accuse venait
                    // d'etablir. Le pair repondait alors sous une session qu'on
                    // venait de detruire, sans en-tete (il est responder), donc
                    // `noSessionForDecrypt`, pendant les 60 s du delai.
                    //
                    // Une ouverture reussie apres coup est la meilleure preuve
                    // qui soit que la session marche. Elle doit annuler le plan,
                    // pas seulement la preuve.
                    repairPlans[from] = nil
                    // On ne journalise que le COMPTE: les ids sont ceux des
                    // messages de l'utilisateur.
                    CallFileLogger.shared.log("DIAG_V2_HEAL_RX | from=\(from.prefix(12)) | resend=\(heal.resend.count)")
                    OshiLog.messaging.info("🔄 [V2] accusé de réparation de \(from.prefix(8))… — session neuve adoptée")
                    if !heal.resend.isEmpty {
                        let ids = heal.resend
                        Task { [weak self] in await self?.v2ResendRequested(from: from, msgIds: ids) }
                    }
                    continue
                }
                // It decoded — drop any failure count it accumulated while the
                // ratchet was out of step.
                V2SyncState.clearRetries(msgId)
                // Un message de ce pair vient d'être ouvert: la session est
                // VIVANTE. C'est la preuve directe qu'une enveloppe fautive
                // était un défaut isolé, pas une casse.
                V2SyncState.clearBreakEvidence(peer: from)
                    // __REPAIR_PLAN_REVALIDATE_2026_08_28__ Retirer la PREUVE ne
                    // suffisait pas: le plan pose plus tot dans CE MEME lot
                    // survivait et s'executait quand meme apres la boucle.
                    //
                    // Scenario reel: le lot contient d'abord des enveloppes
                    // illisibles (sous l'ancienne session), puis l'accuse du
                    // pair — qui porte un en-tete X3DH, donc s'ouvre et GUERIT
                    // la session. La preuve etait bien annulee, et le plan
                    // effacait ensuite la session neuve que l'accuse venait
                    // d'etablir. Le pair repondait alors sous une session qu'on
                    // venait de detruire, sans en-tete (il est responder), donc
                    // `noSessionForDecrypt`, pendant les 60 s du delai.
                    //
                    // Une ouverture reussie apres coup est la meilleure preuve
                    // qui soit que la session marche. Elle doit annuler le plan,
                    // pas seulement la preuve.
                    repairPlans[from] = nil
                V2SyncState.clearUnreadable(msgId)
                // __MAIN_THREAD_ONLY_2026_09_06__ see above.
                await MainActor.run {
                    if let groupId {
                        self.v2DeliverGroup(text: text, from: from, groupId: groupId)
                    } else if text.hasPrefix(GroupWireV2.groupUpdatePrefix) {
                        // __GROUP_E2E_V2_2026_09_23__ §5 state channel: authenticated sender =
                        // the ratchet that opened it. Never stored, never a receipt.
                        MessageGroupManager.shared.receiveV2GroupState(
                            String(text.dropFirst(GroupWireV2.groupUpdatePrefix.count)), from: from)
                    } else {
                        self.v2Deliver(text: text, from: from, msgId: msgId,
                                       timestamp: tsByMsgId[msgId] ?? Date(), me: me)
                    }
                }

            case .file(let from, let msgId, _, let key, let groupId):
                guard !seenBefore.contains(msgId) else { continue }
                V2SyncState.markSeen(msgId)
                V2SyncState.clearRetries(msgId)
                // L'enveloppe s'est OUVERTE: la session est vivante, quoi qu'il
                // arrive ensuite au blob.
                V2SyncState.clearBreakEvidence(peer: from)
                    // __REPAIR_PLAN_REVALIDATE_2026_08_28__ Retirer la PREUVE ne
                    // suffisait pas: le plan pose plus tot dans CE MEME lot
                    // survivait et s'executait quand meme apres la boucle.
                    //
                    // Scenario reel: le lot contient d'abord des enveloppes
                    // illisibles (sous l'ancienne session), puis l'accuse du
                    // pair — qui porte un en-tete X3DH, donc s'ouvre et GUERIT
                    // la session. La preuve etait bien annulee, et le plan
                    // effacait ensuite la session neuve que l'accuse venait
                    // d'etablir. Le pair repondait alors sous une session qu'on
                    // venait de detruire, sans en-tete (il est responder), donc
                    // `noSessionForDecrypt`, pendant les 60 s du delai.
                    //
                    // Une ouverture reussie apres coup est la meilleure preuve
                    // qui soit que la session marche. Elle doit annuler le plan,
                    // pas seulement la preuve.
                    repairPlans[from] = nil
                V2SyncState.clearUnreadable(msgId)
                let ts = tsByMsgId[msgId] ?? Date()
                let delivered = await self.v2FetchAndDeliverBlob(
                    key: key, from: from, msgId: msgId, groupId: groupId,
                    timestamp: ts, me: me, client: client,
                    deleteAfterDelivery: deviceAddressed[msgId] == nil)
                if !delivered {
                    // __V2_BLOB_RETRY_IS_NOT_A_RATCHET_BREAK_2026_08_28__ Le blob
                    // a échoué (hors ligne à mi-course, TTL, …), PAS le ratchet.
                    // Le msgId RESTE dans `seen`: relâché, il repasserait par un
                    // ratchet qui a déjà consommé sa clé, échouerait en AEAD et
                    // se ferait classer `ratchetBreak` — une fausse preuve de
                    // casse qui, avec une réaction immédiate, effacerait une
                    // session saine.
                    V2SyncState.rememberPendingBlob(.init(msgId: msgId, from: from,
                                                          groupId: groupId, key: key,
                                                          ts: ts.timeIntervalSince1970,
                                                          attempts: 0))
                    if let s = seqByMsgId[msgId] {
                        lowestFailedSeq = min(lowestFailedSeq ?? s, s)
                    }
                }

            case .failed(let from, let msgId, let seq, let error, let kind, let failedGroupId):
                // __PER_DEVICE_MAILBOX_2026_09_23__ A per-device copy that does not open is
                // a DEVICE-session problem: never a legacy repair (which would delete the
                // healthy account session with this peer — the reset storm of spec §3.4).
                // Held within the usual ack budget; once spent, our side of that device
                // session is forgotten so our next message re-runs X3DH with a header.
                if let fromDevice = deviceAddressed[msgId] {
                    if V2SyncState.shouldRetryUndecryptable(msgId), let s = seqByMsgId[msgId] {
                        lowestFailedSeq = min(lowestFailedSeq ?? s, s)
                    } else if fromDevice != V2MessageRouter.noDeviceSlot {
                        await router.dropDeviceSession(peer: from, device: fromDevice)
                    }
                    CallFileLogger.shared.log("DIAG_V2_DEVICE_UNDECRYPTABLE | id=\(msgId.prefix(8)) | from=\(from.prefix(12)) | kind=\(kind.rawValue)")
                    continue
                }
                // __GROUP_FAILURE_WIPES_1TO1_2026_08_28__ L'echec d'une enveloppe
                // de GROUPE ne doit jamais piloter la reparation d'une session
                // 1:1: le ratchet 1:1 avec ce membre peut etre parfaitement sain,
                // et l'effacer casserait une conversation qui marchait pour
                // reparer une autre chose. On retient l'enveloppe comme avant —
                // le budget d'ack la fera partir — mais sans alimenter aucune
                // preuve ni aucun plan.
                if failedGroupId != nil {
                    if V2SyncState.shouldRetryUndecryptable(msgId), let s = seqByMsgId[msgId] {
                        lowestFailedSeq = min(lowestFailedSeq ?? s, s)
                    }
                    CallFileLogger.shared.log("DIAG_V2_GROUP_UNDECRYPTABLE | id=\(msgId.prefix(8)) | from=\(from.prefix(12)) | kind=\(kind.rawValue)")
                    continue
                }
                // __V2_FAILURE_IS_TYPED_2026_08_28__ DEUX décisions, longtemps
                // soudées dans un seul compteur, et qui n'ont pas la même
                // unité.
                //
                //  (1) RETENIR L'ACK — généreux, inchangé à 8 tics. Ça ne
                //      retarde AUCUNE livraison (les messages sains du même
                //      lot passent dans cette même boucle) et le relais garde
                //      30 jours. C'est presque gratuit; on ne l'échange pas
                //      contre de la vitesse.
                //  (2) RÉAGIR (effacer + soigner) — porté par une preuve
                //      TYPÉE, immédiate là où l'effacement ne détruit rien.
                //      Réessayer les mêmes octets contre le même état est un
                //      calcul déterministe: 8 tics n'apportent aucune
                //      information de plus que 1.
                let stillHasAckBudget = V2SyncState.shouldRetryUndecryptable(msgId, kind: kind)

                // La preuve ne compte que pour une enveloppe JAMAIS livrée.
                // `seen` est plafonné à 500: un arriéré plus long ferait
                // sortir d'anciens msgId de la fenêtre, ils seraient
                // re-déchiffrés, échoueraient en AEAD et RESSEMBLERAIENT à une
                // casse. La queue étant tirée avec `afterSeq: cursor`, la
                // condition est normalement toujours vraie — elle est là comme
                // marge explicite, pas comme détail.
                let isFresh = seq > V2SyncState.cursor
                let proven = isFresh && V2SyncState.breakEvidence(peer: from, kind: kind, msgId: msgId)

                if stillHasAckBudget {
                    // __ACK_HOLD_DEADLOCK_2026_08_28__ La retenue est notee PAR
                    // PAIR, pas fondue tout de suite dans `lowestFailedSeq`.
                    //
                    // Sinon: on retient l'ack sur l'enveloppe qui a servi de
                    // preuve, on repare, on efface la session — et cette
                    // enveloppe, chiffree sous l'ANCIENNE session, ne pourra
                    // JAMAIS s'ouvrir. Elle reste donc sur le relais, est
                    // re-tiree au tic suivant, se reclasse `ratchetBreak` et
                    // RE-ARME la preuve contre la session qu'on vient de
                    // reparer. La reparation se declenche en boucle, sur sa
                    // propre trace.
                    //
                    // Une fois la reparation partie, ces enveloppes-la sont
                    // definitivement illisibles: les retenir ne sauve rien et
                    // garantit la boucle. On ne les retient donc que si la
                    // reparation N'A PAS abouti (voir apres la boucle).
                    if let s = seqByMsgId[msgId] {
                        ratchetHeldSeqByPeer[from] = min(ratchetHeldSeqByPeer[from] ?? s, s)
                    }
                    OshiLog.messaging.info("↩️ [V2] undecryptable \(msgId.prefix(8))… from \(from.prefix(8))… — holding for retry: \(error)")
                    // __V2_PULL_IS_VISIBLE_2026_08_16__
                    CallFileLogger.shared.log("DIAG_V2_UNDECRYPTABLE | id=\(msgId.prefix(8)) | from=\(from.prefix(12)) | kind=\(kind.rawValue) | err=\(error)")
                } else {
                    // Budget spent. Let it go, or the mailbox never drains.
                    OshiLog.messaging.info("❌ [V2] giving up on \(msgId.prefix(8))… from \(from.prefix(8))…: \(error)")
                    // __V2_PULL_IS_VISIBLE_2026_08_16__ The moment a message the user
                    // was already notified about is thrown away for good.
                    CallFileLogger.shared.log("DIAG_V2_GIVEUP | id=\(msgId.prefix(8)) | from=\(from.prefix(12)) | kind=\(kind.rawValue) | err=\(error)")
                }

                // Une classe non prévue retombe en `.transient` et n'apporte
                // donc aucune preuve: l'ancien filet (budget épuisé) reste sa
                // seule porte, exactement comme avant. Et on le DIT, plutôt que
                // de le fondre dans un défaut silencieux.
                if kind == .transient {
                    CallFileLogger.shared.log("DIAG_V2_FAIL_UNCLASSIFIED | id=\(msgId.prefix(8)) | from=\(from.prefix(12)) | err=\(error)")
                }

                guard proven || !stillHasAckBudget else { continue }

                // __RATCHET_RESEND_2026_08_28__ Le SEUL endroit du code où
                // `msgId` et `from` sont tous deux connus. `.failed` ne porte
                // pas de `groupId`: un id de groupe entrera donc aussi ici, et
                // l'expéditeur le SAUTERA sans erreur.
                V2SyncState.markUnreadable(msgId: msgId, from: from, envelopeTs: tsByMsgId[msgId])

                // On n'AGIT PAS ici. Voir la passe unique après la boucle: agir
                // par item est le défaut qui détruisait la session neuve que
                // l'accusé venait de créer.
                V2SyncState.noteRepairWanted(peer: from)
                if let s = seqByMsgId[msgId] {
                    let previous = repairPlans[from]
                    repairPlans[from] = V2RepairPlan(
                        kind: previous.map { v2MergeRepairKind($0.kind, kind) } ?? kind,
                        lowestSeq: min(previous?.lowestSeq ?? s, s))
                } else if repairPlans[from] == nil {
                    repairPlans[from] = V2RepairPlan(kind: kind, lowestSeq: Int.max)
                }
            }
        }

        // ==================================================================
        // PASSE UNIQUE DE RÉPARATION — une fois par PAIR, jamais par item.
        //
        // 🔴 LE DÉFAUT QU'ELLE CORRIGE, ET IL EST ACTUEL, PAS THÉORIQUE.
        // Le délai était testé APRÈS `deleteSession`. Sur un lot de k ≥ 2
        // enveloppes cassées du même pair — le cas NORMAL d'une session morte,
        // toutes épuisant leur budget au même tic — l'item #1 effaçait,
        // envoyait l'accusé (`sendHealSignal` → `sendText` → `encrypt1to1` →
        // `loadOrEstablish` CRÉE ET PERSISTE une session initiateur), puis
        // l'item #2 rappelait `deleteSession` et DÉTRUISAIT cette session
        // neuve, pendant que le délai bloquait tout nouvel accusé pendant
        // 60 s. Le pair adoptait notre X3DH et répondait sous une session que
        // nous venions d'effacer; ses réponses, étant celles d'un responder,
        // ne portent AUCUN en-tête ⇒ `noSessionForDecrypt` ⇒ conversation
        // morte pour une minute pleine, puis le cycle recommence.
        //
        // Réagir plus tôt rend ce scénario PLUS fréquent, pas moins: c'est un
        // préalable à la réaction immédiate, pas un raffinement.
        //
        // Au passage, `sendSessionResetNotification` (compat 1.0.39) partait
        // lui aussi par item, sans aucun délai. Il est hissé ici.
        // ==================================================================
        var healFailedSeq: Int? = nil
        var repairedPeers: Set<String> = []
        for (peer, plan) in repairPlans {
            let ok = await v2RepairPeer(peer: peer, plan: plan, me: me)
            if ok { repairedPeers.insert(peer) }
            if !ok, plan.lowestSeq != Int.max {
                // Acquitter alors que la réparation n'est pas partie perdrait À
                // LA FOIS le message ET le déclencheur: si aucune autre
                // enveloppe n'arrive, la conversation reste morte. Le budget
                // d'ack (8) reste le plafond absolu au-dessus de cette retenue.
                healFailedSeq = min(healFailedSeq ?? plan.lowestSeq, plan.lowestSeq)
            }
        }

        // __RESEND_HAD_ONE_VEHICLE_2026_10_28__ Un pair qui vient de PROUVER qu'il sait lire
        // une liste, alors qu'on tient encore des ids qu'on n'a jamais pu lui redemander.
        //
        // LA FAILLE QUE CECI FERME, et c'est une perte définitive, pas un retard. La demande
        // n'avait qu'UN véhicule: l'accusé émis par `v2RepairPeer`, qui ne part que sur une
        // session PROUVÉE morte. La liste était donc l'otage d'une coïncidence — il fallait
        // que le pair soit DÉJÀ connu capable à cet instant précis. Si son bundle était
        // antérieur à la capacité, ou si la lecture échouait pile là, la liste était retenue,
        // la session guérissait, et aucune autre casse ne venait. Pas de casse, pas d'accusé;
        // pas d'accusé, pas de véhicule. Les ids expiraient sept jours plus tard, jamais
        // demandés, et les messages étaient perdus.
        //
        // Rapporté depuis l'autre bout comme: « je reçois toutes les notifications mais le
        // message n'apparaît souvent jamais ».
        //
        // APRÈS la passe de réparation, délibérément: un pair qu'on vient de réparer a déjà
        // reçu sa liste dans l'accusé de réparation, et `clearUnreadable` a vidé son
        // registre — il ne reste donc rien à demander, et le délai partagé n'est pas
        // dépensé à la place de la réparation, qui compte davantage.
        for peer in newlyResendCapable where !repairedPeers.contains(peer) {
            await Self.requestPendingResends(from: peer)
        }

        // Ack so the relay drops the batch — but only up to the seq BEFORE the
        // earliest failed media blob, so undelivered media stays queued for the
        // next tick. Everything already delivered is in the seen-set, so the
        // re-pulled prefix is deduped (no double delivery, just a little replay
        // until the blob succeeds). With no failures we ack the whole batch.
        // __ACK_HOLD_DEADLOCK_2026_08_28__ Les enveloppes d'un pair REPARE sont
        // laissees partir: elles etaient chiffrees sous la session qu'on vient
        // d'effacer et ne s'ouvriront jamais. Les retenir ferait re-tirer, a
        // chaque tic, la trace meme qui a declenche la reparation — et donc
        // re-declencherait la reparation, indefiniment.
        //
        // Ce que ca coute, et il faut le dire: ces messages-la sont PERDUS pour
        // de bon cote reception. C'est precisement ce que la liste de renvoi
        // vient rattraper — leurs identifiants ont ete notes AVANT
        // (`markUnreadable`) et voyagent dans l'accuse.
        for (peer, seq) in ratchetHeldSeqByPeer where !repairedPeers.contains(peer) {
            lowestFailedSeq = min(lowestFailedSeq ?? seq, seq)
        }
        if let h = healFailedSeq { lowestFailedSeq = min(lowestFailedSeq ?? h, h) }
        let ackSeq = lowestFailedSeq.map { $0 - 1 } ?? pull.maxSeq
        guard ackSeq > V2SyncState.cursor else {
            OshiLog.messaging.info("⏸️ [V2] holding ack at cursor \(V2SyncState.cursor) — media blob at seq \(lowestFailedSeq ?? -1) pending re-pull")
            return
        }
        do {
            if let deviceSigner {
                try await client.ack(userKey: me, upToSeq: ackSeq, signer: deviceSigner)
            } else {
                try await client.ack(userKey: me, upToSeq: ackSeq)
            }
            V2SyncState.cursor = ackSeq
        } catch let V2.ClientError.http(status, _) where deviceSigner != nil && (status == 410 || status == 409) {
            await MessageManager.v2Transport.deviceModeRejected(status: status)
        } catch {
            OshiLog.messaging.info("⚠️ [V2] ack failed (will re-pull): \(error)")
        }
    }

    // MARK: - Redemander ce qu'on n'a jamais pu lire, hors réparation

    /// __RESEND_HAD_ONE_VEHICLE_2026_10_28__ Ask a peer to re-send the envelopes we could
    /// never open, WITHOUT first breaking anything.
    ///
    /// Until this existed, a resend list could only travel on the heal emitted by
    /// `v2RepairPeer` — i.e. only at the moment a session was proven dead. That made the
    /// list hostage to a coincidence: the peer had to be already KNOWN to read one at that
    /// exact instant. A peer whose bundle predated `CAP_RESEND_LIST`, or whose capability
    /// read failed right then, got the bare marker; the session healed; no further break
    /// ever came; and the ids aged out of the registry seven days later unasked.
    ///
    /// This runs instead the moment we LEARN the peer is capable, which is the other
    /// natural trigger and the one that was missing.
    ///
    /// ⚠️ It must NOT delete the session. `v2RepairPeer` deletes first because its job is to
    /// repair a dead one, and the X3DH header that carries the repair is a side effect of
    /// having no session. Here the session is healthy — destroying it to ask a question
    /// would create the very outage this is recovering from.
    ///
    /// The cooldown is shared with the repair path on purpose: two heals to one peer in
    /// quick succession is the amplification `__RESEND_AMPLIFICATION_2026_08_28__` bounds,
    /// and a request is as loud as a repair.
    static func requestPendingResends(from peer: String, now: Date = Date()) async {
        guard V2SyncState.peerUnderstandsResendList(peer) else { return }
        let pending = V2SyncState.drainUnreadable(for: peer, now: now)
        guard !pending.isEmpty else { return }
        guard RatchetHealSignal.Policy.maySend(lastSentAt: V2SyncState.lastHealSent(to: peer), now: now) else {
            CallFileLogger.shared.log("DIAG_V2_RESEND_ASK | to=\(peer.prefix(12)) | result=cooldown | pending=\(pending.count)")
            return
        }
        do {
            _ = try await MessageManager.v2Transport.router().sendHealSignal(to: peer, resend: pending)
            V2SyncState.markHealSent(to: peer, at: now)
            // Only after it actually left: an id forgotten on a failed send is a message
            // nobody will ever ask for again.
            for id in pending { V2SyncState.clearUnreadable(id) }
            CallFileLogger.shared.log("DIAG_V2_RESEND_ASK | to=\(peer.prefix(12)) | result=sent | resend=\(pending.count)")
            OshiLog.messaging.info("🔁 [V2] \(peer.prefix(8))… sait lire une liste — \(pending.count) message(s) redemandé(s)")
        } catch {
            CallFileLogger.shared.log("DIAG_V2_RESEND_ASK | to=\(peer.prefix(12)) | result=error | err=\(error)")
        }
    }

    // MARK: - Réparation d'une session morte (passe unique, par pair)

    /// - Returns: `true` quand il n'y a plus rien à retenir pour ce pair —
    ///   soit la réparation est partie, soit il n'y en avait pas à faire.
    ///   `false` quand la réparation a ÉCHOUÉ et que l'ack doit être retenu.
    private func v2RepairPeer(peer: String, plan: V2RepairPlan, me: String) async -> Bool {
        let now = Date()

        // Ne soigner qu'un pair avec lequel une relation EXISTE.
        //
        // Le relais lie déjà `from` à l'identité authentifiée (il refuse un
        // `sender-mismatch` en 403), donc personne ne peut usurper `from` pour
        // nous faire effacer la session d'un TIERS. Reste qu'un compte
        // quelconque peut nous écrire: sans session c'est `noSession`, classe
        // conclusive à 1, qui nous ferait tirer un bundle et émettre vers un
        // inconnu à CHAQUE enveloppe. Le délai borne le débit par pair, pas le
        // nombre de pairs.
        guard v2HasRelationship(with: peer) else {
            CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | result=skip_no_relationship")
            return true
        }

        switch plan.kind {
        case .malformedEnvelope, .spoofedIdentity:
            // Des octets abîmés ou une identité qui ne colle pas ne prouvent
            // rien sur la session. On journalise, on laisse passer l'ack, on
            // n'efface RIEN.
            CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | result=skip_kind | kind=\(plan.kind.rawValue)")
            return true

        case .unusablePreKey:
            // __OPK_MUST_BE_OURS_2026_08_16__ Le pair a bootstrapé avec une
            // pré-clé d'une génération que cet appareil ne détient plus. Un
            // accusé ne corrige pas ça — il RECOMMENCERAIT le problème, le
            // serveur pouvant reservir une clé de la même génération morte.
            // Ce qu'il faut, c'est republier NOTRE matériel.
            await MessageManager.v2Transport.forceRepublishKeys()
            CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | result=prekeys_republished")
            return true

        case .noSession, .gapTooLarge, .ratchetBreak, .transient:
            break
        }

        // Départage symétrique. Le délai brise une boucle DÉCALÉE; deux
        // appareils qui réagissent au même lot cassé dans la même seconde
        // forment une boucle SYMÉTRIQUE que le délai ne fait que répéter à
        // l'identique. Un des deux côtés attend donc un délai complet — sans
        // rien cesser d'ADOPTER entre-temps (RATCHET-H1 est inchangé).
        if !RatchetHealSignal.Policy.isHealInitiator(me: me, peer: peer) {
            let since = V2SyncState.breakEvidenceSince(peer: peer) ?? now
            if now.timeIntervalSince(since) < RatchetHealSignal.Policy.cooldown {
                CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | result=yield_to_initiator")
                return true
            }
        }

        guard RatchetHealSignal.Policy.maySend(lastSentAt: V2SyncState.lastHealSent(to: peer), now: now) else {
            CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | result=cooldown")
            return true
        }

        // __V2_RATCHET_DEAD_END_2026_08_16__ `deleteSession` a existé pendant
        // des mois SANS AUCUN APPELANT: une session v2 devenue mauvaise était
        // réutilisée, inchangée, pour toujours — `loadOrEstablish` court-circuite
        // sur `hasSession`, donc aucun bundle sain sur le serveur ne pouvait la
        // sauver. MESURÉ sur les deux téléphones: 176 enveloppes au-delà du
        // plafond, c'est-à-dire 176 messages annoncés puis détruits, non lus.
        //
        // L'effacement doit rester ICI, dans la passe unique, et JAMAIS dans la
        // boucle: c'est lui qui détruisait la session neuve de l'item précédent.
        try? V2SessionStore().deleteSession(peerUserKey: peer)

        // __RATCHET_HEAL_OVER_V2_2026_08_28__ Effacer NOTRE session ne répare
        // rien à soi seul: le pair croit la sienne bonne, et seul un expéditeur
        // SANS session attache un en-tête X3DH. Comme la session vient d'être
        // effacée, l'accusé PORTE cet en-tête: le pair l'adopte en le
        // déchiffrant. C'est la réparation, pas une demande de réparation.
        //
        // __RATCHET_RESEND_2026_08_28__ Et il emporte la liste de ce qu'on n'a
        // pas pu lire. La liste est construite ICI, après la boucle: construite
        // depuis l'enveloppe courante elle n'aurait contenu qu'UN id, puisque
        // le délai faisait que seul le PREMIER abandon d'un tic émettait.
        // __HEAL_V1_SAFETY_2026_08_28__ La liste ne part QUE vers un pair qui a
        // prouve savoir la lire, en l'annoncant dans l'identifiant d'un accuse
        // precedent. Sinon on vide quand meme le registre — sans quoi les ids
        // s'y accumuleraient a vie pour un pair qui ne les redemandera jamais —
        // mais on n'envoie que la forme nue, celle que 1.0.39 reconnait.
        // __CLIENT_CAPS_2026_08_31__ RAFRAICHIR les capacites du pair AVANT de
        // decider, pas apres.
        //
        // Defaut trouve par un test qui a echoue, pas par relecture:
        // `loadOrEstablish` rend la session existante SANS recuperer le bundle
        // (V2MessageRouter: `if let existing = ... { return existing }`). Pour
        // un pair avec qui on parle deja — c'est-a-dire tous ceux qui comptent —
        // les capacites n'etaient donc JAMAIS apprises, et la correction se
        // contentait de deplacer le probleme d'un cran.
        //
        // Ici, la session vient d'etre effacee juste au-dessus: `capabilities()`
        // ne peut plus sortir tot sur `hasSession` et va donc reellement lire le
        // bundle. Et ca ne coute AUCUN aller-retour supplementaire — il met le
        // bundle en cache, que `sendHealSignal` consomme quelques lignes plus
        // bas au lieu de le redemander.
        _ = await MessageManager.v2Transport.router().capabilities(peerUserKey: peer)

        let capable = V2SyncState.peerUnderstandsResendList(peer)
        let drained = V2SyncState.drainUnreadable(for: peer, now: now)
        let resend = capable ? drained : []
        if !capable && !drained.isEmpty {
            CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | resend_withheld=\(drained.count) | raison=pair_v1")
        }
        do {
            try await MessageManager.v2Transport.router().sendHealSignal(to: peer, resend: resend)
            V2SyncState.markHealSent(to: peer, at: now)
            V2SyncState.clearRetries("heal:" + peer)
            // La preuve a servi: on repart de zéro. Sinon un pair « prouvé
            // cassé » une fois le resterait, et la prochaine enveloppe fautive
            // déclencherait une réaction sur une seule enveloppe.
            V2SyncState.clearBreakEvidence(peer: peer)
            // On n'oublie les ids qu'APRÈS un envoi réussi.
            for id in resend { V2SyncState.clearUnreadable(id) }
            CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | result=sent | x3dh=1 | resend=\(resend.count)")
            OshiLog.messaging.info("🔄 [V2] session avec \(peer.prefix(8))… effacée, accusé parti (\(resend.count) à renvoyer) — le prochain message refait X3DH")

            // Conservé pour les pairs en 1.0.39 et antérieur, qui ne savent pas
            // lire l'accusé v2 mais savent lire celui-ci. Hissé dans cette passe
            // avec le reste: il partait par item, sans aucun délai.
            // À retirer quand plus personne n'est en dessous de 1.0.40.
            if let im = MessageManager.sharedIdentityManager {
                await self.sendSessionResetNotification(to: peer, identityManager: im)
            }
            return true
        } catch {
            CallFileLogger.shared.log("DIAG_V2_HEAL_TX | to=\(peer.prefix(12)) | result=error | err=\(error)")
            // Acquitter maintenant perdrait à la fois le message ET le
            // déclencheur. On retient — mais pas au-delà du budget d'ack, sinon
            // un pair injoignable gèlerait le curseur pour toujours.
            return !V2SyncState.shouldRetryUndecryptable("heal:" + peer)
        }
    }

    /// Une relation existe: un message échangé, une session vivante, ou un
    /// accusé déjà émis vers ce pair.
    private func v2HasRelationship(with peer: String) -> Bool {
        if V2SyncState.lastHealSent(to: peer) != nil { return true }
        if V2SessionStore().hasSession(peerUserKey: peer) { return true }
        let p = MessageManager.canonicalIdentity(peer)
        return messages.contains {
            MessageManager.canonicalIdentity($0.senderPublicKey) == p
                || MessageManager.canonicalIdentity($0.recipientPublicKey) == p
        }
    }

    // MARK: - Renvoi demandé par un pair

    /// Ce qu'on fait d'UN identifiant demandé — fonction PURE: pas d'horloge
    /// interne, pas de réseau, pas d'état global. C'est ce qui la rend
    /// vérifiable cas par cas, y compris la garde de sécurité.
    enum V2ResendDecision: String, Equatable, Sendable {
        case send
        /// Inconnu du magasin local. Cas NORMAL, pas une erreur: une enveloppe
        /// de GROUPE porte un UUID fabriqué par le routeur, et l'extension de
        /// partage n'enregistre aucun identifiant applicatif.
        case notfound
        /// 🔴 LA GARDE DE SÉCURITÉ. Le message existe mais il n'est pas de moi
        /// vers CE pair. Sans elle, un pair qui DEVINE ou REJOUE un id se fait
        /// livrer le contenu d'un tiers.
        case notmine
        case deleted
        /// Média: hors périmètre de la phase 1, explicitement plutôt que par
        /// omission.
        case media
        case age
        case budget
    }

    static func v2ResendDecision(message: SecureMessage?,
                                 me: String,
                                 peer: String,
                                 now: Date,
                                 attemptsSoFar: Int,
                                 peerBudgetLeft: Int) -> V2ResendDecision {
        guard let m = message else { return .notfound }
        guard MessageManager.canonicalIdentity(m.senderPublicKey) == MessageManager.canonicalIdentity(me),
              MessageManager.canonicalIdentity(m.recipientPublicKey) == MessageManager.canonicalIdentity(peer)
        else { return .notmine }
        if m.isDeletedForEveryone { return .deleted }
        // `mediaAttachment` est retiré de l'index à la sauvegarde et rerattaché
        // depuis le disque au chargement, mais purgé pour de bon sur une
        // vue-unique consommée: un renvoi média devrait gérer un contenu absent
        // ET une réservation de blob complète. `mediaType` est donc le test, pas
        // la présence des octets.
        if m.mediaAttachment != nil || m.mediaType != nil { return .media }
        let age = now.timeIntervalSince(m.timestamp)
        if age > RatchetHealSignal.Policy.maxResendAge { return .age }
        guard RatchetHealSignal.Policy.mayResend(attemptsSoFar: attemptsSoFar,
                                                 messageAge: age,
                                                 peerBudgetLeft: peerBudgetLeft) else { return .budget }
        return .send
    }


    /// Réémet les messages qu'un pair déclare n'avoir pas pu ouvrir.
    ///
    /// 🔴 LA GARDE DE PROPRIÉTÉ (a) EST UNE GARDE DE SÉCURITÉ, PAS UNE
    /// OPTIMISATION. Sans elle, un pair qui DEVINE ou REJOUE un identifiant se
    /// fait réémettre un message destiné à quelqu'un d'autre. La normalisation
    /// est la même qu'à `receiveMessage` et qu'à `handleIncomingDeliveryReceipt`.
    ///
    /// POURQUOI SOUS LE MÊME msgId, ET POURQUOI C'EST IDEMPOTENT
    /// La branche `.failed` n'appelle JAMAIS `markSeen` (contrairement à
    /// `.text` et `.file`), donc `receiveAndDecrypt` ne filtrera pas l'id; et
    /// si l'original avait fini par arriver, `receiveMessage` le jette sur son
    /// doublon d'id. Un UUID neuf, lui, produirait une SECONDE bulle.
    ///
    /// POURQUOI AUCUNE NOTIFICATION
    /// Dans le chemin v2 le push est un appel SÉPARÉ, fait uniquement par
    /// `v2TrySend`. `router.sendText` seul n'en déclenche aucun — et c'est ce
    /// qu'on veut: le destinataire a été notifié à l'époque, 32 bannières de
    /// rattrapage seraient le bug d'à côté.
    func v2ResendRequested(from peer: String, msgIds: [String]) async {
        guard V2FeatureFlag.enabled else { return }

        // __RESEND_AMPLIFICATION_2026_08_28__ Cette voie est declenchee par une
        // charge que le PAIR controle entierement, et le delai de 60 s ne la
        // couvrait pas: il ne gardait que l'emission de l'accuse.
        //
        // Un pair pouvait donc envoyer 200 messages ordinaires portant chacun
        // un accuse avec 32 identifiants, et declencher 6400 examens de renvoi
        // chez nous. Le budget horaire par pair borne les ENVOIS, pas le
        // travail: chaque examen parcourt `messages`, qui est le fil complet.
        //
        // Le delai est donc pose sur la DEMANDE elle-meme, avant tout travail.
        // Une seconde demande dans la minute est ignoree — un pair reellement
        // casse en re-emettra une, et il n'a rien perdu: ses identifiants
        // restent dans SON registre jusqu'a ce qu'ils soient servis.
        let peerForRate = MessageManager.canonicalIdentity(peer)
        guard RatchetHealSignal.Policy.maySend(
                lastSentAt: V2SyncState.lastResendServed(to: peerForRate), now: Date()) else {
            CallFileLogger.shared.log("DIAG_V2_RESEND | to=\(peerForRate.prefix(12)) | result=ignore | why=cooldown")
            return
        }
        V2SyncState.markResendServed(to: peerForRate)

        let me = MessageManager.canonicalIdentity(
            MessageManager.sharedIdentityManager?.publicKey ?? V2KeychainCrypto().userKey)
        guard !me.isEmpty else { return }
        let peerCanonical = MessageManager.canonicalIdentity(peer)
        let router = await MessageManager.v2Transport.router()
        let now = Date()

        for id in msgIds.prefix(RatchetHealSignal.Policy.maxResendPerRequest) {
            let stored = messages.first { $0.id == id }
            let decision = MessageManager.v2ResendDecision(
                message: stored,
                me: me,
                peer: peerCanonical,
                now: now,
                attemptsSoFar: V2SyncState.resendAttempts(id),
                peerBudgetLeft: V2SyncState.peerResendBudgetLeft(peerCanonical, now: now))
            guard decision == .send, let m = stored else {
                CallFileLogger.shared.log("DIAG_V2_RESEND | id=\(id.prefix(8)) | to=\(peer.prefix(12)) | result=skip | why=\(decision.rawValue)")
                continue
            }

            do {
                // AUCUNE écriture d'état d'envoi ni de téléversement: un
                // renvoi n'est pas un nouvel envoi, et faire clignoter la
                // bulle d'un message vieux d'une semaine serait un mensonge
                // sur ce qui vient de se passer. Un test de source garde cette
                // absence — d'où l'énumération dans le test et pas ici.
                // __RESEND_FIDELITY_2026_08_28__ On reemet ce que l'expediteur
                // AFFICHE aujourd'hui, pas ce qu'il avait tape.
                //
                // Une edition n'ecrase pas `plaintextContent`: elle ecrit
                // `editedContent` a cote. Reemettre le premier renverrait donc
                // definitivement la version PRE-EDITION — « rendez-vous a 20h »
                // alors que l'expediteur a corrige en 21h et voit sa correction
                // de son cote. L'action d'edition, elle, est partie sur la
                // session cassee et s'est perdue avec le reste.
                let body = m.editedContent ?? m.plaintextContent ?? ""
                // __RESEND_ENVELOPE_2026_08_28__ Identifiant de relais NEUF — le
                // msgId est en clair et conservé 30 j; le réutiliser laissait le
                // relais apparier les deux enveloppes, donc dater la panne et
                // compter les messages concernés. L'identifiant d'origine part
                // DANS le chiffré, pour que le destinataire déduplique.
                guard let wrapped = RatchetHealSignal.Resent.encode(originalId: id, body: body) else {
                    CallFileLogger.shared.log("DIAG_V2_RESEND | id=\(id.prefix(8)) | to=\(peer.prefix(12)) | result=skip | why=encode")
                    continue
                }
                _ = try await router.sendText(peer: peer, wrapped,
                                              msgId: UUID().uuidString,
                                              originalTimestamp: m.timestamp)
                V2SyncState.noteResent(id)
                V2SyncState.noteResendSpent(peerCanonical, now: now)
                CallFileLogger.shared.log("DIAG_V2_RESEND | id=\(id.prefix(8)) | to=\(peer.prefix(12)) | result=sent")
            } catch {
                CallFileLogger.shared.log("DIAG_V2_RESEND | id=\(id.prefix(8)) | to=\(peer.prefix(12)) | result=error")
            }
        }
    }

    // MARK: - Blobs média: reprise hors du ratchet

    /// Télécharge et livre le blob d'une enveloppe déjà déchiffrée.
    /// - Returns: `false` si le téléchargement a échoué (à reprendre).
    /// __PER_DEVICE_MAILBOX_2026_09_23__ A `sent-copy` from another device of this account:
    /// the message it sent, shown here as OUTGOING (status sent). Deduped by id against the
    /// same row arriving later through devsync. No notification, no receipt.
    private func v2DeliverSentCopy(_ copy: V2MessageRouter.SentCopy, envelopeTs: Date, me: String,
                                   client: V2Client) async {
        let ts = copy.sentAt ?? envelopeTs
        var media: (bytes: Data, key: V2FileKeyMessage)? = nil
        if let key = try? JSONDecoder().decode(V2FileKeyMessage.self, from: Data(copy.message.utf8)),
           key.kind == V2FileKeyMessage.kindValue {
            guard key.groupMessage == nil, RevealWirePlan(key: key) == nil || (key.revealLayerIndex ?? 0) == 0 else { return }
            do {
                let url = try await client.downloadAndDecryptFileToDisk(blobId: key.blobId, chunkCount: key.chunkCount,
                                                                        fileKeyB64: key.fileKey, fileNonceB64: key.fileNonce,
                                                                        manifestB64: key.manifest)
                let bytes = try Data(contentsOf: url)
                try? FileManager.default.removeItem(at: url)
                media = (bytes, key)
            } catch {
                CallFileLogger.shared.log("DIAG_V2_SENT_COPY | id=\(copy.msgId.prefix(8)) | media=unavailable")
                return
            }
        } else if OSHIControlPayload.isSilent(copy.message) {
            return
        }
        await MainActor.run {
            guard !self.messages.contains(where: { $0.id.caseInsensitiveCompare(copy.msgId) == .orderedSame }) else { return }
            var msg = SecureMessage(
                id: copy.msgId,
                senderAddress: me,
                recipientAddress: copy.peer,
                encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: me, timestamp: ts),
                timestamp: ts,
                isRead: true,
                deliveryStatus: .sent,
                senderPublicKey: me,
                recipientPublicKey: copy.peer,
                plaintextContent: media == nil ? copy.message : "",
                // nil here: `addMessage` posts `MessageDeliveryConfirmed` for `.ipfs` rows,
                // which would flip OUR outgoing copy to "delivered" on arrival. The
                // cloud badge is set right after insertion.
                deliveryMethod: nil
            )
            if let media {
                msg.mediaAttachment = media.bytes
                msg.originalMediaData = media.bytes
                msg.mediaType = media.key.mediaType.flatMap { MediaManager.MediaType(rawValue: $0) }
                    ?? MessageManager.v2MediaType(forMime: media.key.mime)
                msg.mediaFileName = media.key.filename
                msg.isViewOnce = media.key.isViewOnce ?? false
            }
            self.addMessage(msg)
            if let i = self.messages.firstIndex(where: { $0.id == copy.msgId }), self.messages[i].deliveryMethod == nil {
                self.messages[i].deliveryMethod = .ipfs
                self.saveMessages()
            }
            CallFileLogger.shared.log("DIAG_V2_SENT_COPY | id=\(copy.msgId.prefix(8)) | media=\(media != nil)")
        }
    }

    private func v2FetchAndDeliverBlob(key: V2FileKeyMessage,
                                       from: String,
                                       msgId: String,
                                       groupId: String?,
                                       timestamp: Date,
                                       me: String,
                                       client: V2Client,
                                       deleteAfterDelivery: Bool = true) async -> Bool {
        do {
            // 🔧 RESUMABLE/BACKGROUND DOWNLOAD: fetch the blob's chunks to disk
            // via a background URLSession and stream-decrypt to a temp file
            // (bounded memory, resumes from the first missing chunk on
            // interruption) instead of pulling the whole file into RAM in the
            // foreground. We still hand v2DeliverFile a Data for now
            // (memory-mapped, so no eager full read) to keep delivery unchanged.
            let fileURL = try await client.downloadAndDecryptFileToDisk(
                blobId: key.blobId,
                chunkCount: key.chunkCount,
                fileKeyB64: key.fileKey,
                fileNonceB64: key.fileNonce,
                manifestB64: key.manifest)
            let bytes = try Data(contentsOf: fileURL, options: .mappedIfSafe)
            try? FileManager.default.removeItem(at: fileURL)
            if groupId != nil {
                // GROUP media (blob path). The stripped GroupMessage rides inside
                // the ratchet payload; re-attach the decrypted bytes and hand it to
                // the SAME funnel the group text path uses. A group file envelope
                // with no embedded GroupMessage cannot be placed in any
                // conversation — log and drop rather than mis-file it into the 1:1
                // thread with the sender.
                // __MAIN_THREAD_ONLY_2026_09_06__ delivery mutates @Published state.
                await MainActor.run {
                    self.v2DeliverGroupFile(bytes: bytes, groupMessageB64: key.groupMessage, from: from, groupId: groupId,
                                            videoNote: key.videoNoteMetadata)
                }
            } else {
                await MainActor.run {
                    self.v2DeliverFile(bytes: bytes, filename: key.filename, mime: key.mime,
                                       mediaTypeRaw: key.mediaType,
                                       isViewOnce: key.isViewOnce ?? false,
                                       from: from, msgId: msgId,
                                       timestamp: timestamp, me: me,
                                       reveal: RevealWirePlan(key: key),
                                       videoNote: key.videoNoteMetadata)
                }
            }
            // Delete-after-delivery: bytes are decrypted + persisted, so reclaim
            // the server disk now instead of waiting for TTL.
            // __PER_DEVICE_MAILBOX_2026_09_23__ …except for a per-device copy: every device
            // of this account (and the sender's own devices, via sent-copies) fetches the
            // SAME blob, so the first one to finish must not delete it under the others.
            // The blob store expires it on its own (24 h after collection).
            if deleteAfterDelivery { try? await client.deleteBlob(blobId: key.blobId) }
            return true
        } catch {
            OshiLog.messaging.info("⚠️ [V2] file download failed for \(msgId.prefix(8))…: \(error)")
            return false
        }
    }

    /// Rejoue les blobs en attente, EN TÊTE DE TIC et hors du ratchet.
    ///
    /// __V2_BLOB_RETRY_IS_NOT_A_RATCHET_BREAK_2026_08_28__ L'ancienne reprise
    /// passait par le ratchet (`seen` était rouvert) sur une enveloppe DÉJÀ
    /// déchiffrée: elle ne pouvait qu'échouer en AEAD, et ressemblait alors
    /// trait pour trait à une session morte. Avec un budget de 8 il fallait 8
    /// téléchargements ratés pour armer la bombe; avec une réaction immédiate,
    /// UN blob raté hors ligne effacerait une session parfaitement saine.
    private func v2ReplayPendingBlobs(client: V2Client, me: String) async {
        let pending = V2SyncState.blobPending
        guard !pending.isEmpty else { return }
        for (msgId, entry) in pending {
            let ok = await v2FetchAndDeliverBlob(
                key: entry.key, from: entry.from, msgId: msgId, groupId: entry.groupId,
                timestamp: Date(timeIntervalSince1970: entry.ts), me: me, client: client,
                deleteAfterDelivery: !V2DeviceMailbox.isActive)
            if ok {
                V2SyncState.clearPendingBlob(msgId)
                CallFileLogger.shared.log("DIAG_V2_BLOB_RESUME | id=\(msgId.prefix(8)) | result=delivered")
            } else if !V2SyncState.notePendingBlobAttempt(msgId) {
                // Reprises épuisées: on abandonne le blob, et l'ack cesse d'être
                // retenu — sinon un blob mort gèlerait la boîte à vie.
                CallFileLogger.shared.log("DIAG_V2_BLOB_RESUME | id=\(msgId.prefix(8)) | result=giveup | attempts=\(V2SyncState.blobMaxAttempts)")
            }
        }
    }

    // MARK: - Inbound mapping into the existing funnel

    /// Persist an inbound v2 text/system payload through `receiveMessage`, the
    /// same funnel mesh + IPFS use — receipts, reactions, edits, call signals,
    /// profile updates etc. are all recognised there by their prefixes.
    /// - Parameter silent: livre SANS programmer de notification. Utilisé par le
    ///   RENVOI: le destinataire a déjà été notifié quand le message est arrivé
    ///   la première fois — c'est même cette notification-là, suivie d'aucun
    ///   message, qui a fait remonter tout ce problème au départ.
    private func v2Deliver(text: String, from: String, msgId: String, timestamp: Date, me: String,
                           silent: Bool = false) {
        var msg = SecureMessage(
            id: msgId,
            senderAddress: from,
            recipientAddress: me,
            encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: from, timestamp: timestamp),
            timestamp: timestamp,
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: from,
            recipientPublicKey: me,
            plaintextContent: text,
            deliveryMethod: .ipfs
        )

        // Safety net: a v2 peer that embedded media as the legacy base64 JSON
        // (instead of the blob store) still renders as a proper media bubble.
        if text.hasPrefix("{"), let parsed = MessageManager.v2ParseEmbeddedMediaJSON(text) {
            msg.mediaAttachment = parsed.data
            msg.originalMediaData = parsed.data
            msg.mediaType = parsed.type
            msg.mediaFileName = parsed.fileName
            msg.isViewOnce = parsed.isViewOnce
            msg.plaintextContent = ""
            // __VIDEO_NOTE_2026_09_24__ spec §2.C, same keys in the embedded form.
            if parsed.type == .video,
               let obj = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] {
                msg.applyVideoNote(VideoNoteMetadata.parse(obj, isVideo: true))
            }
        }

        receiveMessage(msg, suppressNotification: silent)
        v2SendDeliveryReceiptIfNeeded(for: msg)
    }

    /// Une couche k>0: elle remplace les octets du message DÉJÀ dans le fil.
    ///
    /// Trois choses doivent bouger ensemble, et en oublier une laisse l'écran sur
    /// l'ancienne image alors que tout le reste a avancé:
    ///   1. `messages[…].mediaAttachment` — la source de vérité persistée;
    ///   2. le magasin de révélation — ce que la bulle interroge pour savoir où
    ///      elle en est;
    ///   3. le CACHE média — sinon la bulle continue de servir l'ancienne couche
    ///      depuis la mémoire, et rien de ce qui précède ne se voit.
    private func v2ApplyRevealLayer(_ reveal: RevealWirePlan, bytes: Data, from: String) {
        // Le magasin de révélation ET le cache média sont isolés sur le MainActor:
        // ils alimentent directement des vues. Le chemin de réception v2, lui, est
        // un contexte détaché — d'où le saut explicite. `messages` et
        // `saveMessages()` traversent avec, ce qui évite au passage de muter la
        // liste depuis deux fils.
        DispatchQueue.main.async { [weak self] in
            self?.v2ApplyRevealLayerOnMain(reveal, bytes: bytes, from: from)
        }
    }

    @MainActor
    private func v2ApplyRevealLayerOnMain(_ reveal: RevealWirePlan, bytes: Data, from: String) {
        guard let index = messages.firstIndex(where: { $0.id == reveal.revealId }) else {
            // La couche 0 n'est jamais arrivée (installée après coup, ou perdue).
            // Rien à réécrire: on jette plutôt que de fabriquer une bulle
            // orpheline qui montrerait une image floue sans fin.
            OshiLog.messaging.info("🖼️ Couche \(reveal.layerIndex) sans message porteur — ignorée")
            return
        }
        messages[index].mediaAttachment = bytes
        messages[index].originalMediaData = bytes
        TimedRevealStore.shared.apply(messageId: reveal.revealId,
                                      layerIndex: reveal.layerIndex,
                                      bytes: bytes)
        MediaAttachmentCache.shared.remove(reveal.revealId)
        saveMessages()
        objectWillChange.send()
        OshiLog.messaging.info("🖼️ Couche \(reveal.layerIndex)/\(reveal.layerCount - 1) appliquée")
    }

    /// Persist an inbound v2 GROUP payload. `text` is the base64 GroupMessage
    /// the sender fanned out (identical bytes to the legacy Pinata group path),
    /// so it flows into the SAME `receiveGroupMessage` funnel — which dedups on
    /// the inner GroupMessage.id (stable across all per-recipient envelopes),
    /// gates stale identities, auto-adds unknown senders, and posts the group
    /// UI notification. Group MEDIA does NOT come through here — it arrives as a
    /// `.file` envelope and is handled by `v2DeliverGroupFile`.
    private func v2DeliverGroup(text: String, from: String, groupId: String) {
        // Decode + deliver on the MainActor (MessageGroupManager is MainActor-
        // isolated). Capturing only Strings keeps this Sendable-clean.
        Task { @MainActor in
            // __GROUP_E2E_V2_2026_09_23__ §3: the envelope `from`/`groupId` are authoritative.
            guard let groupMessage = GroupWireV2.decodeContent(text) else {
                OshiLog.messaging.info("⚠️ [V2] group payload decode failed from \(from.prefix(8))… — dropped")
                return
            }
            MessageGroupManager.shared.receiveV2GroupMessage(groupMessage, from: from, envelopeGroupId: groupId)
        }
    }

    /// Persist an inbound v2 GROUP MEDIA message whose bytes came from the blob
    /// store. `groupMessageB64` is the sender's `GroupMessage` with its inline
    /// `encryptedContent` stripped — decode it, re-attach `bytes` as `mediaData`
    /// and hand it to `receiveGroupMessage`, which then takes the `directMedia`
    /// branch (`GroupMessaging.swift:2928`) and writes the file to disk under the
    /// message id. Everything else — id dedup, stale-identity gate, auto-add,
    /// notification — is byte-for-byte the group TEXT path.
    private func v2DeliverGroupFile(bytes: Data, groupMessageB64: String?, from: String, groupId: String? = nil,
                                    videoNote: VideoNoteMetadata? = nil) {
        guard let groupMessageB64 else {
            OshiLog.messaging.info("⚠️ [V2] group file envelope from \(from.prefix(8))… carried no GroupMessage — dropped")
            return
        }
        Task { @MainActor in
            guard let data = Data(base64Encoded: groupMessageB64),
                  var groupMessage = try? JSONDecoder().decode(GroupMessage.self, from: data) else {
                OshiLog.messaging.info("⚠️ [V2] group file payload decode failed from \(from.prefix(8))…")
                return
            }
            groupMessage.mediaData = bytes
            // __VIDEO_NOTE_2026_09_24__ spec §1.5: a note if EITHER location says so;
            // the file-key duration wins over the embedded one.
            if let note = videoNote ?? groupMessage.videoNoteMetadata, groupMessage.mediaType == .video {
                groupMessage.applyVideoNote(VideoNoteMetadata(durationMs: note.durationMs ?? groupMessage.durationMs))
            }
            // __GROUP_E2E_V2_2026_09_23__ §4 then §3 from step 3.
            if let groupId {
                MessageGroupManager.shared.receiveV2GroupMessage(groupMessage, from: from, envelopeGroupId: groupId)
            } else {
                MessageGroupManager.shared.receiveGroupMessage(groupMessage)
            }
        }
    }

    /// Persist an inbound v2 file (blob already downloaded + decrypted).
    /// __REVEAL_RECEIVE_2026_08_22__ La planification telle qu'elle arrive du fil,
    /// une fois validée. `nil` dès qu'un des quatre champs manque ou est absurde:
    /// une planification à moitié lue est pire que pas de planification du tout —
    /// un `layerCount` de 1 donne `finalIndex = 0`, donc une division par zéro
    /// puis une date NaN dans le compte à rebours.
    struct RevealWirePlan {
        let revealId: String
        let layerIndex: Int
        let layerCount: Int
        let totalSeconds: TimeInterval

        init?(key: V2FileKeyMessage) {
            guard let id = key.revealId, !id.isEmpty,
                  let index = key.revealLayerIndex, index >= 0,
                  let count = key.revealLayerCount, count >= 2, count <= 64,
                  let ms = key.revealTotalMs, ms > 0, ms <= 7 * 24 * 3600 * 1000,
                  index < count
            else { return nil }
            self.revealId = id
            self.layerIndex = index
            self.layerCount = count
            self.totalSeconds = TimeInterval(ms) / 1000
        }
    }

    /// __REVEAL_RECEIVE_2026_08_22__ Deuxième maillon côté récepteur.
    ///
    /// La couche 0 crée la bulle, comme n'importe quel média. Les couches
    /// suivantes ne doivent SURTOUT PAS en créer une: elles réécrivent la pièce
    /// jointe du message déjà affiché. Sans ça, une révélation en cinq couches
    /// produirait cinq photos dans le fil.
    ///
    /// 🔴 LA DATE DE DÉPART EST CELLE DE L'ARRIVÉE, JAMAIS CELLE DE L'EXPÉDITEUR.
    /// Le fil ne transporte volontairement AUCUN horodatage de départ: s'il en
    /// portait un, il suffirait d'envoyer une date passée pour que la photo
    /// arrive nette immédiatement. La durée voyage, l'origine du temps est locale.
    private func v2DeliverFile(bytes: Data, filename: String, mime: String,
                               mediaTypeRaw: String?, isViewOnce: Bool,
                               from: String, msgId: String, timestamp: Date, me: String,
                               reveal: RevealWirePlan? = nil,
                               videoNote: VideoNoteMetadata? = nil) {
        if let reveal {
            if reveal.layerIndex > 0 {
                v2ApplyRevealLayer(reveal, bytes: bytes, from: from)
                return
            }
            // Couche 0: on arme la planification AVANT de créer la bulle, sinon
            // la vue se compose une fois sans révélation et affiche la couche 0
            // comme une photo ordinaire, définitivement.
            if let schedule = TimedRevealSchedule(layerCount: reveal.layerCount,
                                                  totalDuration: reveal.totalSeconds,
                                                  startedAt: Date()) {
                DispatchQueue.main.async {
                    _ = TimedRevealStore.shared.begin(messageId: reveal.revealId,
                                                      schedule: schedule,
                                                      layer0: bytes)
                }
                OshiLog.messaging.info("🖼️ Révélation armée à l'arrivée: \(reveal.layerCount) couches sur \(Int(reveal.totalSeconds)) s")
            }
        }

        var msg = SecureMessage(
            id: msgId,
            senderAddress: from,
            recipientAddress: me,
            encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: from, timestamp: timestamp),
            timestamp: timestamp,
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: from,
            recipientPublicKey: me,
            plaintextContent: "",
            mediaAttachment: bytes,
            deliveryMethod: .ipfs,
            originalMediaData: bytes
        )
        msg.mediaType = mediaTypeRaw.flatMap { MediaManager.MediaType(rawValue: $0) }
            ?? MessageManager.v2MediaType(forMime: mime)
        msg.mediaFileName = filename
        msg.isViewOnce = isViewOnce
        if msg.mediaType == .video { msg.applyVideoNote(videoNote) }  // __VIDEO_NOTE_2026_09_24__

        receiveMessage(msg)
        v2SendDeliveryReceiptIfNeeded(for: msg)
    }

    /// Ack a persisted inbound v2 content message back to its sender with the
    /// existing receipt payload, over the normal send path (v2 first). Reuses
    /// the mesh receipt guard so control/system traffic never ping-pongs.
    private func v2SendDeliveryReceiptIfNeeded(for message: SecureMessage) {
        let me = MessageManager.sharedIdentityManager?.publicKey ?? ""
        guard MessageManager.shouldAckInboundMeshMessage(
            content: message.plaintextContent,
            senderPublicKey: message.senderPublicKey,
            myPublicKey: me) else { return }
        guard let identityManager = MessageManager.sharedIdentityManager,
              let meshManager = MessageManager.sharedMeshManager else { return }

        self.sendMessage(
            content: "📬DELIVERY_RECEIPT📬\(message.id)",
            recipientAddress: message.senderPublicKey,
            identityManager: identityManager,
            meshManager: meshManager,
            skipLocalMessage: true
        )
    }

    /// Protocol payloads the peer must process but never be alerted about.
    ///
    /// (Le « mirrors the `silentControlPrefixes` list in `uploadToIPFS` » d'avant
    /// décrivait le problème en croyant décrire la solution: les deux listes ne
    /// se reflétaient pas, elles DIVERGEAIENT — 8 entrées chacune contre 10
    /// `.silent` au catalogue. Le signal d'appel reste l'exception qui sonne,
    /// désormais déclarée une fois dans `silentButStillRings`.)
    ///
    /// __PUSH_LISTS_DRIFTED_2026_09_02__ La liste a déménagé dans
    /// `OSHIControlPayload.suppressesPush`, où elle est DÉRIVÉE du catalogue au
    /// lieu d'en être une copie de plus. Le nom reste, redirigé, parce qu'un
    /// tableau vide ici ressemblerait à « rien n'est silencieux » à la
    /// prochaine lecture.
    static var v2SilentControlPrefixes: Set<String> { OSHIControlPayload.pushSilentPrefixes }

    // MARK: - Media type / mime helpers

    static func v2Mime(for type: MediaManager.MediaType?) -> String {
        switch type {
        case .image: return "image/jpeg"
        // __GIF_2026_09_12__ `image/gif` sur le fil. C'est ce type MIME qui dit
        // au destinataire — app ou navigateur — qu'il faut ANIMER au lieu de
        // décoder une image fixe; l'annoncer en jpeg suffirait à figer le GIF
        // à l'arrivée sans qu'aucune autre couche ne s'en aperçoive.
        case .gif: return "image/gif"
        case .video: return "video/mp4"
        case .audio: return "audio/m4a"
        case .contact: return "text/vcard"
        case .document, .none: return "application/octet-stream"
        }
    }

    static func v2FileName(for message: SecureMessage) -> String {
        if let name = message.mediaFileName, !name.isEmpty { return name }
        switch message.mediaType {
        case .image: return "photo.jpg"
        case .gif: return "image.gif"
        case .video: return "video.mp4"
        case .audio: return "audio.m4a"
        case .contact: return "contact.vcf"
        case .document, .none: return "file.dat"
        }
    }

    static func v2MediaType(forMime mime: String) -> MediaManager.MediaType {
        if mime.hasPrefix("image/") { return .image }
        if mime.hasPrefix("video/") { return .video }
        if mime.hasPrefix("audio/") { return .audio }
        if mime.contains("vcard") { return .contact }
        return .document
    }

    /// Minimal decoder for the legacy `{"type":"media", ...}` embed, used only
    /// as an inbound safety net (v2 senders use the blob store instead).
    static func v2ParseEmbeddedMediaJSON(_ text: String)
        -> (data: Data, type: MediaManager.MediaType?, fileName: String?, isViewOnce: Bool)? {
        guard let jsonData = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              obj["type"] as? String == "media",
              let contentB64 = obj["content"] as? String,
              let data = Data(base64Encoded: contentB64) else { return nil }
        let type = (obj["mediaType"] as? String).flatMap { MediaManager.MediaType(rawValue: $0) }
        return (data, type, obj["fileName"] as? String, obj["isViewOnce"] as? Bool ?? false)
    }
}

// MARK: - DEBUG self-test: per-device mailbox + websession v2 (__PER_DEVICE_MAILBOX_2026_09_23__)
#if DEBUG
/// `-OSHISelfTestMultiDevice` (the test target is not folder-synced). Writes
/// `Documents/selftest_multidevice.txt`, one PASS/FAIL/SKIP line per check.
///   1. golden vectors: `device_vectors.json` (mailbox) and `websession_e2e/test/vectors.json`;
///   2. with `OSHI_V2_BASE_URL` = a LOCAL `per_device_mailbox/v2` server (flag on): three
///      simulated identities (Alice ×3 devices, Bob updated, Carol old sender) run the
///      spec §6 test plan against the real server through the real router and client;
///   3. with `OSHI_SELFTEST_APP_MAILBOX=1` as well: the APP's own pull loop in device mode
///      (registration, live delivery, sent-copy insertion, 410 alert);
///   4. with `OSHI_SELFTEST_WS_QR` (+ `OSHI_WEB_BASE_URL`/`OSHI_WEB_WS_URL`): the app pairs
///      with a v2 browser run by `ServerPatches/websession_e2e/test` tooling and serves it.
enum MultiDeviceSelfTest {
    static func runIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-OSHISelfTestMultiDevice") else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
            Task.detached { await run() }
        }
    }

    final class Log: @unchecked Sendable {
        private let lock = NSLock()
        private(set) var lines: [String] = []
        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            add("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " — \(detail)")")
        }
        func add(_ l: String) {
            lock.lock(); lines.append(l); let all = lines.joined(separator: "\n") + "\n"; lock.unlock()
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? all.write(to: docs.appendingPathComponent("selftest_multidevice.txt"), atomically: true, encoding: .utf8)
            OshiLog.storage.info("SELFTEST-MD \(l)")
        }
    }

    static func run() async {
        let log = Log()
        log.add("INFO start \(Date())")
        deviceVectors(log)
        WebE2ESelfTest.vectors(log)
        let env = ProcessInfo.processInfo.environment
        if let base = env["OSHI_V2_BASE_URL"], let url = URL(string: base) {
            await liveMailbox(log, base: url)
            if env["OSHI_SELFTEST_APP_MAILBOX"] == "1" { await appMailbox(log, base: url) }
        } else {
            log.add("SKIP mailbox.live (no OSHI_V2_BASE_URL)")
        }
        if let qr = env["OSHI_SELFTEST_WS_QR"] {
            await WebE2ESelfTest.live(log, qr: qr)
        }
        log.add("INFO done")
    }

    static func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    static func unhex(_ s: String) -> Data {
        var d = Data(); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return d
    }

    // MARK: 1. mailbox vectors (ServerPatches/per_device_mailbox/device_vectors.json)

    static func deviceVectors(_ log: Log) {
        let identity = "ERERERERERERERERERERERERERERERERERERERERERE="
        let dk = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        let ts = "1790000000000"
        let id = V2Device.deviceId(dk: Data(base64Encoded: dk)!)
        log.check("mailbox.vector.deviceId", id == "8a62844af4e6e7850a98c731308278e1", id)
        guard let dev = try? Curve25519.Signing.PrivateKey(rawRepresentation: unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")),
              let appr = try? Curve25519.Signing.PrivateKey(rawRepresentation: unhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")) else {
            log.check("mailbox.vector.seeds", false); return
        }
        let dsk = dev.publicKey.rawRepresentation.base64EncodedString()
        log.check("mailbox.vector.dsk", dsk == "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=")
        log.check("mailbox.vector.approverDsk", appr.publicKey.rawRepresentation.base64EncodedString() == "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw=")
        // CryptoKit's Ed25519 signatures are RANDOMISED (hedged), so "byte for byte" is
        // checked as: identical canonical strings, the vector signature verifies under our
        // key, and our own signature verifies too. (RFC 8032 deterministic signers — the
        // server's node:crypto, BouncyCastle — reproduce the vector bytes exactly.)
        func sig(_ key: Curve25519.Signing.PrivateKey, _ str: String, _ vector: String, _ name: String) {
            let pub = key.publicKey
            let vOK = Data(base64Encoded: vector).map { pub.isValidSignature($0, for: Data(str.utf8)) } ?? false
            let ours = (try? key.signature(for: Data(str.utf8))).map { pub.isValidSignature($0, for: Data(str.utf8)) } ?? false
            log.check("mailbox.vector.\(name).signature", vOK && ours)
        }
        let reg = V2Device.registerProofString(identity: V2Device.normalizeIdentity(identity), deviceId: id, dk: dk, dsk: dsk, ts: ts)
        log.check("mailbox.vector.registerProof.string", reg == "OSHI-DEVICE-REGISTER/1\nERERERERERERERERERERERERERERERERERERERERERE=\n8a62844af4e6e7850a98c731308278e1\nAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\n11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n1790000000000")
        sig(dev, reg, "xgbz9LBRI7OTGiujcAlZ/MBCdJeGLJCJct6tB/QIpqSA47aWUJgI493AaNW/RRrmeq8aTDAQZZIzEipZUK8mAw==", "registerProof")
        let apv = V2Device.approvalString(identity: identity, deviceId: id, dsk: dsk, ts: ts)
        log.check("mailbox.vector.approval.string", apv == "OSHI-DEVICE-APPROVE/1\nERERERERERERERERERERERERERERERERERERERERERE=\n8a62844af4e6e7850a98c731308278e1\n11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n1790000000000")
        sig(appr, apv, "hMuiDohMyNnTCCGGbv+9zRl+u1+shr5MKD7wQj6D09gQ7v5nTo/5WOch7doqK6Ezw4xudvd+Sh9y2WZjz6mmAQ==", "approval")
        let body = Data("{\"upToSeq\":42}".utf8)
        let bodyHash = hex(Data(SHA256.hash(data: body)))
        log.check("mailbox.vector.deviceRequest.bodySha256", bodyHash == "6848d1f78f9c2479db6416480bbd2a6dbb34bc6cdd1981b721e06a4b949da256")
        let path = "/v2/messages/" + (identity.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? identity) + "/ack"
        let rq = V2Device.requestString(deviceId: id, method: "POST", path: path, bodySHA256Hex: bodyHash, ts: ts)
        log.check("mailbox.vector.deviceRequest.string", rq == "OSHI-DEVICE/1\n8a62844af4e6e7850a98c731308278e1\nPOST\n/v2/messages/ERERERERERERERERERERERERERERERERERERERERERE%3D/ack\n6848d1f78f9c2479db6416480bbd2a6dbb34bc6cdd1981b721e06a4b949da256\n1790000000000")
        sig(dev, rq, "4d+sTmeDVLZKNQmNdx2bspBhdQ7ENy64Y+VdlLq3vHSZYK10dBbVHDwzxlKzKP6C65AkNLjWOek/tz/Km0DqCw==", "deviceRequest")
        // Spelling: a base64url identity is normalised before it is signed.
        log.check("mailbox.normalizeIdentity", V2Device.normalizeIdentity("ERERERERERERERERERERERERERERERERERERERERERE") == identity)
        // sent-copy wire shape round trip.
        let sc = V2MessageRouter.sentCopyPayload(peer: "QUJD", message: "hi", msgId: "M-1", sentAtMs: 1790000000000)
        let parsed = sc.flatMap { V2MessageRouter.parseSentCopy(String(decoding: $0, as: UTF8.self)) }
        log.check("mailbox.sentCopy.roundTrip", parsed?.peer == "QUJD" && parsed?.message == "hi" && parsed?.msgId == "M-1"
                  && parsed?.sentAt == Date(timeIntervalSince1970: 1790000000))
        let frac = V2MessageRouter.parseSentCopy("{\"kind\":\"sent-copy\",\"v\":1,\"conversation\":{\"type\":\"1to1\",\"peer\":\"QUJD\"},\"message\":\"x\",\"msgId\":\"M\",\"sentAt\":\"2026-09-23T10:00:00.250Z\"}")
        log.check("mailbox.sentCopy.acceptsFractionalSentAt", frac?.sentAt != nil)
        log.check("mailbox.slot", V2MessageRouter.slot("P", device: nil) == "P" && V2MessageRouter.slot("P", device: "d") == "P#d")
    }

    // MARK: 2. live, router level, against a LOCAL server

    struct TestCrypto: V2CryptoProviding {
        let signing: Curve25519.Signing.PrivateKey
        let identity: String
        var userKey: String { identity }
        func signCanonical(_ message: Data) throws -> (signature: String, signingPublicKey: String) {
            (try signing.signature(for: message).base64EncodedString(), signing.publicKey.rawRepresentation.base64EncodedString())
        }
    }

    struct Person {
        let name: String
        let ik: Curve25519.KeyAgreement.PrivateKey
        let sk: Curve25519.Signing.PrivateKey
        var userKey: String { ik.publicKey.rawRepresentation.base64EncodedString() }
        var crypto: TestCrypto { TestCrypto(signing: sk, identity: userKey) }
        var ikItem: String { "selftest.md.\(name).ik" }
        var skItem: String { "selftest.md.\(name).sk" }
        init(_ name: String) {
            self.name = name
            ik = Curve25519.KeyAgreement.PrivateKey(); sk = Curve25519.Signing.PrivateKey()
            try? KeychainHelper.save(key: "selftest.md.\(name).ik", data: ik.rawRepresentation)
            try? KeychainHelper.save(key: "selftest.md.\(name).sk", data: sk.rawRepresentation)
        }
        func client(_ base: URL) -> V2Client {
            V2Client(config: .init(baseURL: base, requestTimeout: 15, maxRetryAttempts: 1), crypto: crypto, session: .shared)
        }
        func router(_ base: URL, device tag: String) -> V2MessageRouter {
            let run = "selftest.md.\(tag).\(UUID().uuidString.prefix(6))"
            return V2MessageRouter(client: client(base),
                                   store: V2SessionStore(sessionPrefix: run + ".s.", ownMaterialKey: run + ".own"),
                                   deviceStore: V2SessionStore(ownMaterialKey: run + ".devown"),
                                   crypto: crypto, signingKeychainKey: skItem, identityKeychainKey: ikItem,
                                   oneTimePreKeyCount: 5)
        }
    }

    static func texts(_ items: [V2IncomingMessage]) -> [String] {
        items.compactMap { if case .text(_, _, _, let t, _) = $0 { return t } else { return nil } }
    }
    static func failures(_ items: [V2IncomingMessage]) -> Int {
        items.filter { if case .failed = $0 { return true } else { return false } }.count
    }

    static func liveMailbox(_ log: Log, base: URL) async {
        let alice = Person("alice"), bob = Person("bob"), carol = Person("carol")
        let a1 = alice.router(base, device: "A1"), a2 = alice.router(base, device: "A2"), a3 = alice.router(base, device: "A3")
        let b = bob.router(base, device: "B"), c = carol.router(base, device: "C")
        let ac = alice.client(base), bc = bob.client(base)
        let k1 = V2Device.Keys(dk: .init(), dsk: .init()), k2 = V2Device.Keys(dk: .init(), dsk: .init())
        let k3 = V2Device.Keys(dk: .init(), dsk: .init()), kb = V2Device.Keys(dk: .init(), dsk: .init())
        do {
            // Identity binding (publish) — the device routes are never graced.
            try await a1.register(); try await b.register(); try await c.register()
            log.check("live.accountBundles.published", true)
            // Flag visible?
            let cfg = (try? await ac.fetchClientConfig()).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            log.check("live.config.device_mailbox_enabled", (cfg?["device_mailbox_enabled"] as? Bool) == true)

            // §3.2 registration: first device needs no approval.
            let empty = try await ac.listDevices()
            log.check("live.register.firstDevice.listEmpty", empty.isEmpty)
            try await ac.registerDevice(V2Device.registration(keys: k1, identity: alice.userKey, approval: nil))
            try await a1.publishDeviceBundle(signer: k1.signer)
            log.check("live.register.firstDevice", (try await ac.listDevices()).map(\.deviceId) == [k1.deviceId])
            let own1 = await a1.accountBundleOwnership()
            log.check("live.holder.firstDevice.mine", own1 == .mine, "\(own1)")

            // Second device: approval required, then approved by A1.
            var gotApprovalRequired = false
            do { try await ac.registerDevice(V2Device.registration(keys: k2, identity: alice.userKey, approval: nil)) }
            catch V2.ClientError.http(let st, let reason) { gotApprovalRequired = st == 403 && (reason ?? "").contains("approval-required") }
            log.check("live.register.secondDevice.approvalRequired", gotApprovalRequired)
            let approval = try V2Device.approve(newDeviceId: k2.deviceId, newDsk: k2.dskB64, identity: alice.userKey, approver: k1)
            try await ac.registerDevice(V2Device.registration(keys: k2, identity: alice.userKey, approval: approval))
            try await a2.publishDeviceBundle(signer: k2.signer)
            let listed = Set(try await ac.listDevices().map(\.deviceId))
            log.check("live.register.secondDevice.approved", listed == [k1.deviceId, k2.deviceId])
            let own2 = await a2.accountBundleOwnership()
            log.check("live.holder.secondDevice.notMine", own2 == .other, "\(own2)")
            // A forged approval (signed by a key that is not registered) is refused.
            var badApproval = false
            let forged = try V2Device.approve(newDeviceId: k3.deviceId, newDsk: k3.dskB64, identity: alice.userKey, approver: k3)
            do { try await ac.registerDevice(V2Device.registration(keys: k3, identity: alice.userKey, approval: forged)) }
            catch V2.ClientError.http(let st, let reason) { badApproval = st == 403 && (reason ?? "").contains("bad-approval") }
            log.check("live.register.forgedApproval.refused", badApproval)

            try await bc.registerDevice(V2Device.registration(keys: kb, identity: bob.userKey, approval: nil))
            try await b.publishDeviceBundle(signer: kb.signer)
            await a1.setDeviceContext(.init(me: alice.userKey, deviceId: k1.deviceId, signer: k1.signer, holdsAccountBundle: true))
            await a2.setDeviceContext(.init(me: alice.userKey, deviceId: k2.deviceId, signer: k2.signer, holdsAccountBundle: false))
            await b.setDeviceContext(.init(me: bob.userKey, deviceId: kb.deviceId, signer: kb.signer, holdsAccountBundle: true))

            // §6.2(b) updated sender: one copy per device, both decrypt live.
            let peerList = try await bc.peerDevices(userKey: alice.userKey)
            log.check("live.peerDevices", peerList.enabled && Set(peerList.devices.map(\.deviceId)) == [k1.deviceId, k2.deviceId])
            _ = try await b.sendText(peer: alice.userKey, "hello-devices")
            let p1 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: 0, signer: k1.signer)
            let p2 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: 0, signer: k2.signer)
            log.check("live.fanout.copiesAddressed", p1.messages.first?.toDevice == k1.deviceId && p2.messages.first?.toDevice == k2.deviceId
                      && p1.messages.first?.fromDevice == kb.deviceId && p1.messages.first?.msgId == p2.messages.first?.msgId)
            let r1 = await a1.receiveAndDecrypt(p1), r2 = await a2.receiveAndDecrypt(p2)
            log.check("live.fanout.A1.decrypts", texts(r1) == ["hello-devices"], "\(r1)")
            log.check("live.fanout.A2.decrypts", texts(r2) == ["hello-devices"], "\(r2)")
            // Ack isolation: A1's ack does not remove A2's copy.
            try await ac.ack(userKey: alice.userKey, upToSeq: p1.maxSeq, signer: k1.signer)
            let p2b = try await ac.pullMessages(userKey: alice.userKey, afterSeq: 0, signer: k2.signer)
            log.check("live.ack.perDevice", p2b.messages.count == p2.messages.count)
            try await ac.ack(userKey: alice.userKey, upToSeq: p2.maxSeq, signer: k2.signer)
            // Reply direction: A1 → Bob's device, Bob decrypts under (alice, A1).
            _ = try await a1.sendText(peer: bob.userKey, "reply-from-A1")
            let pb = try await bc.pullMessages(userKey: bob.userKey, afterSeq: 0, signer: kb.signer)
            log.check("live.reply.bobDecrypts", texts(await b.receiveAndDecrypt(pb)).contains("reply-from-A1"))
            try await bc.ack(userKey: bob.userKey, upToSeq: pb.maxSeq, signer: kb.signer)

            // §6.2(a) + §6.5 old sender: account envelopes. Holder decrypts; the other device
            // drops them silently — 0 failures (= 0 resets) over 50 envelopes.
            for i in 0..<50 { _ = try await c.sendText(peer: alice.userKey, "old-\(i)") }
            let q1 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: p1.maxSeq, signer: k1.signer)
            let q2 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: p2.maxSeq, signer: k2.signer)
            let s1 = await a1.receiveAndDecrypt(q1), s2 = await a2.receiveAndDecrypt(q2)
            log.check("live.oldSender.holderDecrypts50", texts(s1).count == 50 && failures(s1) == 0, "texts=\(texts(s1).count) failed=\(failures(s1))")
            log.check("live.oldSender.nonHolder.silentDrop(0 failures, 0 resets)", q2.messages.count == 50 && s2.isEmpty,
                      "pulled=\(q2.messages.count) out=\(s2.count)")
            // The legacy view (an old install of Alice) still sees the account items.
            let legacy = try await ac.pullMessages(userKey: alice.userKey, afterSeq: 0)
            log.check("live.legacyView.seesAccountItems", legacy.messages.filter { $0.toDevice == nil }.count >= 50)
            try await ac.ack(userKey: alice.userKey, upToSeq: q1.maxSeq, signer: k1.signer)
            try await ac.ack(userKey: alice.userKey, upToSeq: q2.maxSeq, signer: k2.signer)

            // §6.2(c) sent from A2: Bob gets it, A1 gets the sent-copy, A2 gets nothing.
            _ = try await a2.sendText(peer: bob.userKey, "from-A2", selfCopy: true)
            let pb2 = try await bc.pullMessages(userKey: bob.userKey, afterSeq: pb.maxSeq, signer: kb.signer)
            log.check("live.selfCopy.peerGetsIt", texts(await b.receiveAndDecrypt(pb2)) == ["from-A2"])
            let w1 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: q1.maxSeq, signer: k1.signer)
            let copy = texts(await a1.receiveAndDecrypt(w1)).compactMap(V2MessageRouter.parseSentCopy).first
            log.check("live.selfCopy.otherDeviceGetsSentCopy", copy?.message == "from-A2" && copy?.peer == bob.userKey)
            let w2 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: q2.maxSeq, signer: k2.signer)
            log.check("live.selfCopy.notToSender", w2.messages.isEmpty)
            try await ac.ack(userKey: alice.userKey, upToSeq: w1.maxSeq, signer: k1.signer)

            // §3.5 409 device-list-mismatch: A3 joins; Bob's cached list is stale.
            let apv3 = try V2Device.approve(newDeviceId: k3.deviceId, newDsk: k3.dskB64, identity: alice.userKey, approver: k2)
            try await ac.registerDevice(V2Device.registration(keys: k3, identity: alice.userKey, approval: apv3))
            try await a3.publishDeviceBundle(signer: k3.signer)
            await a3.setDeviceContext(.init(me: alice.userKey, deviceId: k3.deviceId, signer: k3.signer, holdsAccountBundle: false))
            _ = try await b.sendText(peer: alice.userKey, "after-A3")
            let x3 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: 0, signer: k3.signer)
            log.check("live.mismatch.retriedAndDelivered", texts(await a3.receiveAndDecrypt(x3)).contains("after-A3"))
            let x1 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: w1.maxSeq, signer: k1.signer)
            log.check("live.mismatch.existingDevicesToo", texts(await a1.receiveAndDecrypt(x1)).contains("after-A3"))

            // Media: ONE blob, every device fetches it (nobody deletes it under the others).
            let blobBytes = Data((0..<3000).map { UInt8($0 % 251) })
            _ = try await b.sendFile(peer: alice.userKey, data: blobBytes, filename: "t.bin", mime: "application/octet-stream")
            let m1 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: x1.maxSeq, signer: k1.signer)
            let m3 = try await ac.pullMessages(userKey: alice.userKey, afterSeq: x3.maxSeq, signer: k3.signer)
            var mediaOK = 0
            for (r, p) in [(a1, m1), (a3, m3)] {
                for item in await r.receiveAndDecrypt(p) {
                    if case .file(_, _, _, let key, _) = item, (try? await r.downloadAndDecryptFile(key))?.data == blobBytes { mediaOK += 1 }
                }
            }
            log.check("live.media.everyDeviceDownloads", mediaOK == 2, "\(mediaOK)/2")

            // §6.3 removal: A1 removes A3; A3's next device request answers 410.
            try await ac.unregisterDevice(k3.deviceId, signer: k1.signer)
            var gone = false
            do { _ = try await ac.pullMessages(userKey: alice.userKey, afterSeq: 0, signer: k3.signer) }
            catch V2.ClientError.http(let st, _) { gone = st == 410 }
            log.check("live.unregister.removedDeviceGets410", gone)
        } catch {
            log.check("live.mailbox", false, "threw \(error)")
        }
    }

    // MARK: 3. the APP's own pull loop in device mode

    static func appMailbox(_ log: Log, base: URL) async {
        let me = V2KeychainCrypto().userKey
        guard !me.isEmpty else { log.add("SKIP app.mailbox (no identity)"); return }
        // The app registers itself on its own (pull ticks every second).
        var waited = 0
        while !V2DeviceMailbox.isActive && waited < 60 { try? await Task.sleep(nanoseconds: 1_000_000_000); waited += 1 }
        log.check("app.registeredAutomatically", V2DeviceMailbox.isActive, "after \(waited)s, status=\(V2DeviceMailbox.status.rawValue)")
        log.check("app.holdsAccountBundle", V2DeviceMailbox.holdsAccountBundle)
        guard V2DeviceMailbox.isActive, let myId = V2DeviceMailbox.registeredDeviceId, let myKeys = V2Device.loadKeys() else { return }
        // Wait for the per-device bundle to be published.
        let dave = Person("dave")
        let d = dave.router(base, device: "D")
        do {
            try await d.register()
            var devs: [String] = []
            for _ in 0..<20 { devs = await d.peerDeviceIds(me, refresh: true); if !devs.isEmpty { break }; try? await Task.sleep(nanoseconds: 1_000_000_000) }
            log.check("app.deviceBundlePublished", devs == [myId], "\(devs)")
            // An updated sender (Dave, registered) → the app's device copy → the message list.
            let dk = V2Device.Keys(dk: .init(), dsk: .init())
            try await dave.client(base).registerDevice(V2Device.registration(keys: dk, identity: dave.userKey, approval: nil))
            try await d.publishDeviceBundle(signer: dk.signer)
            await d.setDeviceContext(.init(me: dave.userKey, deviceId: dk.deviceId, signer: dk.signer, holdsAccountBundle: true))
            let id1 = UUID().uuidString
            _ = try await d.sendText(peer: me, "app-live-\(id1.prefix(6))", msgId: id1)
            let arrived = await waitForMessage(id1)
            log.check("app.deviceCopy.delivered", arrived)
            // A second device of the app's account sends a message to Dave with a sent-copy:
            // the app shows it as OUTGOING.
            guard let ikRaw = try? KeychainHelper.load(key: "privateKey"), let skRaw = try? KeychainHelper.load(key: "signingKey") else {
                log.add("SKIP app.sentCopy (keys unreadable)"); return
            }
            try? KeychainHelper.save(key: "selftest.md.appmirror.ik", data: ikRaw)
            try? KeychainHelper.save(key: "selftest.md.appmirror.sk", data: skRaw)
            let mirrorCrypto = TestCrypto(signing: try Curve25519.Signing.PrivateKey(rawRepresentation: skRaw), identity: me)
            let run = "selftest.md.M.\(UUID().uuidString.prefix(6))"
            let mc = V2Client(config: .init(baseURL: base, requestTimeout: 15, maxRetryAttempts: 1), crypto: mirrorCrypto, session: .shared)
            let mirror = V2MessageRouter(client: mc, store: V2SessionStore(sessionPrefix: run + ".s.", ownMaterialKey: run + ".own"),
                                         deviceStore: V2SessionStore(ownMaterialKey: run + ".devown"), crypto: mirrorCrypto,
                                         signingKeychainKey: "selftest.md.appmirror.sk", identityKeychainKey: "selftest.md.appmirror.ik",
                                         oneTimePreKeyCount: 5)
            let km = V2Device.Keys(dk: .init(), dsk: .init())
            let apv = try V2Device.approve(newDeviceId: km.deviceId, newDsk: km.dskB64, identity: me, approver: myKeys)
            try await mc.registerDevice(V2Device.registration(keys: km, identity: me, approval: apv))
            try await mirror.publishDeviceBundle(signer: km.signer)
            await mirror.setDeviceContext(.init(me: me, deviceId: km.deviceId, signer: km.signer, holdsAccountBundle: false))
            let id2 = UUID().uuidString
            _ = try await mirror.sendText(peer: dave.userKey, "sent-from-mirror-\(id2.prefix(6))", msgId: id2, selfCopy: true)
            let copied = await waitForMessage(id2)
            let row = await MainActor.run { MessageManager.shared?.messages.first(where: { $0.id == id2 }).map { ($0.senderPublicKey, $0.recipientPublicKey, "\($0.deliveryStatus)", $0.plaintextContent ?? "") } }
            let outgoing = row.map { $0.0 == me && $0.1 == dave.userKey && $0.2 == "sent" && $0.3.hasPrefix("sent-from-mirror-") } ?? false
            log.check("app.sentCopy.insertedAsOutgoing", copied && outgoing, "copied=\(copied) row=\(row.map { "\($0.0 == me) \($0.1 == dave.userKey) \($0.2) \($0.3.prefix(20))" } ?? "nil")")
            // Removal: the mirror removes the app's device → 410 → legacy + alert.
            try await mc.unregisterDevice(myId, signer: km.signer)
            var w = 0
            while V2DeviceMailbox.status != .removed && w < 20 { try? await Task.sleep(nanoseconds: 1_000_000_000); w += 1 }
            log.check("app.removed.410.alerted", V2DeviceMailbox.status == .removed && !V2DeviceMailbox.isActive)
        } catch {
            log.check("app.mailbox", false, "threw \(error)")
        }
    }

    static func waitForMessage(_ id: String, seconds: Int = 30) async -> Bool {
        for _ in 0..<seconds {
            let found = await MainActor.run { MessageManager.shared?.messages.contains(where: { $0.id == id }) ?? false }
            if found { return true }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        return false
    }
}
#endif

// MARK: - __LEAK_FIX_2026_09_25__ Keychain store for small secret-bearing values
//
// Small Codable values that carry key material (the pending-blob ledger holds received
// attachments' AES keys) used to be written to UserDefaults — a plist that rides in
// iCloud / Finder backups. This keeps them in the Keychain (`KeychainHelper`:
// AfterFirstUnlockThisDeviceOnly, not synchronisable, app-private group) and migrates the
// old UserDefaults value transparently: read once, written to the Keychain, read back,
// and only then removed from defaults.

enum V2SecretBlobStore {
    private static let lock = NSLock()
    /// Last value we could not persist (keychain locked before first unlock): kept in
    /// memory so the running process never loses it.
    private static var unsaved: [String: Data] = [:]

    static func load<T: Decodable>(_ type: T.Type, account: String, legacyDefaultsKey: String,
                                   defaults: UserDefaults = .standard) -> T? {
        guard let data = loadData(account: account, legacyDefaultsKey: legacyDefaultsKey, defaults: defaults) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    static func save<T: Encodable>(_ value: T, isEmpty: Bool, account: String, legacyDefaultsKey: String,
                                   defaults: UserDefaults = .standard) {
        lock.lock(); defer { lock.unlock() }
        if isEmpty {
            try? KeychainHelper.delete(key: account)
            unsaved[account] = nil
            defaults.removeObject(forKey: legacyDefaultsKey)
            return
        }
        guard let data = try? JSONEncoder().encode(value) else { return }
        do {
            try KeychainHelper.save(key: account, data: data)
            unsaved[account] = nil
            defaults.removeObject(forKey: legacyDefaultsKey)
        } catch {
            unsaved[account] = data
            OshiLog.storage.info("⚠️ V2SecretBlobStore: keychain write deferred for \(account) (\(error))")
        }
    }

    static func loadData(account: String, legacyDefaultsKey: String, defaults: UserDefaults = .standard) -> Data? {
        lock.lock(); defer { lock.unlock() }
        if let pending = unsaved[account] {
            // Retry the write now that we might be unlocked.
            if (try? KeychainHelper.save(key: account, data: pending)) != nil {
                unsaved[account] = nil
                defaults.removeObject(forKey: legacyDefaultsKey)
            }
            return pending
        }
        do {
            let data = try KeychainHelper.load(key: account)
            // An interrupted migration may have left the cleartext copy behind.
            if defaults.object(forKey: legacyDefaultsKey) != nil { defaults.removeObject(forKey: legacyDefaultsKey) }
            return data
        } catch KeychainError.itemNotFound {
            guard let legacy = defaults.data(forKey: legacyDefaultsKey) else { return nil }
            do {
                try KeychainHelper.save(key: account, data: legacy)
                if (try? KeychainHelper.load(key: account)) == legacy {
                    defaults.removeObject(forKey: legacyDefaultsKey)
                    OshiLog.storage.info("🔐 V2SecretBlobStore: migrated \(account) out of UserDefaults")
                }
            } catch {
                OshiLog.storage.info("⚠️ V2SecretBlobStore: migration deferred for \(account) (\(error))")
            }
            return legacy
        } catch {
            // Locked (before first unlock): serve the legacy copy if one still exists.
            return defaults.data(forKey: legacyDefaultsKey)
        }
    }
}


/// __COVER_PULL_JITTER_2026_11_18__ Admits at most one cover-mode pull per
/// jittered interval. Pure value type so the timing distribution is unit-tested
/// (`CoverPullGateTests`). Mirrors Android `PollBackoff.coverJitteredMs` (±50 %).
nonisolated struct CoverPullGate {
    var baseInterval: TimeInterval = 5
    var jitterFraction: Double = 0.5
    private(set) var nextAllowed: Date = .distantPast

    mutating func admit(now: Date, random: () -> Double = { Double.random(in: 0...1) }) -> Bool {
        guard now >= nextAllowed else { return false }
        let factor = 1 - jitterFraction + 2 * jitterFraction * random()
        nextAllowed = now.addingTimeInterval(baseInterval * factor)
        return true
    }
}
