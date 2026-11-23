//
//  V2MessageRouter.swift
//  OSHI — orchestrator for the v2 end-to-end (X3DH + Double Ratchet) messaging.
//
//  DEFENSIVE, PURELY ADDITIVE. This is the glue that ties together the v2 crypto
//  core (OSHICryptoV2), the v2 transport client (V2Client), the persistent
//  session store (V2SessionStore) and the group manager (V2GroupSession). It is
//  ONLY ever exercised behind the v2 feature flag via `MessageManager.routeOutgoing`.
//  It does NOT touch the existing IPFS / Pinata path.
//
//  RESPONSIBILITIES
//    * `register()`            — publish THIS device's X3DH bundle to /v2/keys.
//    * `capabilities(peer)`    — does the peer have a v2 bundle? (→ v2 vs IPFS).
//    * 1:1 session setup       — fetch peer bundle, VERIFY the signed-prekey
//                                signature, run X3DH, init the ratchet, persist.
//    * `sendText` / `sendFile` — ratchet-encrypt; files ride the blob store with
//                                the file key travelling inside the ratchet msg.
//    * `receiveAndDecrypt`     — decrypt a pull, handling the X3DH first-message
//                                header (responder side) and group fan-in.
//    * `downloadAndDecryptFile`— fetch blob chunks + open them with the key msg.
//    * `V2GroupContext`        — the per-member crypto seam V2GroupSession borrows.
//
//  Modelled as an `actor` so all mutable session state transitions are serialised
//  and race-free. The wire encodings (base64 header / ciphertext / keys) are
//  self-consistent across our own clients and match the opaque fields the servers
//  forward verbatim.
//
//  ASSUMPTION: a peer's v2 `userKey` is the same base64 identity string OSHI
//  already uses as a contact's public-key address. The caller passes that string
//  straight through; if the two ever diverge, map at the call site.
//
//  NOTE: no iOS runtime in the authoring environment; written to type-check
//  against the sibling files. Runtime verification happens on a Mac.
//

import Foundation
import CryptoKit

// MARK: - Wire payloads / results

/// The ratchet-message payload that carries a file's key material. The bytes live
/// in the blob store; THIS travels inside the (E2E) ratchet message, exactly as
/// the server contract requires ("the file key travels in the ratchet message").
struct V2FileKeyMessage: Codable, Sendable, Equatable {
    /// Discriminator so the receiver can tell a file-key message from plain text.
    let kind: String            // always "v2file"
    let blobId: String
    let fileKey: String         // base64 (32 bytes)
    let fileNonce: String       // base64 (8 bytes)
    let chunkCount: Int
    let manifest: String        // base64 (ct || tag)
    let filename: String
    let mime: String
    /// OSHI media semantics that must survive the transport so the receiver
    /// can rebuild the exact `SecureMessage` (raw value of
    /// `MediaManager.MediaType`; nil for generic files → inferred from mime).
    let mediaType: String?
    /// View-once (ephemeral) media flag. Optional so older payloads decode.
    let isViewOnce: Bool?

    // MARK: - __REVEAL_WIRE_2026_08_22__ Révélation progressive
    //
    // Quatre scalaires OPTIONNELS, sur le patron exact d'`isViewOnce` juste
    // au-dessus. Émis `nil` quand il n'y a pas de révélation, donc le JSON de
    // 100 % du trafic actuel est INCHANGÉ AU BIT PRÈS — c'est ce que verrouille
    // `testFileKeyPayloadUnchangedWhenNoReveal`.
    //
    // 🔴 POURQUOI ICI, ET NULLE PART AILLEURS. Il aurait été plus simple de poser
    // ces champs sur `SecureMessage`. C'est interdit: `SecureMessage` voyage EN
    // CLAIR sur le mesh et sur LoRa, et chaque octet y est prélevé sur le texte
    // de l'utilisateur (budget LoRa). `V2FileKeyMessage` est chiffré dans le
    // ratchet et invisible du relais — c'est le seul porteur conçu pour la
    // sémantique média 1:1.
    //
    // 🔴 AUCUNE ÉNUMÉRATION. Une clé inconnue est ignorée par un décodeur
    // `Codable` à clés comme par le `JSONObject` d'Android; une VALEUR D'ENUM
    // inconnue fait THROW `init(from:)` et emporte le message ENTIER. Des
    // scalaires tolérants, donc, et rien d'autre.

    /// Identifiant de la révélation — l'id du message porteur de la couche 0.
    /// C'est lui qui relie les couches suivantes à la bulle déjà affichée.
    let revealId: String?
    /// Rang de cette couche, 0 = la plus floue, envoyée immédiatement.
    let revealLayerIndex: Int?
    /// Nombre TOTAL de couches, tel que l'encodeur l'a réellement produit —
    /// jamais deviné: le récepteur en dérive `finalIndex`, et un `layerCount`
    /// faux donne une division par zéro puis une date NaN.
    let revealLayerCount: Int?
    /// Durée totale de la révélation, en millisecondes. Millisecondes et non
    /// secondes: c'est l'unité que la v2 emploie déjà sur le fil.
    let revealTotalMs: Int?
    /// GROUP MEDIA ONLY: base64 of the JSON-encoded `GroupMessage` this blob
    /// belongs to, with its inline-media `encryptedContent` STRIPPED. The
    /// receiver decodes it, attaches the decrypted blob bytes as `mediaData` and
    /// hands it to the very same `receiveGroupMessage` funnel the group TEXT path
    /// uses — so id-dedup, the stale-identity gate, auto-add and the group
    /// notification all behave identically. nil for 1:1 files.
    ///
    /// WHY IT RIDES HERE and not in the envelope: the envelope's `groupId` is
    /// server-visible routing metadata; the message id, sender, timestamp, caption
    /// and filename must stay inside the ratchet ciphertext. Optional, so payloads
    /// written before this field decode unchanged.
    let groupMessage: String?

    // MARK: - __VIDEO_NOTE_2026_09_24__ Message vidéo rond
    //
    // Deux scalaires OPTIONNELS de plus, sur le patron d'`isViewOnce`, émis
    // `nil` hors note vidéo: le JSON de tout le trafic existant reste identique
    // au bit près (`testFileKeyPayloadUnchangedWhenNoReveal` le verrouille
    // toujours). Contrat partagé: docs/VIDEO_NOTE_SPEC.md §2.A/§2.B.
    /// `true` sur une note vidéo, jamais `false` (clé absente sinon).
    let videoNote: Bool?
    /// Durée enregistrée en millisecondes, ≤ 15000.
    let durationMs: Int?

    static let kindValue = "v2file"

    /// __REVEAL_WIRE_2026_08_22__ Initialiseur explicite pour donner aux quatre
    /// champs de révélation une valeur par défaut.
    ///
    /// L'initialiseur synthétisé les aurait rendus OBLIGATOIRES à chaque site de
    /// construction — y compris dans des tests qui n'ont rien à voir avec la
    /// révélation, et qui ont effectivement cessé de compiler. Un champ optionnel
    /// qu'il faut quand même écrire partout n'est optionnel que sur le fil.
    init(kind: String, blobId: String, fileKey: String, fileNonce: String,
         chunkCount: Int, manifest: String, filename: String, mime: String,
         mediaType: String?, isViewOnce: Bool?,
         revealId: String? = nil, revealLayerIndex: Int? = nil,
         revealLayerCount: Int? = nil, revealTotalMs: Int? = nil,
         groupMessage: String?,
         videoNote: Bool? = nil, durationMs: Int? = nil) {
        self.kind = kind
        self.blobId = blobId
        self.fileKey = fileKey
        self.fileNonce = fileNonce
        self.chunkCount = chunkCount
        self.manifest = manifest
        self.filename = filename
        self.mime = mime
        self.mediaType = mediaType
        self.isViewOnce = isViewOnce
        self.revealId = revealId
        self.revealLayerIndex = revealLayerIndex
        self.revealLayerCount = revealLayerCount
        self.revealTotalMs = revealTotalMs
        self.groupMessage = groupMessage
        self.videoNote = videoNote
        self.durationMs = durationMs
    }

    /// The note metadata this key message carries (spec §1: flag + video).
    var videoNoteMetadata: VideoNoteMetadata? {
        VideoNoteMetadata.from(flag: videoNote, durationMs: durationMs,
                               isVideo: VideoNoteMetadata.isVideoType(mediaType, mime: mime))
    }
}

extension V2FileKeyMessage {
    /// __VIDEO_NOTE_2026_09_24__ Explicit only so that the two video-note keys
    /// decode TOLERANTLY — a mistyped `videoNote` from some peer must degrade to
    /// a normal video, not throw away the file (the synthesized decoder would).
    /// Every other key decodes exactly as before.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = try c.decode(String.self, forKey: .kind)
        blobId = try c.decode(String.self, forKey: .blobId)
        fileKey = try c.decode(String.self, forKey: .fileKey)
        fileNonce = try c.decode(String.self, forKey: .fileNonce)
        chunkCount = try c.decode(Int.self, forKey: .chunkCount)
        manifest = try c.decode(String.self, forKey: .manifest)
        filename = try c.decode(String.self, forKey: .filename)
        mime = try c.decode(String.self, forKey: .mime)
        mediaType = try c.decodeIfPresent(String.self, forKey: .mediaType)
        isViewOnce = try c.decodeIfPresent(Bool.self, forKey: .isViewOnce)
        revealId = try c.decodeIfPresent(String.self, forKey: .revealId)
        revealLayerIndex = try c.decodeIfPresent(Int.self, forKey: .revealLayerIndex)
        revealLayerCount = try c.decodeIfPresent(Int.self, forKey: .revealLayerCount)
        revealTotalMs = try c.decodeIfPresent(Int.self, forKey: .revealTotalMs)
        groupMessage = try c.decodeIfPresent(String.self, forKey: .groupMessage)
        videoNote = (try? c.decodeIfPresent(Bool.self, forKey: .videoNote)) ?? nil
        if let whole = try? c.decodeIfPresent(Int.self, forKey: .durationMs) {
            durationMs = VideoNoteMetadata.sanitize(whole)
        } else if let real = try? c.decodeIfPresent(Double.self, forKey: .durationMs), real.isFinite {
            durationMs = VideoNoteMetadata.sanitize(Int(real.rounded()))
        } else {
            durationMs = nil
        }
    }
}

/// __REVEAL_WIRE_2026_08_22__ Ce qu'une couche de révélation ajoute à un envoi.
///
/// Un paramètre optionnel unique plutôt que quatre: quatre arguments qu'on peut
/// passer indépendamment, ce sont quatre occasions d'en oublier un et d'émettre
/// une planification incohérente — un `layerCount` sans `layerIndex` donne une
/// division par zéro chez le destinataire.
struct RevealSendContext: Sendable, Equatable {
    let revealId: String
    let layerIndex: Int
    let layerCount: Int
    let totalMs: Int
}

/// A decrypted inbound message surfaced by `receiveAndDecrypt`. `groupId` is
/// non-nil for group traffic. `.failed` isolates a single bad envelope so one
/// failure never aborts the whole batch (the error is stringified to stay Sendable).
enum V2IncomingMessage: Sendable {
    case text(from: String, msgId: String, seq: Int, text: String, groupId: String?)
    case file(from: String, msgId: String, seq: Int, key: V2FileKeyMessage, groupId: String?)
    /// __GROUP_FAILURE_WIPES_1TO1_2026_08_28__ `groupId` a ete AJOUTE.
    /// Sans lui, l'echec d'une enveloppe de GROUPE etait indiscernable d'une
    /// casse de ratchet 1:1: il alimentait la meme preuve, epuisait le meme
    /// budget d'ack, et finissait par effacer la session 1:1 — parfaitement
    /// saine — partagee avec ce membre. Une reference de contexte de groupe
    /// tombee (`GroupError.contextUnavailable`, V2GroupSession.swift:192)
    /// suffisait a emprunter ce chemin.
    case failed(from: String, msgId: String, seq: Int, error: String,
                kind: V2DecryptFailureKind, groupId: String?)
}

/// Pourquoi une enveloppe n'a pas pu être ouverte — la SEULE information qui
/// permette de décider entre « attendre » et « réparer ».
///
/// __V2_FAILURE_IS_TYPED_2026_08_28__ Jusqu'ici les deux décisions étaient
/// soudées dans un compteur unique: 8 tentatives, quelle que soit la cause.
/// Ce compteur mesure des TICS (1 Hz en avant-plan), pas des preuves. Or
/// réessayer les MÊMES octets contre le MÊME état de ratchet est un calcul
/// DÉTERMINISTE: il n'apporte aucune information nouvelle. Le hors-séquence,
/// lui, est déjà traité par le ratchet lui-même (MKSKIPPED persisté,
/// `skipMessageKeys` jusqu'à `maxSkip = 1000`), donc il n'a jamais eu besoin
/// des 8 tics non plus.
///
/// La taxonomie se calcule là où l'`Error` TYPÉE existe encore — une fois
/// `.failed` construit, il ne reste qu'une `String`, et classer par analyse
/// de texte serait à la merci du premier changement de message d'erreur.
enum V2DecryptFailureKind: String, Sendable {
    /// Aucun enregistrement local ET aucun en-tête X3DH. Effacer est un no-op,
    /// et attendre n'ajoutera jamais ni session ni en-tête.
    case noSession
    /// Une session EXISTE, elle n'ouvre pas le message, et aucun en-tête ne
    /// peut la remplacer. La seule classe réellement ambiguë.
    case ratchetBreak
    /// L'écart de numérotation dépasse `maxSkip`. Le relais renvoie la queue
    /// ENTIÈRE après le curseur: les messages manquants ne sont pas en route.
    case gapTooLarge
    /// L'initiateur a dérivé SK avec une pré-clé à usage unique que cet
    /// appareil ne détient pas (`__OPK_MUST_BE_OURS_2026_08_16__`).
    case unusablePreKey
    /// L'identité du bundle ne correspond pas au pair demandé, ou sa signature
    /// est invalide. On ne « répare » pas ça: on le journalise.
    case spoofedIdentity
    /// Les octets sont inexploitables. Ils ne changeront pas au re-pull.
    case malformedEnvelope
    /// Non classé. Garde le budget long: une casse d'un genre non prévu doit
    /// rester rattrapée par l'ancien filet, pas disparaître.
    case transient

    /// Fonction PURE: pas d'horloge, pas de réseau, pas d'état. C'est ce qui
    /// la rend testable cas par cas.
    ///
    /// Le `switch` sur `RouterError` est EXHAUSTIF SANS `default`, et c'est
    /// délibéré: la seule chose qui périme une taxonomie, c'est un cas ajouté
    /// à l'enum et jamais classé. Ici le compilateur le refuse.
    ///
    /// À CONSIGNER, vérifié dans l'arbre:
    ///   • `missingX3DHForNewSession` est DÉCLARÉE et n'a AUCUN site de throw.
    ///     Le cas réel est `noSessionForDecrypt`; ne bâtir aucune politique
    ///     sur la première.
    ///   • `healWithLiveSession` et `sessionVanishedUnderLock` sont levées par
    ///     le chemin d'ÉMISSION (`sendHealSignal`, `encrypt1to1`). Elles ne
    ///     peuvent pas remonter en `.failed`; elles sont classées `.transient`
    ///     parce que c'est la seule réponse honnête si elles y arrivaient un
    ///     jour — surtout pas un effacement de session.
    nonisolated static func classify(_ error: Error) -> V2DecryptFailureKind {
        if let e = error as? V2MessageRouter.RouterError {
            switch e {
            case .noSessionForDecrypt, .missingX3DHForNewSession: return .noSession
            case .ratchetBreak:                                   return .ratchetBreak
            case .unknownOneTimePreKey:                           return .unusablePreKey
            case .bundleIdentityMismatch, .badSignedPreKeySignature: return .spoofedIdentity
            case .malformedHeader, .malformedFileKey, .malformedBundleKey, .malformedPadding: return .malformedEnvelope
            case .healWithLiveSession, .sessionVanishedUnderLock:  return .transient
            }
        }
        if let e = error as? OSHICryptoV2.CryptoError {
            switch e {
            case .tooManySkippedMessages: return .gapTooLarge
            case .ciphertextTooShort, .chunkCountMismatch, .sizeMismatch, .mediaPaddingInvalid: return .malformedEnvelope
            case .badState: return .transient
            }
        }
        return .transient
    }
}

// MARK: - V2MessageRouter

actor V2MessageRouter {

    /// Below this, media uploads run on the FOREGROUND session.
    ///
    /// 4 MB: at the 452–590 KB/s measured against the live host that is roughly
    /// 7–9 s of transfer — about as long as someone will watch a progress bar
    /// before switching apps. Under it, `nsurlsessiond`'s scheduling latency
    /// dwarfs the transfer (a 502 KB photo: <2 s of work, ~60 s observed); over
    /// it, surviving suspension is worth more than starting promptly.
    ///
    /// Streaming sends (>= V2.streamingSendThreshold, 16 MB) always use the
    /// background session and never consult this.
    static let backgroundUploadMinBytes = 4 * 1024 * 1024


    enum RouterError: Error, LocalizedError {
        case malformedBundleKey
        case badSignedPreKeySignature(peer: String)
        case bundleIdentityMismatch(peer: String)
        case malformedHeader
        case malformedFileKey
        case noSessionForDecrypt(peer: String)
        /// __V2_FAILURE_IS_TYPED_2026_08_28__ Session existante + échec
        /// d'ouverture + AUCUN en-tête X3DH pour la remplacer. Levée à un
        /// SEUL endroit — la branche [RATCHET-H1] de `decrypt1to1` — parce
        /// que c'est le seul endroit du code qui sache les deux choses à la
        /// fois. Le rethrow nu qu'elle remplace faisait arriver la casse
        /// réelle et un `malformedHeader` au consommateur sous la même forme.
        /// __ERROR_CARRIES_IDENTITY_2026_08_28__ `peer` est un PREFIXE, jamais
        /// la cle entiere. Dans ce protocole l'adresse EST l'identite X25519
        /// (`establishResponderSession` impose `x3dh.identityKey == sender`),
        /// et cette erreur finit interpolee dans une ligne
        /// `DIAG_V2_UNDECRYPTABLE` de `CallFileLogger` — un journal EXPORTE.
        /// Une desynchronisation persistante y aurait donc recopie la cle
        /// publique complete du correspondant, a chaque tic, dans un fichier
        /// destine au partage. Le prefixe suffit a diagnostiquer; le reste
        /// n'apporte rien et voyage.
        case ratchetBreak(peer: String, underlying: String)
        case healWithLiveSession(peer: String)
        case missingX3DHForNewSession(peer: String)
        /// The initiator derived SK with a one-time pre-key we do not hold.
        case unknownOneTimePreKey(peer: String, keyId: String)
        /// `loadOrEstablish` returned, but the record was gone by the time we took
        /// the cross-process lock — another process deleted it in between.
        case sessionVanishedUnderLock(peer: String)
        /// __V2_PADDING_2026_11_09__ The plaintext carried the padding flag but its
        /// ISO 7816-4 tail was broken. Authenticated bytes from the peer, so nothing a
        /// session reset could fix: classified `malformedEnvelope` (acked, never healed).
        case malformedPadding

        var errorDescription: String? {
            switch self {
            case .malformedBundleKey: return "V2MessageRouter: a bundle key was not valid base64"
            case .badSignedPreKeySignature(let p): return "V2MessageRouter: signed-prekey signature invalid for \(p)"
            case .bundleIdentityMismatch(let p): return "V2MessageRouter: fetched bundle identity != requested peer \(p) (possible MITM)"
            case .malformedHeader: return "V2MessageRouter: ratchet header could not be decoded"
            case .malformedFileKey: return "V2MessageRouter: file key message was malformed"
            case .noSessionForDecrypt(let p): return "V2MessageRouter: no session and no X3DH header from \(p)"
            case .ratchetBreak(let p, let u): return "V2MessageRouter: the existing session with \(p) cannot open this envelope and it carries no X3DH header — \(u)"
            case .healWithLiveSession(let p): return "V2MessageRouter: heal signal for \(p) requires the local session to be deleted first"
            case .missingX3DHForNewSession(let p): return "V2MessageRouter: first message from \(p) lacked an X3DH header"
            case .unknownOneTimePreKey(let p, let id): return "V2MessageRouter: \(p) bootstrapped with one-time pre-key \(id.prefix(8))… which this device does not hold — the server handed out a pre-key from a dead generation"
            case .sessionVanishedUnderLock(let p): return "V2MessageRouter: the session with \(p) disappeared between establishing it and locking it"
            case .malformedPadding: return "V2MessageRouter: padded plaintext with a malformed padding tail"
            }
        }
    }

    /// Result of a peer capability probe.
    enum Capability: Sendable {
        case available                 // peer has a v2 bundle (or we already have a session)
        case unavailable               // peer has no bundle → caller should use IPFS
        case unknown                   // transient/network error → caller should use IPFS for now
    }

    // MARK: Dependencies

    private let client: V2Client
    private let store: V2SessionStore
    /// __PER_DEVICE_MAILBOX_2026_09_23__ Own key material of THIS device's per-device bundle
    /// (its own signed pre-key and one-time pool). Kept apart from the account material so
    /// a one-time pre-key is never published twice (once per pool) and consumed twice.
    private let deviceStore: V2SessionStore
    private let group: V2GroupSession
    private let crypto: V2CryptoProviding
    private let signingKeychainKey: String
    /// Keychain item of the X25519 account identity. Always "privateKey" in the app; the
    /// DEBUG multi-device self-test runs several identities side by side.
    private let identityKeychainKey: String
    private let oneTimePreKeyCount: Int

    /// Associated data bound into every ratchet AEAD. Fixed + empty so both ends
    /// pass byte-identical AAD; the ratchet header (which the core appends to the
    /// AAD internally) already authenticates the message ordering.
    private let associatedData = Data()

    /// Bundles fetched during `capabilities` are cached so the subsequent
    /// `establishInitiatorSession` reuses them (avoiding a second server-side
    /// one-time-prekey pop).
    private var bundleCache: [String: V2.FetchedBundle] = [:]

    /// __PER_DEVICE_MAILBOX_2026_09_23__ Set by the app while device mode is active (flag on
    /// + this install registered). nil — the share extension, the flag off, not registered —
    /// means every path below is exactly the account path it always was.
    private var deviceContext: V2DeviceContext?
    /// Peer → device ids holding a bundle, cached 10 min (spec §3.5).
    private var peerDeviceCache: [String: (ids: [String], at: Date)] = [:]
    static let peerDeviceCacheTTL: TimeInterval = 600
    /// Set only while a per-device GROUP envelope is being opened (see `decryptFromMember`).
    private var inboundDeviceSlot: (sender: String, slot: String)?
    /// Slot suffix for a per-device copy whose sender has no device id (spec §3.4 row 2).
    /// Kept apart from the bare legacy key: the sender's side of that session is
    /// `(me, myDevice)`, a different ratchet from its legacy `(me, nil)` one, and sharing
    /// one slot would make the two clobber each other.
    static let noDeviceSlot = "-"
    /// __LEGACY_READER_2026_09_28__ UserDefaults suite of the per-peer read view (nil = standard).
    /// Injected so tests do not touch the app's defaults.
    private let peerViewSuite: String?

    init(client: V2Client = V2Client(),
         store: V2SessionStore = V2SessionStore(),
         deviceStore: V2SessionStore = V2SessionStore(ownMaterialKey: KeychainHelper.Account.v2OwnKeys + ".device"),
         group: V2GroupSession = V2GroupSession(),
         crypto: V2CryptoProviding = V2KeychainCrypto(),
         signingKeychainKey: String = "signingKey",
         identityKeychainKey: String = "privateKey",
         oneTimePreKeyCount: Int = 20,
         peerViewSuite: String? = nil) {
        self.identityKeychainKey = identityKeychainKey
        self.peerViewSuite = peerViewSuite
        self.client = client
        self.store = store
        self.deviceStore = deviceStore
        self.group = group
        self.crypto = crypto
        self.signingKeychainKey = signingKeychainKey
        self.oneTimePreKeyCount = oneTimePreKeyCount
    }

    /// Attach this router as the group manager's crypto context. Call once after
    /// construction, before any group operation.
    func attachGroup() async {
        await group.attach(context: self)
    }

    /// The group manager, for callers that want to drive group membership / sends.
    var groups: V2GroupSession { group }

    // MARK: - Own key material + registration

    /// Ensure THIS device has local v2 key material (X25519 identity + signed
    /// pre-key + one-time pre-key pool), generating and persisting it once.
    ///
    /// The X3DH identity key (IK) is NOT a fresh key: it is the legacy
    /// Curve25519 key-agreement identity OSHI already uses as the contact
    /// address (Keychain "privateKey"). That makes `bundle.identityKey ==
    /// contact address == userKey`, which is exactly what the TOFU check in
    /// `establishInitiatorSession` verifies — peers authenticate our v2 bundle
    /// against the identity they already know (Signal's long-term-IK design).
    /// Material persisted with a different identity (pre-fix random IK, or an
    /// identity restore/rotation) is detected by pub mismatch and regenerated.
    @discardableResult
    private func ensureOwnKeyMaterial(forDevice: Bool = false) throws -> V2SessionStore.OwnKeyMaterial {
        let identity = try loadLegacyIdentityPair()
        let store = forDevice ? self.deviceStore : self.store

        if let existing = try store.loadOwnKeyMaterial() {
            // 🔧 FIX (2026-08-12, v2 was dead for every new peer): the identity
            // check alone was not enough, because material stored by an earlier
            // build carries a signing key that CANNOT verify its own signature —
            // see the note at `signingPublicKeyB64` below. Such material has to be
            // rebuilt even though the identity matches, or the device keeps
            // publishing an unverifiable bundle forever.
            let selfConsistent = Data(base64Encoded: existing.signingPublicKeyB64).map {
                OSHICryptoV2.verifyPrekey($0, existing.signedPreKey.pub, existing.signedPreKeySignature)
            } ?? false
            if existing.identity.pub == identity.pub && selfConsistent {
                return existing
            }
            if !selfConsistent {
                CallFileLogger.shared.log("DIAG_V2_KEYS | repaired=stored_bundle_could_not_verify_itself")
            }
            // Stale material bound to another identity — rebuild; register()
            // republishes the fresh bundle on the next router build.
        }

        let spk = OSHICryptoV2.generateX25519()
        let signingPair = try loadSigningPair()
        let spkSignature = try OSHICryptoV2.signPrekey(signingPair, spk.pub)

        var opks: [V2SessionStore.OneTimePreKeyEntry] = []
        opks.reserveCapacity(oneTimePreKeyCount)
        for _ in 0..<oneTimePreKeyCount {
            let p = OSHICryptoV2.generateX25519()
            opks.append(.init(keyId: UUID().uuidString, pair: .init(p)))
        }

        let material = V2SessionStore.OwnKeyMaterial(
            identity: .init(identity),
            signedPreKey: .init(spk),
            signedPreKeyId: UUID().uuidString,
            signedPreKeySignature: spkSignature,
            // 🔧 FIX (2026-08-12): this published `crypto.userKey` — the X25519
            // KEY-AGREEMENT public key — as the bundle's `signingKey`. But the
            // signature above is produced by `loadSigningPair()`, an Ed25519
            // SIGNING key from a different Keychain entry. So every bundle told
            // peers "verify my signed pre-key with this X25519 key" while the
            // signature had been made with an unrelated Ed25519 one.
            //
            // `verifyPrekey` therefore could never succeed, `establishInitiatorSession`
            // threw `badSignedPreKeySignature` for EVERY peer, and every send fell
            // back to legacy IPFS. Measured on a real device:
            //
            //   DIAG_V2_DECLINE | why=send_threw | err=badSignedPreKeySignature(peer: …)
            //   DIAG_UPLOAD | phase=begin | envelopeBytes=3795909
            //
            // 641 KB of photo shipped as 3.79 MB — the legacy envelope's 5.92×
            // expansion — at ~150 KB/s, which is the reported ~25 s exactly. The
            // slow media was a SYMPTOM; v2 never established a single new session.
            signingPublicKeyB64: signingPair.pub.base64EncodedString(),
            oneTimePreKeys: opks
        )
        try store.saveOwnKeyMaterial(material)
        return material
    }

    /// Load the shared Ed25519 signing identity (the same "signingKey" Keychain
    /// item used by VoIPPushManager / V2KeychainCrypto) as an OSHICryptoV2 pair.
    private func loadSigningPair() throws -> OSHICryptoV2.Ed25519Pair {
        let raw = try KeychainHelper.load(key: signingKeychainKey)
        let sk = try Curve25519.Signing.PrivateKey(rawRepresentation: raw)
        return OSHICryptoV2.Ed25519Pair(priv: raw, pub: sk.publicKey.rawRepresentation)
    }

    /// Load the legacy X25519 key-agreement identity (Keychain "privateKey" —
    /// the pair behind `IdentityManager.publicKey`) as the X3DH identity key.
    private func loadLegacyIdentityPair() throws -> OSHICryptoV2.X25519Pair {
        let raw = try KeychainHelper.load(key: identityKeychainKey)
        let sk = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw)
        return OSHICryptoV2.X25519Pair(priv: raw, pub: sk.publicKey.rawRepresentation)
    }

    /// Publish this device's X3DH bundle to `/v2/keys` so peers can start sessions.
    func register() async throws {
        let m = try ensureOwnKeyMaterial()
        let bundle = V2.PreKeyBundleUpload(
            identityKey: m.identity.pub.base64EncodedString(),
            signingKey: m.signingPublicKeyB64,
            signedPreKey: V2.SignedPreKey(
                keyId: m.signedPreKeyId,
                key: m.signedPreKey.pub.base64EncodedString(),
                signature: m.signedPreKeySignature.base64EncodedString()
            ),
            oneTimePreKeys: m.oneTimePreKeys.map {
                V2.OneTimePreKey(keyId: $0.keyId, key: $0.pair.pub.base64EncodedString())
            },
            // __CLIENT_CAPS_2026_08_31__ On annonce ce qu'on sait lire, la ou
            // les deux cotes regardent DEJA avant de s'ecrire.
            caps: RatchetHealSignal.Caps.mine
        )
        _ = try await client.publishBundle(bundle)
    }

    /// Top up the server-side one-time pre-key pool when it runs low. Peers pop
    /// one OPK per new session; without refill the pool drains and later peers
    /// fall back to the signed pre-key only (still secure, but reduced forward
    /// secrecy for the first message). Checks the SERVER count (authoritative —
    /// it pops as peers fetch), and when below `lowWater` generates enough fresh
    /// OPKs to reach `target`, persists their private halves locally, and
    /// publishes a top-up bundle. The server APPENDS OPKs on republish, so the
    /// surviving pool is never dropped. Fresh UUID keyIds avoid collisions.
    func refillOneTimePreKeysIfNeeded(lowWater: Int = 5, target: Int = 20) async throws {
        let me = crypto.userKey
        guard !me.isEmpty else { return }
        let remaining = try await client.prekeyCount(userKey: me)
        guard remaining < lowWater else { return }

        let material = try ensureOwnKeyMaterial()
        let need = max(0, target - remaining)
        guard need > 0 else { return }

        var newEntries: [V2SessionStore.OneTimePreKeyEntry] = []
        newEntries.reserveCapacity(need)
        for _ in 0..<need {
            let p = OSHICryptoV2.generateX25519()
            newEntries.append(.init(keyId: UUID().uuidString, pair: .init(p)))
        }
        try store.appendOneTimePreKeys(newEntries)

        // Republish: long-lived fields unchanged, ONLY the new OPKs (append).
        let bundle = V2.PreKeyBundleUpload(
            identityKey: material.identity.pub.base64EncodedString(),
            signingKey: material.signingPublicKeyB64,
            signedPreKey: V2.SignedPreKey(
                keyId: material.signedPreKeyId,
                key: material.signedPreKey.pub.base64EncodedString(),
                signature: material.signedPreKeySignature.base64EncodedString()
            ),
            oneTimePreKeys: newEntries.map {
                V2.OneTimePreKey(keyId: $0.keyId, key: $0.pair.pub.base64EncodedString())
            },
            // __REPUBLISH_WIPED_CAPS_2026_10_19__ This omitted `caps`, and the
            // parameter defaults to 0 — so a routine one-time-prekey top-up
            // REPLACED the bundle `register()` had published with
            // `RatchetHealSignal.Caps.mine` and silently un-advertised
            // resendList + groupV2.
            //
            // Nothing reports it, because a capability is read as a fact about
            // the peer: `GroupEnvelopeV3` says "Absent means 'cannot', never
            // 'assume yes': the cost of guessing wrong is a member who stops
            // receiving group messages." So the peer is quietly demoted and the
            // sender does the demoting for it.
            //
            // Measured on this Mac: `/v2/keys/<id>` returned `caps: 0` while
            // every other live identity returned 3, and group updates added on
            // another device never arrived. The comment at `register()` — "on
            // annonce ce qu'on sait lire" — has to hold on EVERY publish, not
            // just the first.
            caps: RatchetHealSignal.Caps.mine
        )
        let now = try await client.publishBundle(bundle)
        OshiLog.messaging.info("🔑 [V2] OPK pool refilled: \(remaining) → \(now) (+\(need))")
    }

    // MARK: - Capabilities

    /// Whether `peerUserKey` can receive v2 messages. An existing session counts
    /// as available without a network round-trip. A fetched bundle is cached for
    /// the immediate `sendText`/`sendFile` so it is not fetched twice.
    func capabilities(peerUserKey: String) async -> Capability {
        if store.hasSession(peerUserKey: peerUserKey) { return .available }
        do {
            let bundle = try await client.fetchBundle(userKey: peerUserKey)
            bundleCache[peerUserKey] = bundle
            RatchetHealSignal.Caps.remember(peer: peerUserKey, caps: bundle.caps ?? 0)
            // __V2_PADDING_2026_11_09__ the account bundle a session with `peerUserKey` would be built on.
            V2PaddingPeers.noteBundle(peer: peerUserKey, device: nil, caps: bundle.caps)
            // __MEDIA_PADDING_2026_11_10__ same place, same slot, bit 8.
            V2MediaPaddingPeers.noteBundle(peer: peerUserKey, device: nil, caps: bundle.caps)
            return .available
        } catch let V2.ClientError.http(status, _) where status == 404 {
            return .unavailable
        } catch {
            return .unknown
        }
    }

    // MARK: - 1:1 session establishment

    /// Return the existing session for `peer`, or establish one as the X3DH
    /// initiator (fetching + verifying the peer bundle) and return that.
    private func loadOrEstablish(peer: String, device: String? = nil) async throws -> V2SessionStore.SessionRecord {
        if let existing = try store.loadSession(peerUserKey: Self.slot(peer, device: device)) { return existing }
        return try await establishInitiatorSession(peer: peer, device: device)
    }

    /// __PER_DEVICE_MAILBOX_2026_09_23__ Session key `(peer, deviceId?)`. The legacy session
    /// is the bare peer key, so everything recorded before this change is untouched. A
    /// userKey is base64 and never contains `#`.
    nonisolated static func slot(_ peer: String, device: String?) -> String {
        guard let device, !device.isEmpty else { return peer }
        return peer + "#" + device
    }

    private func establishInitiatorSession(peer: String, device: String? = nil) async throws -> V2SessionStore.SessionRecord {
        let material = try ensureOwnKeyMaterial()
        let slotKey = Self.slot(peer, device: device)

        let bundle: V2.FetchedBundle
        if let cached = bundleCache[slotKey] {
            bundle = cached
        } else if let device {
            bundle = try await client.fetchBundle(userKey: peer, device: device)
        } else {
            bundle = try await client.fetchBundle(userKey: peer)
        }
        bundleCache.removeValue(forKey: slotKey)
        // __CLIENT_CAPS_2026_08_31__ Le SECOND point de recuperation, et il
        // compte autant que le premier: `capabilities()` sort tot quand une
        // session existe deja, donc c'est souvent ICI que le bundle est
        // reellement lu. Retenir seulement dans `capabilities()` laisserait les
        // capacites inconnues precisement pour les pairs actifs.
        RatchetHealSignal.Caps.remember(peer: peer, caps: bundle.caps ?? 0)
        // __V2_PADDING_2026_11_09__ Keyed by the SLOT this bundle builds a session for —
        // the line above is keyed by identity even for a per-device bundle.
        V2PaddingPeers.noteBundle(peer: peer, device: device, caps: bundle.caps)
        V2MediaPaddingPeers.noteBundle(peer: peer, device: device, caps: bundle.caps)   // __MEDIA_PADDING_2026_11_10__

        // Decode + VERIFY the signed pre-key against the peer's signing identity.
        guard let theirIkPub = Data(base64Encoded: bundle.identityKey),
              let theirSigningPub = Data(base64Encoded: bundle.signingKey),
              let theirSpkPub = Data(base64Encoded: bundle.signedPreKey.key),
              let spkSig = Data(base64Encoded: bundle.signedPreKey.signature) else {
            throw RouterError.malformedBundleKey
        }
        guard OSHICryptoV2.verifyPrekey(theirSigningPub, theirSpkPub, spkSig) else {
            throw RouterError.badSignedPreKeySignature(peer: peer)
        }
        // TOFU identity binding (H-1): the peer's routing address IS their X25519
        // identity key, so a bundle whose identityKey != the requested peer means
        // the server substituted keys (MITM). Reject before establishing a session.
        guard bundle.identityKey == peer else {
            throw RouterError.bundleIdentityMismatch(peer: peer)
        }
        let theirOpkPub: Data? = bundle.oneTimePreKey.flatMap { Data(base64Encoded: $0.key) }

        // X3DH (initiator) + immediate first DH ratchet (Alice).
        let ephemeral = OSHICryptoV2.generateX25519()
        let sk = try OSHICryptoV2.x3dhInitiator(
            ik: material.identity.x25519,
            ek: ephemeral,
            theirIkPub: theirIkPub,
            theirSpkPub: theirSpkPub,
            theirOpkPub: theirOpkPub
        )
        let state = try OSHICryptoV2.ratchetInitAlice(SK: sk, bobRatchetPub: theirSpkPub)

        // Header that MUST ride on the first outbound message.
        let x3dhHeader = V2.X3DHHeader(
            identityKey: material.identity.pub.base64EncodedString(),
            ephemeralKey: ephemeral.pub.base64EncodedString(),
            signedPreKeyId: bundle.signedPreKey.keyId,
            oneTimePreKeyId: bundle.oneTimePreKey?.keyId
        )

        let record = V2SessionStore.SessionRecord(
            peerUserKey: slotKey,
            role: .initiator,
            ratchet: .init(state),
            peerIdentityKey: theirIkPub,
            peerSigningKey: theirSigningPub,
            pendingInitiatorHeader: x3dhHeader
        )
        try store.saveSession(record)
        return record
    }

    /// Establish a session as the X3DH responder from an inbound first-message
    /// header. Consumes the matching one-time pre-key. The returned record is NOT
    /// yet ratcheted-forward — the caller decrypts and persists.
    private func establishResponderSession(sender: String,
                                           x3dh: V2.X3DHHeader,
                                           slot: String? = nil,
                                           deviceMaterial: Bool = false) throws -> V2SessionStore.SessionRecord {
        let material = try ensureOwnKeyMaterial(forDevice: deviceMaterial)

        // Responder-side TOFU (mirror of the initiator check): the initiator's
        // X3DH identity key MUST equal their routing address (`from`/sender),
        // because the address IS the X25519 identity. A mismatch means someone
        // is trying to bootstrap a session under a spoofed sender — reject it.
        guard x3dh.identityKey == sender else {
            throw RouterError.bundleIdentityMismatch(peer: sender)
        }

        guard let theirIkPub = Data(base64Encoded: x3dh.identityKey),
              let theirEkPub = Data(base64Encoded: x3dh.ephemeralKey) else {
            throw RouterError.malformedBundleKey
        }

        // Our signed pre-key is the initial ratchet pair; find the consumed OPK.
        let spkPair = material.signedPreKey.x25519
        // __OPK_MUST_BE_OURS_2026_08_16__
        // A one-time pre-key id we do not hold used to fall through this `if` and
        // leave `opkPair == nil` — so the responder derived SK from three DHs while
        // the initiator used four. Two different SKs, and the ONLY symptom was that
        // the first message failed to authenticate: `.failed`, eight silent retry
        // ticks, acked away. No line anywhere said "I was handed a pre-key that is
        // not mine", which is why this looked like a broken ratchet for days and
        // why resetting the session made it recur every single time (a reset forces
        // a fresh X3DH, which pops another dead pre-key).
        //
        // MEASURED on the live prekey store: `POST /v2/keys/publish` APPENDS
        // one-time pre-keys to the surviving pool while REPLACING the long-lived
        // material, and `GET /v2/keys/:userKey` pops with `shift()` — the OLDEST
        // first. iPhone 17: 5302 entries, 40 distinct ids, and the current
        // generation does not start until index 4362. iPhone 14: 1440 entries,
        // current generation at index 360. Every bootstrap was popping a pre-key
        // whose private half this device discarded when it rebuilt its material.
        //
        // `nil` stays legitimate for the no-OPK bootstrap (pool drained → the
        // initiator sends no id and derives three DHs too). It is a hard error ONLY
        // when an id was named and we cannot honour it — better a loud failure than
        // a session that can never decrypt anything.
        var opkPair: OSHICryptoV2.X25519Pair? = nil
        if let opkId = x3dh.oneTimePreKeyId {
            guard let entry = material.oneTimePreKeys.first(where: { $0.keyId == opkId }) else {
                CallFileLogger.shared.log("DIAG_V2_OPK_UNKNOWN | peer=\(sender.prefix(12)) | keyId=\(opkId.prefix(8)) | held=\(material.oneTimePreKeys.count)")
                throw RouterError.unknownOneTimePreKey(peer: sender, keyId: opkId)
            }
            opkPair = entry.pair.x25519
        }

        let sk = try OSHICryptoV2.x3dhResponder(
            ik: material.identity.x25519,
            spk: spkPair,
            opk: opkPair,
            theirIkPub: theirIkPub,
            theirEkPub: theirEkPub
        )
        let state = OSHICryptoV2.ratchetInitBob(SK: sk, bobRatchetPair: spkPair)

        // NOTE: the one-time pre-key is NOT burned here. The caller consumes it
        // only after the first message AEAD-verifies (OTPK-H2) — otherwise a
        // forged X3DH header could drain the pool without ever authenticating.

        let senderSigning = Data(base64Encoded: sender)
        return V2SessionStore.SessionRecord(
            peerUserKey: slot ?? sender,
            role: .responder,
            ratchet: .init(state),
            peerIdentityKey: theirIkPub,
            peerSigningKey: senderSigning
        )
    }

    // MARK: - 1:1 encrypt / decrypt core (shared by direct + group paths)

    /// - Parameter padGate: __V2_PADDING_2026_11_09__ the caller's group-level rule —
    ///   false when a group has a member that cannot strip padding. 1:1 passes true;
    ///   the per-session capability is checked here, where the slot is known.
    private func encrypt1to1(peer: String,
                             plaintext: Data,
                             device: String? = nil,
                             padGate: Bool = true) async throws -> (header: String, ciphertext: String, x3dh: V2.X3DHHeader?) {
        // `loadOrEstablish` may hit the network (X3DH bundle fetch), so it stays
        // OUTSIDE the lock — holding a cross-process lock across a network call is
        // how you turn a 40 ms hold into a five-second one and make the other
        // process time out.
        _ = try await loadOrEstablish(peer: peer, device: device)
        // __V2_PADDING_2026_11_09__ AFTER loadOrEstablish: a first contact records the
        // peer's bundle caps for this slot there, before we decide.
        let sealed = V2Padding.shouldPad(padGate: padGate,
                                         slotCapable: V2PaddingPeers.slotCapable(peer: peer, device: device))
            ? V2Padding.pad(plaintext) : plaintext
        let peer = Self.slot(peer, device: device)

        // 🔒 __RATCHET_RMW_2026_08_18__ Load, advance, persist — one atomic unit
        // across processes. OSHIShare performs the SAME sequence for the same peer,
        // and two interleaved copies mean the second save discards the first one's
        // chain advance: the recipient then cannot authenticate the next message,
        // permanently, for that session.
        //
        // The record is re-read INSIDE the lock on purpose. The copy returned above
        // was read before we held it and may already be stale.
        return try store.withPeerSession(peer) {
            guard var record = try store.loadSession(peerUserKey: peer) else {
                throw RouterError.sessionVanishedUnderLock(peer: peer)
            }
            let state = record.ratchet.toRatchetState()
            let (header, ciphertext) = try OSHICryptoV2.ratchetEncrypt(
                state, plaintext: sealed, associatedData: associatedData)

            // Persist the advanced state; the X3DH header rides ONLY the first message.
            record.ratchet = .init(state)
            let x3dh = record.pendingInitiatorHeader
            record.pendingInitiatorHeader = nil
            try store.saveSession(record)

            return (encodeHeaderB64(header), ciphertext.base64EncodedString(), x3dh)
        }
    }

    /// __V2_PADDING_2026_11_09__ Every v2 receive comes through here: the ratchet
    /// opens the envelope (`decrypt1to1Raw`, unchanged), THEN the padding is stripped.
    /// Outside the raw function on purpose — a padding error thrown inside it would
    /// be taken for a broken session and trigger the RATCHET-H1 re-establishment.
    private func decrypt1to1(sender: String,
                             header: String,
                             ciphertext: String,
                             x3dh: V2.X3DHHeader?,
                             slot: String? = nil,
                             deviceMaterial: Bool = false) async throws -> Data {
        let raw = try await decrypt1to1Raw(sender: sender, header: header, ciphertext: ciphertext,
                                           x3dh: x3dh, slot: slot, deviceMaterial: deviceMaterial)
        // __MEDIA_PADDING_2026_11_10__ A new X3DH session may be another (older) install:
        // forget what we knew of its media capability. A message never proves bit 8 —
        // only a bundle, a device list or a padded FILE from that slot (`parse`) does.
        V2MediaPaddingPeers.noteInbound(slot: slot ?? sender, paddedFile: false, carriedX3DH: x3dh != nil)
        switch V2Padding.unpad(raw) {
        case .plain(let pt):
            V2PaddingPeers.noteInbound(slot: slot ?? sender, padded: false, carriedX3DH: x3dh != nil)
            return pt
        case .padded(let pt):
            V2PaddingPeers.noteInbound(slot: slot ?? sender, padded: true, carriedX3DH: x3dh != nil)
            return pt
        case .malformed:
            throw RouterError.malformedPadding
        }
    }

    private func decrypt1to1Raw(sender: String,
                                header: String,
                                ciphertext: String,
                                x3dh: V2.X3DHHeader?,
                                slot: String? = nil,
                                deviceMaterial: Bool = false) async throws -> Data {
        guard let ctData = Data(base64Encoded: ciphertext) else {
            throw RouterError.malformedFileKey
        }
        let hdr = try decodeHeader(header)
        // __PER_DEVICE_MAILBOX_2026_09_23__ `sender` stays the IDENTITY (the X3DH TOFU check
        // compares against it); `key` is the session slot; per-device copies answer X3DH
        // with THIS device's own pre-keys.
        let key = slot ?? sender
        let ownStore = deviceMaterial ? deviceStore : store

        // Attempt decryption against a session record, persisting the advanced
        // ratchet + consuming the OPK only on success. Factored out so we can
        // retry with a freshly re-established session (RATCHET-H1 recovery).
        func attempt(_ record: V2SessionStore.SessionRecord,
                     opkToConsume: String?) throws -> Data {
            let state = record.ratchet.toRatchetState()
            let plaintext = try OSHICryptoV2.ratchetDecrypt(
                state, header: hdr, ciphertext: ctData, associatedData: associatedData)
            // [OTPK-H2] Burn the one-time pre-key only now that the first
            // message AEAD-verified — a forged header can't deplete the pool.
            if let opkId = opkToConsume {
                _ = try? ownStore.consumeOneTimePreKey(keyId: opkId)
            }
            var advanced = record
            advanced.ratchet = .init(state)
            try store.saveSession(advanced)
            return plaintext
        }

        // 🔒 __RATCHET_RMW_2026_08_18__ The whole load → decrypt → persist sequence
        // is one atomic unit across processes, for the same reason the send side is:
        // OSHIShare advances the SENDING chain of this very record, and an
        // interleaved save from either side discards the other's advance.
        //
        // Only the FAST PATH is inside the lock. The recovery branch below
        // re-establishes a session and can touch the network, and holding a
        // cross-process lock across that would stall the other process for seconds.
        // The recovery `attempt` takes the lock again on its own — which is safe
        // precisely because it runs after this block has released it, never nested
        // inside it. (`withPeerSession` is NOT reentrant; nesting deadlocks.)
        if let existing = try store.withPeerSession(key, { try store.loadSession(peerUserKey: key) }) {
            do {
                return try store.withPeerSession(key) { try attempt(existing, opkToConsume: nil) }
            } catch {
                // [RATCHET-H1] Existing session can't open this message. If it
                // carries an X3DH header, the peer has started a NEW session
                // (reinstall / key restore / state loss on their side). Accept
                // it: re-establish as responder from the header and retry, which
                // replaces our stale record and heals the conversation. Without
                // this, one desync wedges every future message forever. If there
                // is no header (genuine ratchet break), surface the error so the
                // caller can decide (the pull loop acks past it rather than
                // blocking the whole queue).
                // __V2_FAILURE_IS_TYPED_2026_08_28__ Le rethrow nu perdait la
                // seule chose que cet endroit sache et que personne d'autre ne
                // peut redécouvrir: qu'une session EXISTAIT et qu'aucun
                // en-tête ne viendra la remplacer. `underlying` garde le
                // diagnostic d'origine mot pour mot.
                guard let x3dh else {
                    throw RouterError.ratchetBreak(peer: String(sender.prefix(12)),
                                                   underlying: String(describing: error))
                }
                let fresh = try establishResponderSession(sender: sender, x3dh: x3dh, slot: key, deviceMaterial: deviceMaterial)
                OshiLog.messaging.info("🔄 [V2] session with \(sender.prefix(8))… re-established from inbound X3DH (recovery)")
                return try store.withPeerSession(key) { try attempt(fresh, opkToConsume: x3dh.oneTimePreKeyId) }
            }
        } else if let x3dh {
            let fresh = try establishResponderSession(sender: sender, x3dh: x3dh, slot: key, deviceMaterial: deviceMaterial)
            return try store.withPeerSession(key) { try attempt(fresh, opkToConsume: x3dh.oneTimePreKeyId) }
        } else {
            throw RouterError.noSessionForDecrypt(peer: sender)
        }
    }

    // MARK: - Public send

    /// Send a plaintext string to a peer over the v2 ratchet. Returns the relay
    /// msgIds it accepted.
    ///
    /// `msgId`: pass the app-level stable message id so the SAME logical message
    /// carries ONE id across every transport (mesh / IPFS / v2). The receiver
    /// persists a v2 message under this id, and its cross-transport dedup is by
    /// id — without this a message delivered over both mesh and v2 arrives with
    /// two different ids and shows as a duplicate bubble. Defaults to a fresh
    /// UUID for callers that have no app id (e.g. internal control traffic).
    @discardableResult
    /// - Parameter originalTimestamp: date A AFFICHER chez le destinataire.
    ///   __RESEND_TIMESTAMP_2026_08_28__ Sans ce parametre, `V2.Envelope` prend
    ///   `Date()` par defaut et le destinataire horodate la bulle a l'instant du
    ///   RENVOI: un « je suis en bas » du lundi 9 h reapparaissait jeudi 18 h,
    ///   au mauvais endroit du fil et avec un sens different. On ne renvoie pas
    ///   un message pour en fabriquer un autre.
    /// - Parameters:
    ///   - accountOnly: __PER_DEVICE_MAILBOX_2026_09_23__ force the account envelope even in
    ///     device mode (the ratchet-heal signal repairs the LEGACY session, never a device one).
    ///   - selfCopy: also send a `sent-copy` to this account's other devices (user-visible
    ///     messages only; the caller decides).
    func sendText(peer: String, _ text: String, msgId: String? = nil,
                  originalTimestamp: Date? = nil,
                  accountOnly: Bool = false,
                  selfCopy: Bool = false) async throws -> [String] {
        if !accountOnly, deviceContext != nil {
            let id = msgId ?? UUID().uuidString
            let ts = V2EnvelopeTimestamp.ms(original: originalTimestamp)
            let copy = selfCopy ? Self.sentCopyPayload(peer: peer, message: text, msgId: id, sentAtMs: ts) : nil
            return try await sendFanout([FanoutTarget(peer: peer, type: .oneToOne, groupId: nil, msgId: id,
                                                      plaintext: Data(text.utf8))],
                                        selfCopy: copy.map { (id, $0) }, ts: ts).accepted
        }
        // __V2_PENDING_ENVELOPE_2026_10_09__ Encrypt ONCE per message id.
        //
        // This used to encrypt on every call, so a retry of the same app message
        // id produced a different ciphertext and the relay answered 409
        // `message-id-conflict` — burning the v2 lane for that message for the
        // 30 days its receipt is retained. See `V2PendingEnvelopeStore`.
        let plaintext = Data(text.utf8)
        let id = msgId ?? UUID().uuidString
        let me = crypto.userKey
        let env: V2.Envelope
        if let cached = try V2PendingEnvelopeStore.envelope(from: me, to: peer,
                                                           msgId: id, plaintext: plaintext) {
            OshiLog.messaging.info("♻️ [V2] reusing the stored envelope for \(id.prefix(8))… — a second encrypt would 409")
            env = cached
        } else {
            let (header, ciphertext, x3dh) = try await encrypt1to1(peer: peer, plaintext: plaintext)
            env = V2.Envelope(
                msgId: id,
                from: me,
                to: peer,
                type: .oneToOne,
                x3dh: x3dh,
                header: header,
                ciphertext: ciphertext,
                ts: V2EnvelopeTimestamp.ms(original: originalTimestamp)
            )
            // Stored BEFORE the POST: a process death between the ratchet step
            // and the network would otherwise lose the only ciphertext this id
            // is ever allowed to carry.
            V2PendingEnvelopeStore.put(from: me, to: peer, msgId: id,
                                       plaintext: plaintext, envelope: env)
        }
        let accepted = try await client.sendEnvelope(env)
        // Only once the relay has it. A throw leaves the record in place so the
        // retry reuses this exact ciphertext.
        V2PendingEnvelopeStore.remove(from: me, to: peer, msgId: id)
        return accepted
    }

    /// Émet l'accusé de réparation vers `peer`, sur le canal des messages.
    ///
    /// PRÉCONDITION, et elle porte tout: la session locale avec `peer` doit
    /// AVOIR ÉTÉ EFFACÉE juste avant. C'est ce qui force `loadOrEstablish` à
    /// refaire X3DH, donc à poser `pendingInitiatorHeader`, donc à faire porter
    /// l'en-tête par cette enveloppe. Le pair l'adopte en la déchiffrant
    /// (RATCHET-H1) et les deux côtés repartent sur la même session neuve.
    ///
    /// L'accusé ne DEMANDE donc pas la réparation: il l'APPORTE. Envoyé sans
    /// avoir effacé, il repartirait sur la session cassée et ne réparerait
    /// rien — d'où la vérification explicite plutôt qu'un commentaire.
    ///
    /// L'identifiant porte `RatchetHealSignal.msgIdPrefix` en clair, pour que
    /// l'extension de notification puisse taire la bannière sans déchiffrer.
    ///
    /// - Parameter resend: les ids d'enveloppe que NOUS n'avons pas pu ouvrir
    ///   et que le pair est le seul à détenir encore. La liste voyage DANS le
    ///   chiffré (le relais jette tout champ d'enveloppe inconnu, et de toute
    ///   façon il n'a pas à savoir quels messages ont échoué). Vide — le cas
    ///   normal — la charge reste octet pour octet celle de la v1.
    @discardableResult
    func sendHealSignal(to peer: String, resend: [String] = []) async throws -> [String] {
        if store.hasSession(peerUserKey: peer) {
            throw RouterError.healWithLiveSession(peer: peer)
        }
        // __HEAL_V1_SAFETY_2026_08_28__ On ANNONCE toujours qu'on sait lire une
        // liste (via l'identifiant, que la v1 ignore), mais on n'en JOINT une
        // que si le pair l'a annonce avant nous. Sans ca, un pair en 1.0.39
        // afficherait notre JSON en clair dans sa conversation: son
        // reconnaisseur est une egalite stricte qu'aucune liste ne satisfait.
        return try await sendText(peer: peer,
                                  RatchetHealSignal.encode(resend: resend),
                                  msgId: RatchetHealSignal.makeCapableMsgId(),
                                  accountOnly: true)
    }

    /// Fan a group plaintext out to an EXPLICIT member list (the app's
    /// authoritative group roster), one ratchet-encrypted envelope per member
    /// that has a v2 bundle, sent as a single relay batch. Returns the member
    /// userKeys actually handled by v2 so the caller can Pinata-fall-back for
    /// the rest (older app versions). Uses the proven 1:1 ratchet per member —
    /// no group-key crypto, no epoch/admin state — so it inherits forward
    /// secrecy and the RATCHET-H1 recovery. The SAME app-level message id lives
    /// inside `text`, so per-recipient envelope msgIds never cause duplicates
    /// (the group store dedups on that inner id). Returns ([], []) on batch
    /// failure so the caller delivers to everyone via the legacy path.
    @discardableResult
    func sendGroupText(groupId: String, members: [String], _ text: String,
                       ts: Int64? = nil,
                       selfCopy: (msgId: String, plaintext: Data)? = nil) async -> (handled: [String], msgIds: [String]) {
        // __GROUP_E2E_V2_2026_09_23__ §2.1 `ts` = the message's own date (Unix ms); §7.2 one
        // `sent-copy` per group message to this account's other devices (device mode only).
        let envTs = ts ?? Int64(Date().timeIntervalSince1970 * 1000)
        // __V2_PADDING_2026_11_09__ one old member ⇒ no member's copy is padded.
        let padGate = V2PaddingPeers.groupAllows(members: members, me: crypto.userKey)
        // __PER_DEVICE_MAILBOX_2026_09_23__ device mode: the same member set, one copy per
        // member DEVICE (one msgId per member, shared by its copies), one batch.
        if deviceContext != nil {
            let me = crypto.userKey
            var targets: [FanoutTarget] = []
            for member in members where member != me {
                guard case .available = await capabilities(peerUserKey: member) else { continue }
                targets.append(FanoutTarget(peer: member, type: .group, groupId: groupId, msgId: UUID().uuidString,
                                            plaintext: Data(text.utf8), padGate: padGate))
            }
            guard !targets.isEmpty else { return ([], []) }
            do {
                let r = try await sendFanout(targets, selfCopy: selfCopy, ts: envTs)
                return (r.handled, r.accepted)
            } catch {
                return ([], [])
            }
        }
        var envelopes: [V2.Envelope] = []
        var handled: [String] = []
        let me = crypto.userKey
        for member in members where member != me {
            guard case .available = await capabilities(peerUserKey: member) else { continue }
            do {
                let (header, ciphertext, x3dh) = try await encrypt1to1(peer: member, plaintext: Data(text.utf8), padGate: padGate)
                envelopes.append(V2.Envelope(
                    msgId: UUID().uuidString,
                    from: me,
                    to: member,
                    type: .group,
                    groupId: groupId,
                    x3dh: x3dh,
                    header: header,
                    ciphertext: ciphertext,
                    ts: envTs))
                handled.append(member)
            } catch {
                continue   // this member falls back to legacy
            }
        }
        guard !envelopes.isEmpty else { return ([], []) }
        do {
            let ids = try await client.sendBatch(envelopes)
            return (handled, ids)
        } catch {
            return ([], [])   // batch failed → caller uses legacy for everyone
        }
    }

    /// Encrypt a file, upload its (already-AEAD-sealed) chunks to the blob store,
    /// then send the key material as an ordinary ratchet message. Returns relay
    /// msgIds. Enforces the 50 MB cap via the blob client.
    /// `mediaType`/`isViewOnce` are OSHI media semantics carried opaquely inside
    /// the E2E file-key message so the receiver rebuilds the exact bubble.
    @discardableResult
    func sendFile(peer: String, data: Data, filename: String, mime: String,
                  mediaType: String? = nil, isViewOnce: Bool = false,
                  msgId: String? = nil,
                  reveal: RevealSendContext? = nil,
                  selfCopy: Bool = false,
                  videoNote: VideoNoteMetadata? = nil,
                  originalTimestamp: Date? = nil) async throws -> [String] {
        // __RESEND_KEEPS_ORDER_2026_11_18__ The envelope `ts` is the message's OWN
        // date, not the moment this attempt runs: a photo resent after a block or a
        // captive portal must land in the receiver's thread where it was written.
        let envTsMs = V2EnvelopeTimestamp.ms(original: originalTimestamp)
        // Enforce the user-facing PLAINTEXT limit (200 MB) before encryption. Any
        // file FORMAT is allowed — bytes are opaque from here on. (The blob
        // client's own ceiling is on ciphertext, which is slightly larger; this is
        // the clean user-facing check.)
        guard data.count <= V2.maxPlaintextBytes else {
            throw V2.ClientError.blobTooLarge(size: data.count, max: V2.maxPlaintextBytes)
        }

        let blobId: String
        let fileKeyB64: String
        let fileNonceB64: String
        let chunkCount: Int
        let manifestB64: String

        // 🔧 RESUME FIX: an interrupted send used to restart from byte 0 on every
        // attempt, because each call drew a fresh file key AND a fresh reservation.
        // MECHANISM: `sendId` keys a persisted record of {blobId, fileKey,
        // fileNonce}. On a retry we re-encrypt with the SAME key material — which
        // yields byte-identical chunks, since chunk nonces are derived
        // deterministically from the file nonce — and hand the old blobId to the
        // uploader, which then tops up only what `/status` still reports missing.
        // The digest binds the record to these exact bytes so key material can
        // never be reused across different plaintexts. No record / no match /
        // expired blob ⇒ nil ⇒ exactly the previous behaviour.
        let sendId = msgId ?? UUID().uuidString
        let plaintextDigest = V2SendResumeStore.digest(data)
        var resume = V2SendResumeStore.record(for: sendId,
                                              recipientKey: peer,
                                              plaintextSHA256: plaintextDigest)
        // __MEDIA_PADDING_2026_11_10__ Pad only when EVERY device that will open this
        // blob reads padded media; the decision is part of what binds a resume
        // (same key + nonce over a different plaintext would be GCM nonce reuse).
        let mediaPadGate = await mediaPaddingAllowed(recipients: [peer], includeOwnDevices: selfCopy)
        let padMedia: Bool
        switch V2SendResumeStore.resolveMediaPadding(record: resume, gate: mediaPadGate, plaintextSize: data.count) {
        case .resume(let pad):
            padMedia = pad
        case .fresh(let pad):
            padMedia = pad
            if resume != nil { V2SendResumeStore.remove(msgId: sendId); resume = nil }
        }
        let reuseKey = resume.flatMap { Data(base64Encoded: $0.fileKeyB64) }
        let reuseNonce = resume.flatMap { Data(base64Encoded: $0.fileNonceB64) }
        // Both-or-neither: OSHICryptoV2 rejects a half-reused pair, and a record
        // whose base64 failed to decode must not silently become a fresh key
        // paired with an old blobId.
        let canResume = (reuseKey != nil && reuseNonce != nil)
        let resumeBlobId = canResume ? resume?.blobId : nil
        if canResume, let r = resume {
            OshiLog.messaging.info("↩️ [V2] resuming send \(sendId.prefix(8))… into blob \(r.blobId.prefix(12))…")
        }
        // Built per-branch once the (possibly reused) key material is known, since
        // that is what a later attempt must replay.
        func makeOnReserved(fileKey: Data, fileNonce: Data, chunkCount: Int) -> @Sendable (String) -> Void {
            { newBlobId in
                V2SendResumeStore.put(msgId: sendId,
                                      recipientKey: peer,
                                      plaintextSHA256: plaintextDigest,
                                      blobId: newBlobId,
                                      fileKey: fileKey,
                                      fileNonce: fileNonce,
                                      chunkCount: chunkCount,
                                      mediaPadded: padMedia)
            }
        }

        if data.count >= V2.streamingSendThreshold {
            // 🔧 STREAMING SEND (200 MB, 2026-07-19): encrypt to per-chunk files on
            // DISK and upload them one at a time, so peak RAM ≈ one 2 MiB chunk
            // instead of ~2× the file — materialising the whole ciphertext as a
            // `[Data]` is what jetsam-kills a 200 MB send. The produced blob is
            // byte-identical to the in-memory path (encryptFileStreaming ==
            // encryptFile, unit-verified), so the receiver's streaming download +
            // decrypt path is unchanged.
            let work = FileManager.default.temporaryDirectory
                .appendingPathComponent("v2send-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: work) }
            let plainURL = work.appendingPathComponent("plain.bin")
            try data.write(to: plainURL, options: .atomic)
            let chunkDir = work.appendingPathComponent("chunks", isDirectory: true)
            let enc = try OSHICryptoV2.encryptFileStreaming(
                inputURL: plainURL,
                outputDir: chunkDir,
                meta: OSHICryptoV2.FileMeta(filename: filename, mime: mime),
                reuseFileKey: reuseKey,
                reuseFileNonce: reuseNonce,
                padMedia: padMedia)
            blobId = try await client.uploadBlobFromDisk(recipientKey: peer,
                                                         chunkURLs: enc.chunkURLs,
                                                         useBackground: true,
                                                         resumeBlobId: resumeBlobId,
                                                         onReserved: makeOnReserved(fileKey: enc.fileKey,
                                                                                    fileNonce: enc.fileNonce,
                                                                                    chunkCount: enc.chunkCount))
            fileKeyB64 = enc.fileKey.base64EncodedString()
            fileNonceB64 = enc.fileNonce.base64EncodedString()
            chunkCount = enc.chunkCount
            manifestB64 = enc.manifest.base64EncodedString()
        } else {
            let encrypted = try OSHICryptoV2.encryptFile(
                bytes: data,
                meta: OSHICryptoV2.FileMeta(filename: filename, mime: mime),
                reuseFileKey: reuseKey,
                reuseFileNonce: reuseNonce,
                padMedia: padMedia)
            // FOREGROUND-COUPLING FIX (2026-07-27) said useBackground: true always.
            // `URLSession.shared` is suspended the instant the user leaves the app,
            // so a photo upload froze until they came back — the "you have to keep
            // the chat open" report. That reasoning is right for a LARGE transfer.
            //
            // 🔧 FIX (2026-08-11, "a 502 KB photo took nearly a minute on Wi-Fi"):
            // applying it to EVERY size was wrong, and this codebase had already
            // said so once. MessageManager's own note from 1.0.15 reads: "a
            // deferred background session was the wrong tool: it added the
            // system's start/response latency to every send, producing a 'hangs at
            // ~90 %, fails, works on manual retry' pattern on nearly every
            // upload." The 07-27 commit re-introduced exactly that, and its own
            // message opens with "A 1.8 MB photo took ~60 s".
            //
            // MEASURED against the live host: reserve ~0.15 s, a 502 KB chunk PUT
            // 0.87–1.14 s, blobStatus ~0.1 s, commit ~0.1 s, push ~0.36 s — under
            // 2 s of actual work. The remaining ~55 s is `nsurlsessiond` deciding
            // when to run a discretionary-ish transfer, and it is not something
            // the app can measure or hurry.
            //
            // So pick per size. Small media goes foreground and lands in about a
            // second; anything big enough that a user might reasonably leave the
            // app mid-transfer keeps the background session and its suspend
            // survival. The send already holds a UIBackgroundTask assertion from
            // the moment of tap, and the V2 protocol is resumable, so a small send
            // interrupted by backgrounding resumes rather than restarting.
            let useBackgroundUpload = data.count >= Self.backgroundUploadMinBytes
            blobId = try await client.uploadBlob(recipientKey: peer,
                                                 chunks: encrypted.chunks,
                                                 useBackground: useBackgroundUpload,
                                                 resumeBlobId: resumeBlobId,
                                                 onReserved: makeOnReserved(fileKey: encrypted.fileKey,
                                                                            fileNonce: encrypted.fileNonce,
                                                                            chunkCount: encrypted.chunks.count))
            fileKeyB64 = encrypted.fileKey.base64EncodedString()
            fileNonceB64 = encrypted.fileNonce.base64EncodedString()
            chunkCount = encrypted.chunks.count
            manifestB64 = encrypted.manifest.base64EncodedString()
        }

        let keyMsg = V2FileKeyMessage(
            kind: V2FileKeyMessage.kindValue,
            blobId: blobId,
            fileKey: fileKeyB64,
            fileNonce: fileNonceB64,
            chunkCount: chunkCount,
            manifest: manifestB64,
            filename: filename,
            mime: mime,
            mediaType: mediaType,
            isViewOnce: isViewOnce ? true : nil,
            // __REVEAL_WIRE_2026_08_22__ `nil` tant qu'aucune révélation n'est
            // armée: le payload reste byte-identique pour tout le trafic actuel.
            revealId: reveal?.revealId,
            revealLayerIndex: reveal?.layerIndex,
            revealLayerCount: reveal?.layerCount,
            revealTotalMs: reveal?.totalMs,
            groupMessage: nil,
            // __VIDEO_NOTE_2026_09_24__ nil hors note vidéo → JSON inchangé.
            videoNote: videoNote == nil ? nil : true,
            durationMs: videoNote?.durationMs
        )
        let payload = try JSONEncoder().encode(keyMsg)
        let ids: [String]
        if deviceContext != nil {
            // __PER_DEVICE_MAILBOX_2026_09_23__ ONE blob (its recipient is the identity, which
            // every device of the peer — and every device of ours, as owner — may fetch), and
            // the key message encrypted once per device.
            let ts = envTsMs
            let copy = selfCopy ? Self.sentCopyPayload(peer: peer, message: String(decoding: payload, as: UTF8.self),
                                                       msgId: sendId, sentAtMs: ts) : nil
            ids = try await sendFanout([FanoutTarget(peer: peer, type: .oneToOne, groupId: nil, msgId: sendId, plaintext: payload)],
                                       selfCopy: copy.map { (sendId, $0) }, ts: ts).accepted
        } else {
            let (header, ciphertext, x3dh) = try await encrypt1to1(peer: peer, plaintext: payload)
            let env = V2.Envelope(
                msgId: sendId,
                from: crypto.userKey,
                to: peer,
                type: .oneToOne,
                x3dh: x3dh,
                header: header,
                ciphertext: ciphertext,
                ts: envTsMs
            )
            ids = try await client.sendEnvelope(env)
        }
        // Only NOW is the send genuinely durable (blob committed AND the key
        // message accepted by the relay). Dropping the record earlier would leave a
        // committed blob no retry could find its way back to; dropping it later
        // just keeps file key material on disk longer than needed.
        V2SendResumeStore.remove(msgId: sendId)
        return ids
    }

    /// Fan a group MEDIA message out over the V2 blob store — the same
    /// background, resumable transfer 1:1 media uses, with the same
    /// `V2SendResumeStore` ledger. Returns the members v2 delivered to, so the
    /// caller can Pinata-fall-back for the rest exactly like `sendGroupText`.
    ///
    /// ─── THE FAN-OUT DECISION: ONE BLOB PER MEMBER ────────────────────────────
    /// A 1:1 send reserves ONE blob for ONE `recipientKey`. A group has N members.
    /// The attractive option is one blob + N key envelopes — the file key already
    /// travels in the ratchet envelope, so N envelopes referencing one blobId is
    /// enough CRYPTOGRAPHICALLY. It is not enough for AUTHORISATION: the blob store
    /// stores exactly one `recipient` per reservation (`blob_store.js:315-326`) and
    /// `downloadChunk` gates on `isParty(rec, who)` = `who === rec.owner ||
    /// who === rec.recipient` (`blob_store.js:220`, called at `:416`). That check is
    /// NOT under `AUTH_MODE`, so even with auth off every member but one gets a 403.
    /// The server therefore ENFORCES single-recipient, and the honest implementation
    /// is N reservations.
    ///
    /// What we do NOT do is encrypt N times. One file key + one file nonce produce
    /// ONE set of ciphertext chunks (AES-GCM is deterministic and chunk nonces are
    /// `fileNonce || u32be(i)`), and those exact bytes are PUT into each member's
    /// reservation. So the cost is N× UPLINK BYTES and 1× crypto — and the uploads
    /// run SEQUENTIALLY, so the fan-out lengthens the transfer instead of
    /// multiplying the number of concurrent streams (the congestion pattern this
    /// whole consolidation exists to remove).
    ///
    /// The N× uplink is the price of the current server contract. The server change
    /// that removes it is small and is specified verbatim in
    /// `docs/MEDIA_TRANSFER_REFERENCE.md` §3.3 (accept `recipientKeys: [String]` at
    /// reserve, store a Set, widen `isParty`). Until it is deployed, note the
    /// break-even: the legacy Pinata group path uploads ONE envelope but amplifies
    /// every byte 5.62× through nested base64, so this path is CHEAPER on uplink up
    /// to ~5 recipients and more expensive above that — while being resumable,
    /// background-capable and delete-after-delivery at every N.
    ///
    /// ⚠️ COMPATIBILITY: a peer running a build older than this commit hits
    /// `guard groupId == nil else { continue }` in its own `MessageManager+V2` and
    /// SILENTLY DROPS a group file envelope. There is no capability/version field in
    /// the X3DH bundle to detect that (`V2ClientModels.swift:196` —
    /// `PreKeyBundleUpload` carries keys only). See the doc's Open Items.
    @discardableResult
    func sendGroupFile(groupId: String,
                       members: [String],
                       data: Data,
                       filename: String,
                       mime: String,
                       mediaType: String? = nil,
                       groupMessageB64: String,
                       msgId: String,
                       videoNote: VideoNoteMetadata? = nil) async -> (handled: [String], msgIds: [String]) {
        guard data.count <= V2.maxPlaintextBytes else {
            OshiLog.messaging.info("⚠️ [V2] group file \(data.count) B over the \(V2.maxPlaintextBytes) B cap — legacy fan-out")
            return ([], [])
        }
        let me = crypto.userKey
        let recipients = members.filter { $0 != me }
        guard !recipients.isEmpty else { return ([], []) }
        // __V2_PADDING_2026_11_09__ one old member ⇒ no member's key message is padded.
        let padGate = V2PaddingPeers.groupAllows(members: recipients, me: me)

        // ── Resume lookup, per member ─────────────────────────────────────────
        // The ledger is keyed per (message, recipient) because each member has its
        // OWN blobId. The file key material is identical across members, so the
        // first valid record supplies it for the whole fan-out; the digest binding
        // in `record(for:)` still refuses to hand it back for different bytes.
        let plaintextDigest = V2SendResumeStore.digest(data)
        var blobIdByMember: [String: String] = [:]
        var reuseKey: Data?
        var reuseNonce: Data?
        var records: [String: V2SendResumeStore.Record] = [:]
        var keyRecord: V2SendResumeStore.Record?
        for member in recipients {
            guard let r = V2SendResumeStore.record(
                for: V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: member),
                recipientKey: member,
                plaintextSHA256: plaintextDigest) else { continue }
            records[member] = r
            if reuseKey == nil,
               let k = Data(base64Encoded: r.fileKeyB64),
               let n = Data(base64Encoded: r.fileNonceB64) {
                reuseKey = k
                reuseNonce = n
                keyRecord = r
            }
        }
        // __MEDIA_PADDING_2026_11_10__ one file for every member ⇒ pad only when every
        // member's devices read padded media. The key record's padding decision binds
        // the resume; a member blob is continued only if it was cut from the SAME
        // encryption (same key, same padding) — anything else would top a blob up with
        // chunks of a different plaintext under the same (key, nonce).
        let mediaPadGate = await mediaPaddingAllowed(recipients: recipients, includeOwnDevices: false)
        let padMedia: Bool
        switch V2SendResumeStore.resolveMediaPadding(record: keyRecord, gate: mediaPadGate, plaintextSize: data.count) {
        case .resume(let pad):
            padMedia = pad
        case .fresh(let pad):
            padMedia = pad
            reuseKey = nil
            reuseNonce = nil
            records.removeAll()
        }
        for (member, r) in records
        where r.fileKeyB64 == keyRecord?.fileKeyB64 && r.fileNonceB64 == keyRecord?.fileNonceB64
            && (r.mediaPadded ?? false) == padMedia {
            blobIdByMember[member] = r.blobId
        }
        // Both-or-neither, same invariant as `sendFile`: an old blobId paired with a
        // FRESH key would upload chunks that don't match the ones already stored.
        if reuseKey == nil || reuseNonce == nil {
            reuseKey = nil
            reuseNonce = nil
            blobIdByMember.removeAll()
        } else {
            OshiLog.messaging.info("↩️ [V2] resuming group send \(msgId.prefix(8))… — \(blobIdByMember.count)/\(recipients.count) member blob(s) known")
        }

        // ── Encrypt ONCE ──────────────────────────────────────────────────────
        // Above the streaming threshold the ciphertext lives on disk (peak RAM ≈ one
        // chunk); below it, in memory. Either way it is produced exactly once and
        // every member's reservation receives those same bytes.
        let work = FileManager.default.temporaryDirectory
            .appendingPathComponent("v2groupsend-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: work) }

        var chunkURLs: [URL] = []
        var chunksInMemory: [Data] = []
        let fileKey: Data, fileNonce: Data, manifest: Data, chunkCount: Int
        do {
            if data.count >= V2.streamingSendThreshold {
                try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
                let plainURL = work.appendingPathComponent("plain.bin")
                try data.write(to: plainURL, options: .atomic)
                let enc = try OSHICryptoV2.encryptFileStreaming(
                    inputURL: plainURL,
                    outputDir: work.appendingPathComponent("chunks", isDirectory: true),
                    meta: OSHICryptoV2.FileMeta(filename: filename, mime: mime),
                    reuseFileKey: reuseKey,
                    reuseFileNonce: reuseNonce,
                    padMedia: padMedia)
                chunkURLs = enc.chunkURLs
                fileKey = enc.fileKey; fileNonce = enc.fileNonce
                manifest = enc.manifest; chunkCount = enc.chunkCount
            } else {
                let enc = try OSHICryptoV2.encryptFile(
                    bytes: data,
                    meta: OSHICryptoV2.FileMeta(filename: filename, mime: mime),
                    reuseFileKey: reuseKey,
                    reuseFileNonce: reuseNonce,
                    padMedia: padMedia)
                chunksInMemory = enc.chunks
                fileKey = enc.fileKey; fileNonce = enc.fileNonce
                manifest = enc.manifest; chunkCount = enc.chunks.count
            }
        } catch {
            OshiLog.messaging.info("⚠️ [V2] group file encrypt failed: \(error) — legacy fan-out")
            return ([], [])
        }

        let fileKeyB64 = fileKey.base64EncodedString()
        let fileNonceB64 = fileNonce.base64EncodedString()
        let manifestB64 = manifest.base64EncodedString()

        // ── Upload per member, SEQUENTIALLY, then one relay batch ─────────────
        var envelopes: [V2.Envelope] = []
        var handled: [String] = []
        var deviceTargets: [FanoutTarget] = []
        for member in recipients {
            guard case .available = await capabilities(peerUserKey: member) else { continue }
            let ledgerId = V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: member)
            let onReserved: @Sendable (String) -> Void = { newBlobId in
                V2SendResumeStore.put(msgId: ledgerId,
                                      recipientKey: member,
                                      plaintextSHA256: plaintextDigest,
                                      blobId: newBlobId,
                                      fileKey: fileKey,
                                      fileNonce: fileNonce,
                                      chunkCount: chunkCount,
                                      mediaPadded: padMedia)
            }
            do {
                let blobId: String
                if chunkURLs.isEmpty {
                    blobId = try await client.uploadBlob(recipientKey: member,
                                                         chunks: chunksInMemory,
                                                         useBackground: true,
                                                         resumeBlobId: blobIdByMember[member],
                                                         onReserved: onReserved)
                } else {
                    blobId = try await client.uploadBlobFromDisk(recipientKey: member,
                                                                 chunkURLs: chunkURLs,
                                                                 useBackground: true,
                                                                 resumeBlobId: blobIdByMember[member],
                                                                 onReserved: onReserved)
                }
                let keyMsg = V2FileKeyMessage(
                    kind: V2FileKeyMessage.kindValue,
                    blobId: blobId,
                    fileKey: fileKeyB64,
                    fileNonce: fileNonceB64,
                    chunkCount: chunkCount,
                    manifest: manifestB64,
                    filename: filename,
                    mime: mime,
                    mediaType: mediaType,
                    isViewOnce: nil,
                    // Pas de révélation en GROUPE: la planification est par
                    // destinataire (elle démarre à l'arrivée), et un groupe la
                    // ferait démarrer à N moments différents. Hors périmètre,
                    // explicitement, plutôt que par omission.
                    revealId: nil,
                    revealLayerIndex: nil,
                    revealLayerCount: nil,
                    revealTotalMs: nil,
                    groupMessage: groupMessageB64,
                    videoNote: videoNote == nil ? nil : true,
                    durationMs: videoNote?.durationMs)
                let payload = try JSONEncoder().encode(keyMsg)
                if deviceContext != nil {
                    // __PER_DEVICE_MAILBOX_2026_09_23__ encrypted per device in the batch below.
                    deviceTargets.append(FanoutTarget(peer: member, type: .group, groupId: groupId,
                                                      msgId: UUID().uuidString, plaintext: payload, padGate: padGate))
                    handled.append(member)
                    continue
                }
                let (header, ciphertext, x3dh) = try await encrypt1to1(peer: member, plaintext: payload, padGate: padGate)
                envelopes.append(V2.Envelope(
                    msgId: UUID().uuidString,
                    from: me,
                    to: member,
                    type: .group,
                    groupId: groupId,
                    x3dh: x3dh,
                    header: header,
                    ciphertext: ciphertext))
                handled.append(member)
            } catch {
                // This member falls back to legacy. Its ledger record STAYS, so a
                // later retry tops up its partial blob instead of restarting.
                OshiLog.messaging.info("⚠️ [V2] group blob upload failed for \(member.prefix(8))…: \(error)")
                continue
            }
        }
        if !deviceTargets.isEmpty {
            do {
                let r = try await sendFanout(deviceTargets, selfCopy: nil, ts: Int64(Date().timeIntervalSince1970 * 1000))
                for member in handled {
                    V2SendResumeStore.remove(msgId: V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: member))
                }
                return (r.handled, r.accepted)
            } catch {
                OshiLog.messaging.info("⚠️ [V2] group media batch (devices) rejected: \(error) — legacy fan-out")
                return ([], [])
            }
        }
        guard !envelopes.isEmpty else { return ([], []) }
        do {
            let ids = try await client.sendBatch(envelopes)
            // Only now is every member's send durable (blob committed AND the key
            // envelope accepted). Same ordering rule as `sendFile`.
            for member in handled {
                V2SendResumeStore.remove(msgId: V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: member))
            }
            OshiLog.messaging.info("✅ [V2] group media: \(handled.count)/\(recipients.count) member blob(s), \(chunkCount) chunk(s) each")
            return (handled, ids)
        } catch {
            // Batch rejected → nobody got the key material. Keep every ledger record
            // so the retry resumes into the blobs already uploaded.
            OshiLog.messaging.info("⚠️ [V2] group media batch rejected: \(error) — legacy fan-out")
            return ([], [])
        }
    }

    /// Fan a plaintext out to a group and send the batch in one relay request.
    @discardableResult
    func sendGroupText(groupId: String, _ text: String) async throws -> [String] {
        let envelopes = try await group.encryptGroupMessage(groupId: groupId, plaintext: Data(text.utf8))
        guard !envelopes.isEmpty else { return [] }
        return try await client.sendBatch(envelopes)
    }

    // MARK: - Public receive

    /// Decrypt every envelope in a pull result, resiliently. Callers can `ack`
    /// `pull.maxSeq` once the returned messages are durably stored.
    /// Decrypt a pull, skipping envelopes already consumed.
    ///
    /// 🔧 FIX (2026-08-08): `alreadySeen` exists because dedup used to run in the
    /// CALLER, after every envelope had already been ratcheted. The pull is
    /// non-destructive — an envelope is re-delivered until its ack lands — so a
    /// message whose ack failed came back and was decrypted a SECOND time. That
    /// second attempt cannot succeed: `trySkipped` REMOVES the message key when
    /// it uses it (OSHICryptoV2.swift:421), and the ratchet has since advanced.
    /// It therefore landed in `.failed`, burned a retry, and held the ack — the
    /// exact loop the ack was waiting to break.
    ///
    /// Filtering first also makes the operation idempotent, which is the
    /// property any two-reader design (a notification extension, a second
    /// device) has to start from.
    func receiveAndDecrypt(_ pull: V2.PullResult,
                           alreadySeen: Set<String> = []) async -> [V2IncomingMessage] {
        var out: [V2IncomingMessage] = []
        out.reserveCapacity(pull.messages.count)
        for env in pull.messages {
            // Cheap, and BEFORE any ratchet operation.
            if alreadySeen.contains(env.msgId) { continue }
            // __PER_DEVICE_MAILBOX_2026_09_23__ decrypt rules of spec §3.4.
            if let ctx = deviceContext {
                if let to = env.toDevice {
                    // Addressed to one device. The server filters, but never trust it:
                    // someone else's copy is acked and dropped, NEVER repaired.
                    guard to == ctx.deviceId else {
                        CallFileLogger.shared.log("DIAG_V2_DEVICE_FOREIGN | id=\(env.msgId.prefix(8)) | reason=other-device")
                        continue
                    }
                    let slot = Self.slot(env.from, device: env.fromDevice ?? Self.noDeviceSlot)
                    do {
                        let plaintext: Data
                        if env.type == .group {
                            inboundDeviceSlot = (env.from, slot)
                            defer { inboundDeviceSlot = nil }
                            plaintext = try await decryptGroupEnvelope(env)
                        } else {
                            plaintext = try await decrypt1to1(sender: env.from, header: env.header, ciphertext: env.ciphertext,
                                                              x3dh: env.x3dh, slot: slot, deviceMaterial: true)
                        }
                        notePeerWriteMode(env)
                        out.append(parse(plaintext, env: env))
                    } catch {
                        out.append(.failed(from: env.from, msgId: env.msgId, seq: env.seq,
                                           error: String(describing: error),
                                           kind: V2DecryptFailureKind.classify(error),
                                           groupId: env.groupId))
                    }
                    continue
                }
                if !ctx.holdsAccountBundle {
                    // An account envelope from an old sender, on a device that does not hold
                    // the account bundle: try quietly; on failure DROP — no `.failed`, so no
                    // heal signal, no session reset, no error row. The holder has it, and
                    // devsync brings it here. (The one rule that prevents a reset storm.)
                    do {
                        let plaintext = env.type == .group
                            ? try await decryptGroupEnvelope(env)
                            : try await decrypt1to1(sender: env.from, header: env.header,
                                                    ciphertext: env.ciphertext, x3dh: env.x3dh)
                        notePeerWriteMode(env)
                        out.append(parse(plaintext, env: env))
                    } catch {
                        CallFileLogger.shared.log("DIAG_V2_DEVICE_FOREIGN | id=\(env.msgId.prefix(8)) | reason=account-envelope-not-holder")
                    }
                    continue
                }
            }
            do {
                let plaintext: Data
                if env.type == .group {
                    plaintext = try await decryptGroupEnvelope(env)
                } else {
                    plaintext = try await decrypt1to1(
                        sender: env.from,
                        header: env.header,
                        ciphertext: env.ciphertext,
                        x3dh: env.x3dh)
                }
                // __LEGACY_READER_2026_09_28__ Only an envelope that DECRYPTED counts (a forged
                // `from` cannot flip a peer's view); a no-op outside device mode.
                notePeerWriteMode(env)
                out.append(parse(plaintext, env: env))
            } catch {
                out.append(.failed(from: env.from, msgId: env.msgId, seq: env.seq,
                                   error: String(describing: error),
                                   kind: V2DecryptFailureKind.classify(error),
                                   groupId: env.groupId))
            }
        }
        return out
    }

    /// Download a file's blob chunks and decrypt them with a received key message.
    /// __GROUP_E2E_V2_2026_09_23__ Open a `type:"group"` envelope with the pairwise session
    /// (spec §3 step 1). The membership gate used to be `V2GroupSession.decryptIncomingGroup`,
    /// which requires the group in V2GroupSession's OWN store — a store nothing ever populates
    /// (its `createGroup`/control API has no caller). Every inbound v2 group envelope therefore
    /// failed with `noSuchGroup` on iPhones, classified `transient`. The ratchet already
    /// authenticates `from`; membership (unknown group ⇒ hold, removed ⇒ drop, sender ≠ author
    /// ⇒ drop) is enforced on the decoded message against the app's roster
    /// (`MessageGroupManager.receiveV2GroupMessage`). Decrypting first also keeps the
    /// pairwise chain in step instead of leaving a hole in it.
    private func decryptGroupEnvelope(_ env: V2.ReceivedEnvelope) async throws -> Data {
        guard let gid = env.groupId, !gid.isEmpty else {
            throw V2GroupSession.GroupError.notAMember(sender: env.from, groupId: "<none>")
        }
        return try await decryptFromMember(env.from, header: env.header, ciphertext: env.ciphertext, x3dh: env.x3dh)
    }

    func downloadAndDecryptFile(_ key: V2FileKeyMessage) async throws -> (data: Data, filename: String, mime: String) {
        guard let fileKey = Data(base64Encoded: key.fileKey),
              let fileNonce = Data(base64Encoded: key.fileNonce),
              let manifest = Data(base64Encoded: key.manifest) else {
            throw RouterError.malformedFileKey
        }
        let chunks = try await client.downloadBlob(blobId: key.blobId, chunkCount: key.chunkCount)
        let (bytes, _) = try OSHICryptoV2.decryptFile(
            fileKey: fileKey, fileNonce: fileNonce, chunks: chunks, manifest: manifest)
        return (bytes, key.filename, key.mime)
    }

    // MARK: - Parsing / header codec

    private func parse(_ plaintext: Data, env: V2.ReceivedEnvelope) -> V2IncomingMessage {
        if let key = try? JSONDecoder().decode(V2FileKeyMessage.self, from: plaintext),
           key.kind == V2FileKeyMessage.kindValue {
            // __MEDIA_PADDING_2026_11_10__ a PADDED file proves its sender's slot reads padded media.
            if let k = Data(base64Encoded: key.fileKey), let n = Data(base64Encoded: key.fileNonce),
               let m = Data(base64Encoded: key.manifest),
               OSHICryptoV2.manifestIsPadded(fileKey: k, fileNonce: n, manifest: m) {
                let slot = env.toDevice != nil ? Self.slot(env.from, device: env.fromDevice ?? Self.noDeviceSlot) : env.from
                V2MediaPaddingPeers.noteInbound(slot: slot, paddedFile: true, carriedX3DH: false)
            }
            return .file(from: env.from, msgId: env.msgId, seq: env.seq, key: key, groupId: env.groupId)
        }
        let text = String(data: plaintext, encoding: .utf8) ?? ""
        return .text(from: env.from, msgId: env.msgId, seq: env.seq, text: text, groupId: env.groupId)
    }

    private func encodeHeaderB64(_ header: OSHICryptoV2.Header) -> String {
        OSHICryptoV2.encodeHeader(header).base64EncodedString()
    }

    /// Inverse of `OSHICryptoV2.encodeHeader`: dh(32) || pn(u32be) || n(u32be).
    private func decodeHeader(_ b64: String) throws -> OSHICryptoV2.Header {
        guard let data = Data(base64Encoded: b64), data.count >= 40 else {
            throw RouterError.malformedHeader
        }
        let bytes = [UInt8](data)
        let dh = Data(bytes[0..<32])
        func u32(_ o: Int) -> UInt32 {
            (UInt32(bytes[o]) << 24) | (UInt32(bytes[o + 1]) << 16)
                | (UInt32(bytes[o + 2]) << 8) | UInt32(bytes[o + 3])
        }
        return OSHICryptoV2.Header(dh: dh, pn: u32(32), n: u32(36))
    }
}

// MARK: - V2GroupContext conformance

extension V2MessageRouter: V2GroupContext {

    /// Our v2 identity (base64 Ed25519 signing pub). Immutable + Sendable, so this
    /// is safe to expose without actor isolation.
    nonisolated var selfUserKey: String { crypto.userKey }

    func encryptForMember(_ memberUserKey: String,
                          plaintext: Data) async throws -> V2GroupSession.MemberCiphertext {
        // __V2_PADDING_2026_11_09__ `V2GroupSession` fans out without telling us the whole
        // member list, so the group rule cannot be evaluated: never padded.
        let (header, ciphertext, x3dh) = try await encrypt1to1(peer: memberUserKey, plaintext: plaintext, padGate: false)
        return V2GroupSession.MemberCiphertext(header: header, ciphertext: ciphertext, x3dh: x3dh)
    }

    func decryptFromMember(_ senderUserKey: String,
                           header: String,
                           ciphertext: String,
                           x3dh: V2.X3DHHeader?) async throws -> Data {
        // __PER_DEVICE_MAILBOX_2026_09_23__ a per-device GROUP copy reaches us through the
        // group membership gate; open it with the device session it was encrypted under.
        if let inbound = inboundDeviceSlot, inbound.sender == senderUserKey {
            return try await decrypt1to1(sender: senderUserKey, header: header, ciphertext: ciphertext, x3dh: x3dh,
                                         slot: inbound.slot, deviceMaterial: true)
        }
        return try await decrypt1to1(sender: senderUserKey, header: header, ciphertext: ciphertext, x3dh: x3dh)
    }

    /// Ed25519-sign group control bytes with our identity signing key. Reuses the
    /// same signing seam the transport layer uses (immutable → nonisolated).
    nonisolated func signControl(_ data: Data) throws -> (signature: Data, signerKeyB64: String) {
        let (signatureB64, signerKeyB64) = try crypto.signCanonical(data)
        guard let sig = Data(base64Encoded: signatureB64) else {
            throw RouterError.malformedFileKey
        }
        return (sig, signerKeyB64)
    }

    /// The Ed25519 signing key AUTHENTICATED against the X25519 identity `userKey`,
    /// used for group control signer-binding. We only return a binding we actually
    /// verified: the one learned as the X3DH INITIATOR, where `establishInitiator
    /// Session` fetched the peer's prekey bundle, checked `bundle.identityKey ==
    /// peer` (TOFU) and verified their signed prekey against `peerSigningKey`. On
    /// the RESPONDER path we never receive the peer's Ed25519 key — the X3DH header
    /// carries only X25519 material — so `peerSigningKey` there is NOT a trustworthy
    /// signing key; returning it would false-reject legitimate control. Hence the
    /// role gate: responder / no-session ⇒ nil ⇒ the group falls back to TOFU-pin.
    /// Immutable store reference ⇒ nonisolated, matching the other seam methods.
    nonisolated func boundSigningKey(forIdentity userKey: String) -> Data? {
        guard let record = try? store.loadSession(peerUserKey: userKey),
              record.role == .initiator,
              let signing = record.peerSigningKey else {
            return nil
        }
        return signing
    }
}

// MARK: - Send-resume ledger

/// The missing half of the V2 media send's resume story.
///
/// WHY THIS EXISTS
/// ---------------
/// The V2 blob protocol was already resumable ON THE WIRE: `reserve` allocates a
/// blobId, chunks are PUT by index, `/status` reports the still-missing set, and
/// `commit` finalises. What it had no memory of was the blobId. Every attempt —
/// a tap-to-retry, the reachability drain, or `MessageManager`'s retry queue
/// replayed after the app was killed mid-transfer — called `reserve` again, got a
/// NEW blobId, and re-uploaded every byte. A 180 MB video that died at 95 %
/// started again at 0 %.
///
/// Worse, `sendFile` drew a fresh random AES-GCM file key on every call, so even
/// if the old blobId HAD been remembered the new ciphertext would not have
/// matched the chunks already on the server.
///
/// This ledger closes both gaps with one record per outgoing message: the blobId
/// plus the file key material, so the next attempt re-encrypts the same plaintext
/// to byte-identical chunks (chunk nonces are `fileNonce || u32be(i)` and AES-GCM
/// is deterministic — see `OSHICryptoV2.encryptFile(reuseFileKey:reuseFileNonce:)`)
/// and tops up only the indices `/status` still reports missing.
///
/// This is NOT a new transfer mechanism. It is persistence for the one that
/// already exists. (There is a separate, older `ResumableUploader` — the legacy
/// Pinata path's own chunked protocol with its own `resumeMap`. GROUP media no
/// longer reaches it: it moved onto this ledger with `sendGroupFile`. Its one
/// remaining consumer is `MessageManager.uploadToIPFS:4363`, the 1:1 fallback for
/// peers with no v2 bundle — see `docs/MEDIA_TRANSFER_REFERENCE.md` §2.4.)
///
/// SAFETY INVARIANT
/// ----------------
/// Reusing an AES-GCM (key, nonce) pair across DIFFERENT plaintexts is a total
/// break of confidentiality and authenticity. Every record therefore carries a
/// SHA-256 of the plaintext it was created for, and `record(for:…)` refuses to
/// hand key material back unless that digest matches byte-for-byte. A mismatched,
/// missing, expired or corrupt record simply yields nil and the caller falls back
/// to a fresh key + fresh reservation — exactly today's behaviour. The failure
/// mode of this type is "no resume", never "bad crypto".
/// __V2_PENDING_ENVELOPE_2026_10_09__ One ciphertext per message id, forever.
///
/// THE BUG THIS EXISTS FOR. `sendText` encrypted on every call, so a second
/// attempt at the SAME app message id produced a DIFFERENT ciphertext. The relay
/// keys a receipt on `sha256([from, to, msgId])` and fingerprints the envelope
/// (`ServerVPS/v2/relay_v2.js:221`, `:424-432`); a second envelope under one id
/// with a different fingerprint is answered **HTTP 409 `message-id-conflict`**,
/// and that receipt is retained for 30 days (`:146`). So one retry permanently
/// burned the v2 lane for that message — it degraded to legacy IPFS rather than
/// vanishing, but it could never go by the relay again.
///
/// It needed no racing to happen: mesh timeout → `fallbackToIPFS` → `v2TrySend`
/// (attempt 1) → failure → retry queue → `retrySendViaCloud` → `v2TrySend`
/// (attempt 2, fresh ciphertext) → 409.
///
/// Android has had this since 2026-09-22 (`V2PendingEnvelopeStore.kt`); iOS had
/// no counterpart. This is the port.
///
/// WHY IT DIFFERS FROM `V2SendResumeStore`, which it otherwise mirrors: that one
/// returns nil on a digest mismatch so the caller starts fresh, which is correct
/// for a FILE (a fresh upload reserves a fresh blob and nothing is lost). For a
/// 1:1 text, "start fresh" means re-encrypt, which is precisely the 409. So a
/// mismatch here is an ERROR the caller must not paper over: the content behind
/// an id changed, and it needs a NEW id.
enum V2PendingEnvelopeStore {

    enum StoreError: LocalizedError {
        /// Same message id, different plaintext.
        case contentChanged
        var errorDescription: String? {
            "This message id already carries different content; use a new message id."
        }
    }

    /// Matched to `V2SendResumeStore.maxAge` and to the send retry queue's 24 h
    /// cutoff, so the two stores can never disagree about whether a message is
    /// still worth re-driving. Well inside the relay's 30-day receipt retention,
    /// which is the window the 409 actually covers.
    static let maxAge: TimeInterval = 24 * 60 * 60

    /// Bound on the ledger, mirroring Android's 500-record cap.
    static let maxRecords = 500

    struct Record: Codable, Sendable {
        /// SHA-256 of the PLAINTEXT. The binding that makes reuse safe.
        let plaintextSHA256: String
        /// The envelope exactly as it was first built, JSON-encoded.
        let envelopeJSON: Data
        let createdAt: Date
    }

    /// `(from, to, msgId)` — the relay's own receipt key, so this ledger is
    /// keyed by the same thing the 409 is keyed by.
    static func key(from: String, to: String, msgId: String) -> String {
        let joined = "\(from)\u{1F}\(to)\u{1F}\(msgId)"
        return SHA256.hash(data: Data(joined.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func digest(_ plaintext: Data) -> String {
        SHA256.hash(data: plaintext).map { String(format: "%02x", $0) }.joined()
    }

    /// The envelope already built for this id, or nil to build one.
    /// Throws `contentChanged` when the id is being reused for different bytes.
    static func envelope(from: String, to: String, msgId: String,
                         plaintext: Data) throws -> V2.Envelope? {
        let k = key(from: from, to: to, msgId: msgId)
        guard let r = load()[k] else { return nil }
        guard Date().timeIntervalSince(r.createdAt) < maxAge else {
            remove(from: from, to: to, msgId: msgId)
            return nil
        }
        guard r.plaintextSHA256 == digest(plaintext) else {
            // Deliberately NOT "start fresh" — see the type comment.
            throw StoreError.contentChanged
        }
        // A record we cannot decode must not become "no record": that would let
        // the caller re-encrypt, which is the whole failure this prevents.
        guard let env = try? JSONDecoder().decode(V2.Envelope.self, from: r.envelopeJSON) else {
            throw StoreError.contentChanged
        }
        return env
    }

    /// Remember the envelope BEFORE the POST, so a process death between
    /// encrypting and sending cannot lose it.
    static func put(from: String, to: String, msgId: String,
                    plaintext: Data, envelope: V2.Envelope) {
        guard let json = try? JSONEncoder().encode(envelope) else { return }
        var all = load()
        let k = key(from: from, to: to, msgId: msgId)
        if all[k] == nil && all.count >= maxRecords {
            // Drop the oldest rather than refuse the send.
            if let oldest = all.min(by: { $0.value.createdAt < $1.value.createdAt })?.key {
                all.removeValue(forKey: oldest)
            }
        }
        all[k] = Record(plaintextSHA256: digest(plaintext), envelopeJSON: json, createdAt: Date())
        save(all)
    }

    /// Drop the record once the relay has accepted it. Idempotent.
    static func remove(from: String, to: String, msgId: String) {
        var all = load()
        guard all.removeValue(forKey: key(from: from, to: to, msgId: msgId)) != nil else { return }
        save(all)
    }

    #if DEBUG
    static func removeAllForTesting() { try? FileManager.default.removeItem(at: fileURL) }
    #endif

    // MARK: Persistence — same location and reasoning as `V2SendResumeStore`.

    private static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("oshi_v2_pending_envelopes.json")
    }

    private static let lock = NSLock()

    private static func load() -> [String: Record] {
        lock.lock(); defer { lock.unlock() }
        guard let data = try? Data(contentsOf: fileURL),
              let all = try? JSONDecoder().decode([String: Record].self, from: data) else { return [:] }
        return all
    }

    private static func save(_ all: [String: Record]) {
        lock.lock(); defer { lock.unlock() }
        guard let data = try? JSONEncoder().encode(all) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

enum V2SendResumeStore {

    /// Kill switch. Defaults to ON: the digest binding above means a bad record
    /// degrades to a fresh upload rather than corrupting anything, so the safe
    /// value here is the one that stops re-uploading whole videos. Set
    /// `oshi_v2_send_resume` to `false` in UserDefaults to restore the previous
    /// always-start-from-zero behaviour exactly.
    static let defaultsKey = "oshi_v2_send_resume"
    static var enabled: Bool {
        if UserDefaults.standard.object(forKey: defaultsKey) == nil { return true }
        return UserDefaults.standard.bool(forKey: defaultsKey)
    }

    /// Records older than this are dropped. Matched to the send retry queue's own
    /// 24 h cutoff (`MessageManager.loadDeferredQueues`) so the two can never
    /// disagree about whether a message is still worth re-driving — and roughly to
    /// the blob store's TTL, past which the server has forgotten the reservation.
    static let maxAge: TimeInterval = 24 * 60 * 60

    struct Record: Codable, Sendable {
        let msgId: String
        let recipientKey: String
        /// SHA-256 of the PLAINTEXT file bytes. The nonce-reuse guard.
        let plaintextSHA256: String
        let blobId: String
        let fileKeyB64: String
        let fileNonceB64: String
        let chunkCount: Int
        let createdAt: Date
        /// __MEDIA_PADDING_2026_11_10__ Was this reservation's plaintext PADDED?
        /// nil = a record written before media padding existed = unpadded.
        var mediaPadded: Bool? = nil
    }

    /// __MEDIA_PADDING_2026_11_10__ How a send continues given its resume record.
    enum MediaPaddingResume: Equatable, Sendable {
        /// Reuse the record (key, nonce, blob) with THIS padding decision.
        case resume(pad: Bool)
        /// Drop the record; encrypt fresh with this decision.
        case fresh(pad: Bool)
    }

    /// The padding decision binds a resume:
    ///  • an UNPADDED reservation continues unpadded even if padding became
    ///    allowed meanwhile (every reader reads unpadded media);
    ///  • a PADDED reservation continues only while padding is still allowed;
    ///    otherwise it is abandoned and the file re-encrypted fresh, unpadded;
    ///  • the record's chunk count must be the one the (padded) size gives,
    ///    or the record is not trusted.
    static func resolveMediaPadding(record: Record?, gate: Bool, plaintextSize: Int,
                                    chunkSize: Int = OSHICryptoV2.fileChunkSize) -> MediaPaddingResume {
        guard let r = record else { return .fresh(pad: gate) }
        let recorded = r.mediaPadded ?? false
        if recorded && !gate { return .fresh(pad: false) }
        guard r.chunkCount == OSHICryptoV2.expectedChunkCount(plaintextSize: plaintextSize,
                                                              chunkSize: chunkSize, padded: recorded) else {
            return .fresh(pad: gate)
        }
        return .resume(pad: recorded)
    }

    /// Ledger key for ONE (message, recipient) pair.
    ///
    /// A 1:1 send stores its record under the bare app message id — one message,
    /// one blob. A GROUP send reserves ONE BLOB PER MEMBER (the blob store
    /// authorises a single `recipientKey`; see `sendGroupFile`), so the same
    /// logical message owns N blobIds and needs N records. Composing the key keeps
    /// them in the one JSON file, keeps the 24 h prune and the digest binding
    /// working unchanged, and cannot collide with a 1:1 record because a userKey is
    /// base64 and never contains `#`.
    ///
    /// The stored `recipientKey` is still the authoritative check — `record(for:)`
    /// compares it — so a malformed key here degrades to "no resume", never to a
    /// record being handed to the wrong peer.
    static func fanoutId(msgId: String, recipientKey: String) -> String {
        "\(msgId)#\(recipientKey)"
    }

    /// The plaintext digest, computed once by the caller and reused for both the
    /// lookup and the write.
    static func digest(_ plaintext: Data) -> String {
        SHA256.hash(data: plaintext).map { String(format: "%02x", $0) }.joined()
    }

    /// The usable record for this message, or nil (→ caller starts fresh).
    /// `plaintextSHA256` is verified against the stored digest before any key
    /// material is returned; see SAFETY INVARIANT above.
    static func record(for msgId: String,
                       recipientKey: String,
                       plaintextSHA256: String) -> Record? {
        guard enabled else { return nil }
        guard let r = load()[msgId] else { return nil }
        guard Date().timeIntervalSince(r.createdAt) < maxAge else {
            remove(msgId: msgId)
            return nil
        }
        guard r.recipientKey == recipientKey else { return nil }
        guard r.plaintextSHA256 == plaintextSHA256 else {
            // Same message id, different bytes: the record cannot be trusted and
            // its key material must never touch this plaintext.
            OshiLog.messaging.info("⚠️ [V2] resume record for \(msgId.prefix(8))… does not match the payload — starting fresh")
            remove(msgId: msgId)
            return nil
        }
        return r
    }

    /// Remember a reservation. Called the instant `reserve` returns — BEFORE the
    /// first chunk goes out — because the whole point is to survive a process that
    /// dies at an arbitrary point during the transfer.
    static func put(msgId: String,
                    recipientKey: String,
                    plaintextSHA256: String,
                    blobId: String,
                    fileKey: Data,
                    fileNonce: Data,
                    chunkCount: Int,
                    mediaPadded: Bool = false) {
        guard enabled else { return }
        var all = load()
        all[msgId] = Record(msgId: msgId,
                            recipientKey: recipientKey,
                            plaintextSHA256: plaintextSHA256,
                            blobId: blobId,
                            fileKeyB64: fileKey.base64EncodedString(),
                            fileNonceB64: fileNonce.base64EncodedString(),
                            chunkCount: chunkCount,
                            createdAt: Date(),
                            mediaPadded: mediaPadded)
        save(all)
        OshiLog.messaging.info("💾 [V2] resume point saved for \(msgId.prefix(8))… blob=\(blobId.prefix(12))… chunks=\(chunkCount)")
    }

    /// Drop the record once the message has actually been handed to the relay.
    /// Idempotent.
    static func remove(msgId: String) {
        var all = load()
        guard all.removeValue(forKey: msgId) != nil else { return }
        save(all)
    }

    #if DEBUG
    /// Repartir d'un registre VIDE. Réservé aux tests.
    ///
    /// Nécessaire parce que ce magasin est un fichier PARTAGÉ par tout le
    /// processus hôte: quand un run de tests meurt avant son `tearDown`, ses
    /// enregistrements restent sur disque et le run suivant démarre sur l'état
    /// de son prédécesseur. Supprimer une à une les entrées connues ne suffit
    /// pas — les ids d'un run mort ne sont plus connus de personne.
    static func removeAllForTesting() {
        try? FileManager.default.removeItem(at: fileURL)
    }
    #endif

    // MARK: Persistence

    /// Application Support, not Caches — iOS may purge Caches under disk pressure,
    /// which would silently disable resume exactly when the device is struggling.
    private static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("oshi_v2_send_resume.json")
    }

    private static func load() -> [String: Record] {
        guard let data = try? Data(contentsOf: fileURL),
              let all = try? JSONDecoder().decode([String: Record].self, from: data) else { return [:] }
        // Opportunistic prune so an abandoned send can't keep file key material on
        // disk forever.
        let cutoff = Date().addingTimeInterval(-maxAge)
        return all.filter { $0.value.createdAt > cutoff }
    }

    private static func save(_ all: [String: Record]) {
        guard let data = try? JSONEncoder().encode(all) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        // This file holds AES file keys for in-flight sends. Keep it off iCloud.
        var url = fileURL
        var vals = URLResourceValues(); vals.isExcludedFromBackup = true
        try? url.setResourceValues(vals)
    }
}

// MARK: - Per-device mailbox (__PER_DEVICE_MAILBOX_2026_09_23__)

/// __LEGACY_READER_2026_09_28__ Does the peer's live install read the LEGACY (account) view?
///
/// The relay shows an install that is not registered in device mode ONLY the account envelopes
/// (`relay_v2.js visibleTo`: `deviceId == null` ⇒ `isAccountItem(item)`); a per-device copy is
/// invisible to it. `sendFanout`, though, picks per-device copies from `GET /v2/keys/<peer>/devices`,
/// which lists every device that ever published a bundle — including a phone that was reinstalled
/// (its old deviceId stays bundled for 30 days, and the new install cannot register without an
/// approval from it), or a Mac registered next to an iPhone still on a build without device mode.
/// Every message from a device-mode sender then went to a device nobody reads: the relay accepted
/// it (✓), the peer never saw it, and the peer's own messages — account envelopes — still arrived.
/// "I receive, but what I send never arrives." (Android 1.6.26–1.6.32, fixed in bc0e3fc3.)
///
/// The evidence is on the wire: an install in device mode always writes with `fromDevice`
/// (per-device copies carry it), and a device copy always carries `toDevice`. An envelope with
/// NEITHER was written by an install on the account path, i.e. one that also READS the account
/// view. Until that peer writes a device copy again, we address it with the account envelope,
/// which every view of that account receives (its registered devices are owed it too: the holder
/// decrypts, the others drop it quietly, §3.4).
enum V2PeerReadView {
    /// True when the envelope came from an install that is not in device mode.
    nonisolated static func isLegacyWriter(toDevice: String?, fromDevice: String?) -> Bool {
        toDevice == nil && fromDevice == nil
    }
}

/// What the router needs to run in device mode. Supplied by the app (`V2DeviceMailbox`)
/// only while `/v2/config.device_mailbox_enabled` is true AND this install is registered.
struct V2DeviceContext: Sendable {
    /// This account's identity (normalised userKey).
    let me: String
    let deviceId: String
    let signer: V2Device.Signer
    /// True on the device whose published ACCOUNT bundle is live (normally the phone).
    /// Only that device decrypts account envelopes from old senders (§3.4).
    let holdsAccountBundle: Bool
}

extension V2MessageRouter {

    /// One logical message to one recipient identity. In device mode it becomes one
    /// envelope per recipient device (same `msgId`), or one account envelope when the
    /// recipient has no registered device.
    struct FanoutTarget: Sendable {
        let peer: String
        let type: V2.MessageType
        let groupId: String?
        let msgId: String
        let plaintext: Data
        /// __V2_PADDING_2026_11_09__ group-level padding rule (true for 1:1).
        var padGate: Bool = true
    }

    func setDeviceContext(_ ctx: V2DeviceContext?) {
        deviceContext = ctx
        if ctx == nil { peerDeviceCache.removeAll() }
    }

    var isDeviceModeActive: Bool { deviceContext != nil }

    // MARK: Peer read view (__LEGACY_READER_2026_09_28__)

    private var peerViewDefaults: UserDefaults {
        peerViewSuite.flatMap { UserDefaults(suiteName: $0) } ?? .standard
    }

    /// Keyed by a SHA-256 prefix, not the contact address in clear (UserDefaults is not sealed).
    private nonisolated static func peerViewKey(_ peer: String) -> String {
        let digest = SHA256.hash(data: Data(("oshi.peerview|" + V2Device.normalizeIdentity(peer)).utf8))
        return "oshi.v2.peerLegacyView." + digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// True while `peer`'s live install writes (and therefore reads) the account view.
    func peerReadsLegacy(_ peer: String) -> Bool {
        peerViewDefaults.bool(forKey: Self.peerViewKey(peer))
    }

    /// Remember, from an envelope that just DECRYPTED while this install is in device mode, whether
    /// its sender's install is on the account path. Persisted: after a relaunch the first message to
    /// that peer must still take the account path.
    private func notePeerWriteMode(_ env: V2.ReceivedEnvelope) {
        guard let ctx = deviceContext else { return }
        let peer = V2Device.normalizeIdentity(env.from)
        guard peer != ctx.me, peer != V2Device.normalizeIdentity(crypto.userKey) else { return }
        let legacy = V2PeerReadView.isLegacyWriter(toDevice: env.toDevice, fromDevice: env.fromDevice)
        let key = Self.peerViewKey(peer)
        let d = peerViewDefaults
        guard d.bool(forKey: key) != legacy else { return }
        if legacy { d.set(true, forKey: key) } else { d.removeObject(forKey: key) }
        CallFileLogger.shared.log("DIAG_PEER_VIEW | \(peer.prefix(8)) | \(legacy ? "account (legacy) view — account envelopes" : "device mode — per-device copies")")
    }

    /// `{"kind":"sent-copy","v":1,…}` (spec §3.6). `message` is the exact plaintext payload
    /// encrypted to the peer — a text payload, or the `v2file` key JSON for media.
    nonisolated static func sentCopyPayload(peer: String, message: String, msgId: String, sentAtMs: Int64) -> Data? {
        // Second precision (what Desktop/Android parse); the reader accepts both.
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        let obj: [String: Any] = [
            "kind": "sent-copy", "v": 1,
            "conversation": ["type": "1to1", "peer": peer],
            "message": message, "msgId": msgId,
            "sentAt": f.string(from: Date(timeIntervalSince1970: Double(sentAtMs) / 1000)),
        ]
        return try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys, .withoutEscapingSlashes])
    }

    /// A decoded `sent-copy` (1:1 only in this client version).
    struct SentCopy: Equatable, Sendable {
        let peer: String
        let message: String
        let msgId: String
        let sentAt: Date?
    }

    nonisolated static func parseSentCopy(_ text: String) -> SentCopy? {
        guard text.hasPrefix("{"), text.contains("\"sent-copy\""),
              let obj = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              obj["kind"] as? String == "sent-copy",
              let conv = obj["conversation"] as? [String: Any], conv["type"] as? String == "1to1",
              let peer = conv["peer"] as? String, !peer.isEmpty,
              let message = obj["message"] as? String,
              let msgId = obj["msgId"] as? String, !msgId.isEmpty else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let at = (obj["sentAt"] as? String).flatMap { f.date(from: $0) ?? ISO8601DateFormatter().date(from: $0) }
        return SentCopy(peer: peer, message: message, msgId: msgId, sentAt: at)
    }

    // MARK: Own per-device bundle

    private func deviceBundle(_ m: V2SessionStore.OwnKeyMaterial, opks: [V2SessionStore.OneTimePreKeyEntry]) -> V2.PreKeyBundleUpload {
        V2.PreKeyBundleUpload(
            identityKey: m.identity.pub.base64EncodedString(),
            signingKey: m.signingPublicKeyB64,
            signedPreKey: V2.SignedPreKey(keyId: m.signedPreKeyId,
                                          key: m.signedPreKey.pub.base64EncodedString(),
                                          signature: m.signedPreKeySignature.base64EncodedString()),
            oneTimePreKeys: opks.map { V2.OneTimePreKey(keyId: $0.keyId, key: $0.pair.pub.base64EncodedString()) },
            caps: RatchetHealSignal.Caps.mine)
    }

    /// `POST /v2/keys/publish {…, deviceId}` with THIS device's own pre-keys (spec §3.2.4).
    @discardableResult
    func publishDeviceBundle(signer: V2Device.Signer) async throws -> Int {
        let m = try ensureOwnKeyMaterial(forDevice: true)
        return try await client.publishDeviceBundle(deviceBundle(m, opks: m.oneTimePreKeys), signer: signer)
    }

    /// Keep the per-device pool topped up (`GET /v2/keys/<me>/count?device=<id>`).
    func refillDevicePreKeysIfNeeded(signer: V2Device.Signer, lowWater: Int = 5, target: Int = 20) async throws {
        let me = crypto.userKey
        guard !me.isEmpty else { return }
        let remaining = try await client.prekeyCount(userKey: me, device: signer.deviceId)
        guard remaining < lowWater else { return }
        let material = try ensureOwnKeyMaterial(forDevice: true)
        var fresh: [V2SessionStore.OneTimePreKeyEntry] = []
        for _ in 0..<max(0, target - remaining) {
            fresh.append(.init(keyId: UUID().uuidString, pair: .init(OSHICryptoV2.generateX25519())))
        }
        guard !fresh.isEmpty else { return }
        try deviceStore.appendOneTimePreKeys(fresh)
        _ = try await client.publishDeviceBundle(deviceBundle(material, opks: fresh), signer: signer)
    }

    enum AccountBundleOwnership: Sendable { case mine, other, none, unknown }

    /// Is the live ACCOUNT bundle on the server the one THIS install published? (spec §3.2,
    /// `holdsAccountBundle`). Pops one account one-time pre-key, so it is called once per
    /// registration, not per tick.
    func accountBundleOwnership() async -> AccountBundleOwnership {
        let me = crypto.userKey
        guard !me.isEmpty else { return .unknown }
        let mine = try? store.loadOwnKeyMaterial()
        do {
            let b = try await client.fetchBundle(userKey: me)
            guard let mine else { return .other }
            return b.signedPreKey.key == mine.signedPreKey.pub.base64EncodedString() ? .mine : .other
        } catch let V2.ClientError.http(status, _) where status == 404 {
            return .none
        } catch {
            return .unknown
        }
    }

    // MARK: Device lists

    /// The recipient's devices holding a bundle (cached 10 min). `[]` — no device, the
    /// feature off server-side, or the list unreachable — means the account path.
    func peerDeviceIds(_ peer: String, refresh: Bool = false) async -> [String] {
        let key = V2Device.normalizeIdentity(peer)
        if !refresh, let hit = peerDeviceCache[key], Date().timeIntervalSince(hit.at) < Self.peerDeviceCacheTTL {
            return hit.ids
        }
        guard let r = try? await client.peerDevices(userKey: peer) else {
            return peerDeviceCache[key]?.ids ?? []
        }
        let ids = r.enabled ? r.devices.map(\.deviceId).filter { V2Device.isDeviceId($0) } : []
        // __V2_PADDING_2026_11_09__ upgrade-only: a device list never contradicts a proof.
        if r.enabled { for d in r.devices where V2Device.isDeviceId(d.deviceId) { V2PaddingPeers.noteDeviceList(peer: peer, device: d.deviceId, caps: d.caps) } }
        // __MEDIA_PADDING_2026_11_10__ same rule (upgrade-only) for bit 8.
        if r.enabled { for d in r.devices where V2Device.isDeviceId(d.deviceId) { V2MediaPaddingPeers.noteDeviceList(peer: peer, device: d.deviceId, caps: d.caps) } }
        // __ACCOUNT_CAPS_IN_DEVICE_LIST_2026_11_10__ exact for the ACCOUNT slot (media bit only):
        // the account bundle is otherwise only learned by a fetch that pops a one-time pre-key.
        if let ac = r.accountCaps { V2MediaPaddingPeers.noteBundle(peer: peer, device: nil, caps: ac) }
        peerDeviceCache[key] = (ids, Date())
        return ids
    }

    /// __MEDIA_PADDING_2026_11_10__ May a v2 blob for `recipients` be PADDED?
    /// Only when EVERY device that can open it is known to read padded media
    /// (caps bit 8): per recipient, its account slot when it is reached on the
    /// account path, and each of its listed devices in device mode; plus this
    /// account's other devices when they get a sent-copy. Unknown ⇒ false ⇒ the
    /// exact pre-padding bytes. Reads the slot store only (no bundle fetch: that
    /// would pop a one-time pre-key); the device list is the cached one.
    func mediaPaddingAllowed(recipients: [String], includeOwnDevices: Bool) async -> Bool {
        guard !recipients.isEmpty else { return false }
        for r in recipients {
            var slots: [String?] = []
            if deviceContext != nil {
                let devices = await peerDeviceIds(r)
                if devices.isEmpty || peerReadsLegacy(V2Device.normalizeIdentity(r)) { slots.append(nil) }
                slots.append(contentsOf: devices.map { Optional($0) })
            } else {
                slots.append(nil)
            }
            for d in slots where !V2MediaPaddingPeers.slotCapable(peer: r, device: d) { return false }
        }
        if includeOwnDevices, let ctx = deviceContext {
            let me = crypto.userKey
            for d in await peerDeviceIds(me) where d != ctx.deviceId {
                if !V2MediaPaddingPeers.slotCapable(peer: me, device: d) { return false }
            }
        }
        return true
    }

    func setPeerDevices(_ peer: String, _ ids: [String]) {
        peerDeviceCache[V2Device.normalizeIdentity(peer)] = (ids.filter { V2Device.isDeviceId($0) }, Date())
    }

    /// Forget the session with one device of `peer` (stale device, or a device session that
    /// cannot be opened any more). Our next message to it re-runs X3DH with a header, which
    /// the other side adopts (RATCHET-H1) — the device-level repair, which never touches
    /// the legacy session.
    func dropDeviceSession(peer: String, device: String) {
        try? store.deleteSession(peerUserKey: Self.slot(peer, device: device))
    }

    // MARK: Fan-out (spec §3.5 / §3.6)

    /// Encrypt every target once per recipient device (or once for the account), add the
    /// self-copies for this account's other devices, POST ONE batch. On
    /// `409 device-list-mismatch` the list is replaced, stale sessions dropped and the batch
    /// rebuilt — at most 2 retries, then that peer falls back to the account envelope (and
    /// self-copies are dropped: devsync repairs them).
    ///
    /// - Returns: relay-accepted ids and the peers actually handled.
    func sendFanout(_ targets: [FanoutTarget],
                    selfCopy: (msgId: String, plaintext: Data)?,
                    ts: Int64) async throws -> (accepted: [String], handled: [String]) {
        guard let ctx = deviceContext else { throw V2.ClientError.invalidArgument("device mode inactive") }
        let me = crypto.userKey
        let meNorm = V2Device.normalizeIdentity(me)
        var lists: [String: [String]] = [:]
        for t in targets where lists[V2Device.normalizeIdentity(t.peer)] == nil {
            lists[V2Device.normalizeIdentity(t.peer)] = await peerDeviceIds(t.peer)
        }
        let selfTarget = targets.contains { V2Device.normalizeIdentity($0.peer) == meNorm }
        var ownOthers: [String] = []
        if selfCopy != nil, !selfTarget {
            ownOthers = await peerDeviceIds(me).filter { $0 != ctx.deviceId }
        }
        // __LEGACY_READER_2026_09_28__ A peer whose live install reads the account view gets the
        // account envelope (+ our self copies): per-device copies would only reach devices it does
        // not use. See `V2PeerReadView`.
        var forcedAccount = Set(lists.keys.filter { $0 != meNorm && peerReadsLegacy($0) })
        var dropSelfCopies = false

        for attempt in 0...3 {
            var envelopes: [V2.Envelope] = []
            var freshSlots: [String] = []
            var handled: [String] = []
            for t in targets {
                let pk = V2Device.normalizeIdentity(t.peer)
                var devices = forcedAccount.contains(pk) ? [] : (lists[pk] ?? [])
                if pk == meNorm { devices.removeAll { $0 == ctx.deviceId } }   // self-write: minus me
                var built: [V2.Envelope] = []
                do {
                    if devices.isEmpty {
                        let (h, c, x) = try await encrypt1to1(peer: t.peer, plaintext: t.plaintext, padGate: t.padGate)
                        if x != nil { freshSlots.append(t.peer) }
                        built.append(V2.Envelope(msgId: t.msgId, from: me, to: t.peer, type: t.type, groupId: t.groupId,
                                                 x3dh: x, header: h, ciphertext: c, ts: ts))
                    } else {
                        for d in devices {
                            let (h, c, x) = try await encrypt1to1(peer: t.peer, plaintext: t.plaintext, device: d, padGate: t.padGate)
                            if x != nil { freshSlots.append(Self.slot(t.peer, device: d)) }
                            built.append(V2.Envelope(msgId: t.msgId, from: me, to: t.peer, type: t.type, groupId: t.groupId,
                                                     x3dh: x, header: h, ciphertext: c, ts: ts,
                                                     toDevice: d, fromDevice: ctx.deviceId))
                        }
                    }
                } catch {
                    // A device we cannot reach (its bundle vanished between list and fetch):
                    // this peer goes to the account envelope for this message.
                    if !devices.isEmpty, !forcedAccount.contains(pk) {
                        OshiLog.messaging.info("⚠️ [V2] per-device encrypt failed for \(t.peer.prefix(8))… (\(error)) — account envelope")
                        forcedAccount.insert(pk)
                        if let r = try? await encrypt1to1(peer: t.peer, plaintext: t.plaintext, padGate: t.padGate) {
                            if r.x3dh != nil { freshSlots.append(t.peer) }
                            built = [V2.Envelope(msgId: t.msgId, from: me, to: t.peer, type: t.type, groupId: t.groupId,
                                                 x3dh: r.x3dh, header: r.header, ciphertext: r.ciphertext, ts: ts)]
                        } else { continue }
                    } else if targets.count == 1 {
                        for s in freshSlots { try? store.deleteSession(peerUserKey: s) }
                        throw error
                    } else {
                        continue
                    }
                }
                envelopes.append(contentsOf: built)
                handled.append(t.peer)
            }
            if let sc = selfCopy, !dropSelfCopies, !ownOthers.isEmpty, !envelopes.isEmpty {
                var copies: [V2.Envelope] = []
                do {
                    for d in ownOthers {
                        let (h, c, x) = try await encrypt1to1(peer: me, plaintext: sc.plaintext, device: d)
                        if x != nil { freshSlots.append(Self.slot(me, device: d)) }
                        copies.append(V2.Envelope(msgId: sc.msgId, from: me, to: me, type: .oneToOne,
                                                  x3dh: x, header: h, ciphertext: c, ts: ts,
                                                  toDevice: d, fromDevice: ctx.deviceId))
                    }
                    envelopes.append(contentsOf: copies)
                } catch {
                    dropSelfCopies = true   // the message itself still goes
                }
            }
            guard !envelopes.isEmpty else { return ([], []) }
            do {
                let ids = try await client.sendDeviceBatch(envelopes)
                return (ids, handled)
            } catch let m as V2Device.ListMismatch {
                // Nothing of this batch was stored. A session whose FIRST message (the one
                // carrying the X3DH header) was in it must be forgotten, or the retry would
                // go out headerless and the device could never open it.
                for s in freshSlots { try? store.deleteSession(peerUserKey: s) }
                let to = V2Device.normalizeIdentity(m.to)
                let current = m.devices.filter { V2Device.isDeviceId($0) }
                setPeerDevices(to, current)
                for d in m.stale { dropDeviceSession(peer: to, device: d) }
                CallFileLogger.shared.log("DIAG_V2_DEVICE_MISMATCH | attempt=\(attempt) | self=\(to == meNorm) | missing=\(m.missing.count) | stale=\(m.stale.count)")
                if to == meNorm && !selfTarget {
                    ownOthers = current.filter { $0 != ctx.deviceId }
                    if attempt >= 2 { dropSelfCopies = true }
                } else {
                    lists[to] = current
                    if attempt >= 2 { forcedAccount.insert(to) }
                }
                continue
            } catch {
                for s in freshSlots { try? store.deleteSession(peerUserKey: s) }
                throw error
            }
        }
        throw V2.ClientError.invalidArgument("device-list-mismatch persisted")
    }
}

// MARK: - __V2_PADDING_2026_11_09__ Bucketed plaintext padding (internet relay ONLY)
//
// WHAT IT HIDES. Before this, a relay envelope's ciphertext was the plaintext
// length + 16 bytes of AES-GCM tag, so the relay — and anyone reading its state
// file — could tell a delivery receipt ("📬DELIVERY_RECEIPT📬<uuid>", ~60 B) from
// a typed sentence, and read the exact length of every message. Padding to a few
// fixed buckets collapses that to a coarse size CLASS.
//
// WHERE, AND WHY THERE. Inside the ratchet AEAD, on the plaintext, at the single
// choke point every v2 send already goes through (`encrypt1to1`) and undone at
// the single choke point every v2 receive goes through (`decrypt1to1`). Inside
// the AEAD means the padding is confidential and integrity-protected: nobody can
// strip, extend or flip it without failing the tag. And `encrypt1to1` produces
// relay envelopes ONLY — the BLE/Wi-Fi mesh carries `SecureMessage` under the
// legacy ratchet and LoRa/MeshCore carry their own frames; neither ever calls
// this router, so their byte budgets (`MessageTextSplitter`, `MessageSendPolicy`,
// `GroupEnvelopeV3.loRaMessageCeiling`) are untouched by construction.
//
// THE EXPLICIT FLAG. A padded plaintext starts with `FF 50 01` (0xFF never
// occurs in UTF-8, and every v2 plaintext all three clients have ever produced
// is UTF-8 text or JSON), then the original bytes, then ISO/IEC 7816-4 padding
// (`0x80` then `0x00…`) up to the bucket. Padding is NEVER inferred from trailing
// bytes: a plaintext that does not start with 0xFF is returned exactly as is.
//
// COMPATIBILITY. A sender pads ONLY toward a session whose counterpart announced
// `capabilityBit` (prekey-bundle `caps`, or proven by sending us a padded
// message), and a group message only when every member announces it. An old
// client therefore never receives a padded plaintext. Receivers accept both
// forms forever. Same constants, same bytes on Android and Desktop
// (`network/v2/V2Padding.kt`, compiled into both), pinned by a shared vector.
enum V2Padding {
    /// Prekey-bundle `caps` bit: "strips v2 plaintext padding". Same bit on
    /// Android (`V2Padding.CAP_PADDING`) and Desktop.
    static let capabilityBit: Int = 1 << 2

    /// `FF` (never UTF-8) · `'P'` · format version 1.
    static let magic: [UInt8] = [0xFF, 0x50, 0x01]
    static let terminator: UInt8 = 0x80

    /// Total padded plaintext sizes. 256 B swallows every control payload AND
    /// the typical chat line (receipts, typing, "ok", a sentence), so the relay
    /// can no longer tell a receipt from a message; above that the classes grow
    /// ×4. 128 KiB is the last bucket: its ciphertext (131 088 B → 174 784 B of
    /// base64) stays well under the relay's 256 KiB `MAX_CIPHERTEXT_BYTES`. A
    /// plaintext too big for the last bucket is sent unpadded, exactly as today.
    static let buckets: [Int] = [256, 1024, 4096, 16384, 65536, 131072]

    /// The padded size for a plaintext of `n` bytes, or nil = too large, send as is.
    static func paddedLength(forPlaintextLength n: Int) -> Int? {
        let need = n + magic.count + 1
        return buckets.first { $0 >= need }
    }

    static func pad(_ plaintext: Data) -> Data {
        guard let total = paddedLength(forPlaintextLength: plaintext.count) else { return plaintext }
        var out = Data(capacity: total)
        out.append(contentsOf: magic)
        out.append(plaintext)
        out.append(terminator)
        out.append(Data(count: total - out.count))
        return out
    }

    enum Unpadded: Equatable {
        /// No flag: the bytes are an ordinary (old-format) plaintext, untouched.
        case plain(Data)
        /// Flag present and padding well-formed: the original plaintext.
        case padded(Data)
        /// Flag present, padding broken. Never shown, never "repaired".
        case malformed
    }

    static func unpad(_ data: Data) -> Unpadded {
        guard let first = data.first, first == 0xFF else { return .plain(data) }
        let b = [UInt8](data)
        guard b.count >= magic.count + 1, b[1] == magic[1], b[2] == magic[2] else { return .malformed }
        var i = b.count - 1
        while i >= magic.count, b[i] == 0x00 { i -= 1 }
        guard i >= magic.count, b[i] == terminator else { return .malformed }
        return .padded(Data(b[magic.count..<i]))
    }

    /// The one sending decision. `padGate` is the caller's group-level rule
    /// (true for 1:1); `slotCapable` is what we know of this session's counterpart.
    static func shouldPad(padGate: Bool, slotCapable: Bool) -> Bool { padGate && slotCapable }
}

/// What each SESSION's counterpart is known to understand about padding.
///
/// Keyed per session slot (identity, or identity#device), NOT per identity: the
/// account envelope is opened by whichever install holds the account session, a
/// per-device copy by that device — and those can be different builds. Written:
///  • from a prekey bundle at the moment it is fetched to build that slot's
///    session (`establishInitiatorSession`, `capabilities`) — exact value;
///  • from a peer device list (`caps`, when present) — upgrade only;
///  • from an inbound padded message on that slot — proof, upgrade;
///  • from an inbound X3DH-carrying message: the session's counterpart may have
///    just CHANGED (another install adopted the identity), so the slot is reset
///    to whatever that very message proves.
/// Absent ⇒ not capable ⇒ no padding. Deliberately separate from
/// `RatchetHealSignal.Caps`, which is keyed by identity and also overwritten by
/// per-device bundle fetches.
enum V2PaddingPeers {
    private static let storeKey = "oshi_v2_padding_slots"
    private static let cap = 2000
    private static let lock = NSLock()
    /// Injected by tests; the app uses `.standard`.
    nonisolated(unsafe) static var defaults: UserDefaults = .standard

    static func key(peer: String, device: String?) -> String {
        let id = V2Device.normalizeIdentity(peer)
        guard let device, !device.isEmpty else { return id }
        return id + "#" + device
    }

    /// From a router session slot (`peer` or `peer#device`; a userKey never contains `#`).
    static func key(slot: String) -> String {
        guard let hash = slot.firstIndex(of: "#") else { return key(peer: slot, device: nil) }
        return key(peer: String(slot[..<hash]), device: String(slot[slot.index(after: hash)...]))
    }

    private static func load() -> [String: Bool] {
        (defaults.dictionary(forKey: storeKey) as? [String: Bool]) ?? [:]
    }

    private static func set(_ k: String, _ v: Bool, upgradeOnly: Bool) {
        lock.lock(); defer { lock.unlock() }
        var m = load()
        if upgradeOnly && !v { return }
        guard m[k] != v else { return }
        m[k] = v
        while m.count > cap, let victim = m.keys.first(where: { $0 != k }) { m.removeValue(forKey: victim) }
        defaults.set(m, forKey: storeKey)
    }

    /// A bundle fetched for this slot. `caps == nil` (pre-caps bundle) ⇒ not capable.
    static func noteBundle(peer: String, device: String?, caps: Int?) {
        set(key(peer: peer, device: device), ((caps ?? 0) & V2Padding.capabilityBit) != 0, upgradeOnly: false)
    }

    /// A device list entry. Only ever upgrades: a list read never contradicts a proof.
    static func noteDeviceList(peer: String, device: String, caps: Int?) {
        guard let caps else { return }
        set(key(peer: peer, device: device), (caps & V2Padding.capabilityBit) != 0, upgradeOnly: true)
    }

    /// An inbound message decrypted on `slot`. `carriedX3DH`: the session may have a new
    /// counterpart, so its record becomes exactly what this message proves.
    static func noteInbound(slot: String, padded: Bool, carriedX3DH: Bool) {
        if carriedX3DH {
            set(key(slot: slot), padded, upgradeOnly: false)
        } else if padded {
            set(key(slot: slot), true, upgradeOnly: true)
        }
    }

    static func slotCapable(peer: String, device: String?) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return load()[key(peer: peer, device: device)] == true
    }

    /// "This member advertises padding" — at least one of its sessions is known capable.
    static func identityAdvertises(_ peer: String) -> Bool {
        let id = V2Device.normalizeIdentity(peer)
        lock.lock(); defer { lock.unlock() }
        return load().contains { (k, v) in v && (k == id || k.hasPrefix(id + "#")) }
    }

    /// The group rule, same shape as `GroupEnvelopeV3.canUse`: pad a group message only
    /// when EVERY other member advertises it. One old member ⇒ nobody's copy is padded
    /// (that member's unpadded copy would reveal the length anyway).
    static func groupAllows(members: [String], me: String) -> Bool {
        let others = members.filter { V2Device.normalizeIdentity($0) != V2Device.normalizeIdentity(me) }
        return !others.isEmpty && others.allSatisfy(identityAdvertises)
    }

    static func removeAllForTesting() {
        lock.lock(); defer { lock.unlock() }
        defaults.removeObject(forKey: storeKey)
    }
}

// MARK: - __MEDIA_PADDING_2026_11_10__ Who reads PADDED media (caps bit 8)
//
// Same shape and same learning points as `V2PaddingPeers` (bit 4), separate store:
// a slot that strips MESSAGE padding is not thereby proven to read padded MEDIA.
//  • bundle fetched for the slot (`capabilities`, `establishInitiatorSession`) — exact;
//  • peer device list `caps` — upgrade only;
//  • an inbound PADDED FILE on the slot (`parse`) — proof, upgrade;
//  • an inbound X3DH-carrying message — the counterpart may have changed, and a
//    message proves nothing about media: reset to false.
// Absent ⇒ not capable ⇒ the file is sent unpadded, byte-identical to before.
// Never consulted by mesh / LoRa / legacy IPFS: only `sendFile`/`sendGroupFile`
// (v2 blob store) ask `mediaPaddingAllowed`.
enum V2MediaPaddingPeers {
    private static let storeKey = "oshi_v2_media_padding_slots"
    private static let cap = 2000
    private static let lock = NSLock()
    /// Injected by tests; the app uses `.standard`.
    nonisolated(unsafe) static var defaults: UserDefaults = .standard

    static let capabilityBit: Int = RatchetHealSignal.Caps.mediaPadding

    private static func load() -> [String: Bool] {
        (defaults.dictionary(forKey: storeKey) as? [String: Bool]) ?? [:]
    }

    private static func set(_ k: String, _ v: Bool, upgradeOnly: Bool) {
        lock.lock(); defer { lock.unlock() }
        var m = load()
        if upgradeOnly && !v { return }
        guard m[k] != v else { return }
        m[k] = v
        while m.count > cap, let victim = m.keys.first(where: { $0 != k }) { m.removeValue(forKey: victim) }
        defaults.set(m, forKey: storeKey)
    }

    static func noteBundle(peer: String, device: String?, caps: Int?) {
        set(V2PaddingPeers.key(peer: peer, device: device), ((caps ?? 0) & capabilityBit) != 0, upgradeOnly: false)
    }

    static func noteDeviceList(peer: String, device: String, caps: Int?) {
        guard let caps else { return }
        set(V2PaddingPeers.key(peer: peer, device: device), (caps & capabilityBit) != 0, upgradeOnly: true)
    }

    static func noteInbound(slot: String, paddedFile: Bool, carriedX3DH: Bool) {
        if carriedX3DH {
            set(V2PaddingPeers.key(slot: slot), paddedFile, upgradeOnly: false)
        } else if paddedFile {
            set(V2PaddingPeers.key(slot: slot), true, upgradeOnly: true)
        }
    }

    static func slotCapable(peer: String, device: String?) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return load()[V2PaddingPeers.key(peer: peer, device: device)] == true
    }

    static func removeAllForTesting() {
        lock.lock(); defer { lock.unlock() }
        defaults.removeObject(forKey: storeKey)
    }
}


/// __RESEND_KEEPS_ORDER_2026_11_18__ The §2.1 envelope `ts` of a 1:1 message: the
/// date the user WROTE it. A send parked behind a block, a captive portal or a dead
/// disguised path is retried minutes later, and the receiver files each message by
/// this value (`ChatThreadPipeline` sorts the thread by `timestamp`), so stamping the
/// retry time instead put the resent messages in retry order — a photo that waited
/// for its upload landed after texts written after it.
nonisolated enum V2EnvelopeTimestamp {
    static func ms(original: Date?, now: Date = Date()) -> Int64 {
        Int64((original ?? now).timeIntervalSince1970 * 1000)
    }
}
