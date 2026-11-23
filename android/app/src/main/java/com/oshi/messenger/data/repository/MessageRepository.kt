package com.oshi.messenger.data.repository

import android.util.Log
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.local.ContactDao
import com.oshi.messenger.util.AppReviewManager
import com.oshi.messenger.data.model.*
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.encryption.DoubleRatchet
// __RATCHET_IS_SHARED_SOURCE_2026_10_28__ These two moved out of DoubleRatchet so that
// file could stop importing android.* and be compiled by the desktop client. Same names,
// same signatures, same behaviour — an extension just needs the import a member did not.
import com.oshi.messenger.network.encryption.loadSessions
import com.oshi.messenger.network.encryption.saveSessions
import com.oshi.messenger.network.encryption.GroupEnvelopeV3
import com.oshi.messenger.network.encryption.IOSRatchetMessage
import com.oshi.messenger.network.encryption.PeerCapabilities
import com.oshi.messenger.network.encryption.RatchetFailurePolicy
import com.oshi.messenger.network.encryption.ReplayedEnvelopeException
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.mesh.CrossPlatformMessage
import com.oshi.messenger.network.mesh.CrossPlatformPeer
import com.oshi.messenger.network.mesh.MeshMessage
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.MessageType
import com.oshi.messenger.network.vps.VPSClient
import com.oshi.messenger.network.location.LocationMessage
import com.oshi.messenger.network.vps.VPSMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.oshi.messenger.R
import com.oshi.messenger.service.CallEndReason
import com.oshi.messenger.service.CallState
import com.oshi.messenger.service.CallManager
import com.oshi.messenger.service.FCMService
import com.oshi.messenger.ui.MainActivity
import com.oshi.messenger.network.web.WebSessionManager
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.KeyRotationManager
import com.oshi.messenger.service.MediaCompressionManager
import com.oshi.messenger.service.MessageActionsManager
import com.oshi.messenger.service.MessageReactionManager
import com.oshi.messenger.service.MultiDeviceSyncManager
import com.oshi.messenger.service.NotificationPresentation
import com.oshi.messenger.service.TypingIndicatorManager
import com.oshi.messenger.service.ProfileLinksManager
import com.oshi.messenger.service.TextSteganography
import com.oshi.messenger.service.CovertChannelManager
import com.oshi.messenger.service.CoverOutbox
import com.oshi.messenger.service.WallpaperManager
import com.oshi.messenger.service.LoRaDeliveryLedger
import com.oshi.messenger.service.MessageSendPolicy
import com.oshi.messenger.service.media.GifBytes
import com.oshi.messenger.service.lora.LoRaEncryptedContent
import com.oshi.messenger.service.lora.LoRaIdentityBridge
import com.oshi.messenger.service.lora.LoRaChannelThread
import com.oshi.messenger.service.lora.LoRaInteropText
import com.oshi.messenger.service.lora.LoRaNodeIdentityMap
import com.oshi.messenger.service.lora.LoRaReceivedMessage
import com.oshi.messenger.service.lora.LoRaWireMessage
import com.oshi.messenger.service.lora.MeshCoreManager
import com.oshi.messenger.service.LastReactionStore
import com.oshi.messenger.service.LoRaLinkService
import com.oshi.messenger.service.lora.MeshtasticManager
import com.oshi.messenger.ui.viewmodel.dataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessageRepository - Unified message handling
 *
 * Handles:
 * - Sending/receiving via VPS or Mesh
 * - Message encryption/decryption
 * - Local storage
 * - Conversation management
 */
@Singleton
class MessageRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val vpsClient: VPSClient,
    private val meshManager: MeshNetworkManager,
    private val crossPlatformMesh: CrossPlatformMesh,
    private val cryptoManager: CryptoManager,
    private val doubleRatchet: DoubleRatchet,
    private val webSessionManager: WebSessionManager,
    private val reactionManager: MessageReactionManager,
    private val keyRotationManager: KeyRotationManager,
    private val mediaCompression: MediaCompressionManager,
    private val groupManager: GroupManager,
    private val messageActionsManager: MessageActionsManager,
    private val typingIndicatorManager: TypingIndicatorManager,
    private val profileLinksManager: ProfileLinksManager,
    private val multiDeviceSyncManager: MultiDeviceSyncManager,
    // Kept injected but intentionally UNUSED on every message path — see
    // `isSteganographyEnabled()` below. iOS reaches TextSteganography only from the
    // covert relay (`CovertChannelManager.swift:806-807`), and Android has no covert
    // receiver yet (audit P0 #7). This is the injection point that port will use.
    @Suppress("unused")
    private val textSteganography: TextSteganography,
    private val covertChannelManager: CovertChannelManager,
    private val callManager: CallManager,
    private val wallpaperManager: WallpaperManager,
    private val v2MessageRouter: com.oshi.messenger.network.v2.V2MessageRouter,
    private val safetyNumberManager: com.oshi.messenger.service.SafetyNumberManager,
    // __BOT_IN_THE_PIPELINE_2026_11_09__ Lazy: only touched in [initialize], see there.
    private val botManager: dagger.Lazy<com.oshi.messenger.service.BotManager>
) {
    companion object {
        private const val TAG = "MessageRepository"
        // SECURITY (CSPN 2026-09): compile-time guard. Must stay false in release.
        private const val LOG_SENSITIVE = false

        // 🔧 FIX: Profile update deduplication (match iOS behavior)
        private val recentProfileUpdates = mutableMapOf<String, Long>()
        private const val PROFILE_UPDATE_COOLDOWN = 30000L  // 30 seconds cooldown

        /**
         * __BLOCKED_NOTICE_2026_09_18__ How long a blocked sender goes without
         * being told again. One day: long enough that their own traffic cannot be
         * used as a presence beacon, short enough that someone who comes back a
         * week later is reminded rather than left guessing.
         */
        private const val BLOCKED_NOTICE_INTERVAL_MS = 24L * 60 * 60 * 1000

        /**
         * __BLOCKED_NOTICE_SWITCH_2026_09_23__ Product switch: is a blocked peer told?
         * `true` = the owner's request. Signal/WhatsApp deliberately do NOT tell (the
         * notice confirms to a harasser that the account is alive and saw them).
         * `false` makes blocking silent again. Same switch on iOS
         * (`MessageManager.blockedNoticeEnabled`) and Desktop (`BlockedNoticeGate.ENABLED`).
         */
        const val BLOCKED_NOTICE_ENABLED = true

        /** The live singleton, for the call managers' blocked-caller branch (no DI edge). */
        @Volatile private var live: MessageRepository? = null

        /**
         * A blocked caller never rings (EnhancedCallManager / CallManager drop the
         * CALL_REQUEST). Without this they rang out into nothing; now they get the
         * same rate-limited 🚫BLOCKED🚫 line a blocked message gets. `null`
         * plaintext = a gesture, not machinery, so rule 1 lets it through.
         */
        fun noticeBlockedCaller(callerPublicKey: String) {
            val r = live ?: return
            r.scope.launch { r.noticeBlockedSender(callerPublicKey, null) }
        }

        /**
         * Non-suspending block test for callers outside the repository that cannot
         * suspend (the group receive path's local banner). Cache only: a cold cache
         * answers false, i.e. at worst one banner, never a dropped message.
         */
        fun isSenderBlockedCached(publicKey: String): Boolean {
            val r = live ?: return false
            if (publicKey.isEmpty()) return false
            val norm = normalizeBase64Key(publicKey)
            return synchronized(r.blockedKeyCache) { r.blockedKeyCache.contains(norm) }
        }

        /**
         * Devsync and the legacy contacts sync write `contacts.isBlocked` straight to
         * the DAO. Every receive gate reads [blockedKeyCache], which only
         * [blockContact]/[unblockContact] kept in step — so a block made on another
         * device did not stop messages or FCM banners here until the next restart,
         * and an unblock kept dropping them. Re-seed after any such write.
         */
        fun reloadBlockedCache() { live?.refreshBlockedKeyCache() }

        // Max COMPRESSED media size that rides the V2 relay inline (as a base64 JSON
        // envelope inside a single Double-Ratchet message). Above this, media takes the
        // legacy path; a dedicated /v2/blobs chunk channel is future scope. 256 KB of
        // raw bytes ≈ 344 KB base64 — within relay message limits and covers photos and
        // voice-notes; long videos exceed it and fall through by design.
        private const val V2_INLINE_MEDIA_MAX_BYTES = 256 * 1024

        /**
         * The LEGACY 1:1 media envelope — the shape that rides the Double-Ratchet
         * VPS/IPFS path when the peer has no v2 bundle.
         *
         * `{"type":"IMAGE","caption":…,"data":<b64>,"isViewOnce":…}` — the "Android
         * direct" convention iOS reads at `MessageManager.swift:7281-7302`
         * (`isAndroidDirect`, uppercase enum names) with the bytes under `data`
         * (`:7274`).
         *
         * A pure function, and NOT an inline template inside `sendMediaMessage`,
         * because the escaping is the part that breaks: the previous inline version
         * escaped `"` and `\n` and left `\` alone, which produced invalid JSON for a
         * caption as ordinary as `C:\Users`. iOS then stored the whole base64 photo
         * as a text bubble (`MessageManager.swift:6116`) and Android's own
         * `handleMediaContent` dropped it. Escaping is delegated to
         * [OSHICryptoV2.jsonEscape], the complete escaper the V2 codec already uses.
         *
         * 📄 M-6, send half over the LEGACY transport (2026-08-25). iOS reads
         * `mediaJson["fileName"]` out of THIS envelope — `parseAndStoreMedia`,
         * `MessageManager.swift:7317`, straight onto `message.mediaFileName` at
         * `:7334` — and its own legacy encoder writes the key
         * (`MessageManager.swift:5294-5296`). Android emitted no such key, so a
         * `Contrat-2026.pdf` sent to a peer with no V2 bundle arrived on the iPhone
         * with a nil `mediaFileName` and rendered nameless. The V2 blob leg had
         * already been fixed ([wireFileName]); this envelope was what was left.
         *
         * [fileName] is OMITTED when null — exactly as iOS's `if let` omits it — so
         * every photo, video and voice note on this path stays **byte-identical** to
         * what shipped. Only an attachment that actually has a user-chosen name
         * grows a key.
         */
        internal fun legacyMediaEnvelope(
            typeName: String,
            caption: String?,
            dataBase64: String,
            isViewOnce: Boolean,
            fileName: String? = null,
            /** __VIDEO_NOTE_2026_09_24__ [VideoNote.jsonFragment] output ("" for any other media). */
            videoNoteFragment: String = "",
            /**
             * __LEGACY_WRAPPER_2026_09_27__ Non-null ⇒ emit iOS's own wrapper
             * `{"type":"media","mediaType":<this>,…}` instead of the Android-direct
             * `{"type":"CONTACT",…}`. iOS's legacy receiver recognises the direct form
             * for IMAGE/VIDEO/AUDIO/DOCUMENT ONLY (`isMediaMessage`,
             * `MessageManager.swift:7652-7681`); anything else — a contact card — was
             * stored as a text bubble of raw JSON. See [legacyWireMediaType].
             */
            iosMediaType: String? = null,
        ): String {
            val c = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(caption ?: "")
            val t = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(typeName)
            val head = if (iosMediaType != null) {
                val m = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(iosMediaType)
                """{"type":"media","mediaType":"$m","caption":"$c","data":"$dataBase64","isViewOnce":$isViewOnce$videoNoteFragment"""
            } else {
                """{"type":"$t","caption":"$c","data":"$dataBase64","isViewOnce":$isViewOnce$videoNoteFragment"""
            }
            // Sanitized HERE as well as wherever the caller got it: the receiver hands
            // this name to a `File` constructor on API < 29. A traversal must not be
            // emittable through any path, in either direction.
            val n = com.oshi.messenger.util.AttachmentFileName.sanitize(fileName)
                ?: return "$head}"
            val e = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(n)
            return """$head,"fileName":"$e"}"""
        }

        /**
         * __LEGACY_WRAPPER_2026_09_27__ The iOS `mediaType` to wrap a legacy/mesh media
         * envelope in, or null to keep the Android-direct form every shipped iPhone
         * already reads (and which stays byte-identical for photos, videos, voice notes
         * and documents). A contact card needs the wrapper to be recognised at all; a
         * GIF needs it to animate (the direct form can only say IMAGE — iOS then keeps
         * `mediaType` = nil/`gif` and sniffs the bytes).
         */
        internal fun legacyWireMediaType(mediaType: MediaType, isGif: Boolean): String? = when {
            mediaType == MediaType.CONTACT -> "contact"
            mediaType == MediaType.IMAGE && isGif -> "gif"
            else -> null
        }

        /**
         * __LEAK_FIX_2026_09_25__ NO LONGER SENT. The mesh media leg now carries the
         * ratchet ciphertext ([crossPlatformMeshEncryptedPayload]). Kept only as the
         * reference for the legacy RAW frame shape older peers still send and every
         * receiver still accepts; do not put its output on a wire again.
         *
         * The CROSS-PLATFORM MESH media envelope — the plaintext JSON an iPhone on
         * the same LAN parses in `handleCrossPlatformMediaMessage`
         * (`{data, mediaType, fileName, fileSize, caption, isViewOnce}`).
         *
         * A pure function for the same reason [legacyMediaEnvelope] is one: iOS drops
         * this envelope OUTRIGHT when it is not valid JSON — `MeshNetworkManager.swift:417-424`
         * has no text fallback at all — so the escaping is the part that silently
         * costs a message, and it belongs somewhere a test can reach it.
         *
         * Both the caption and the name go through the complete escaper
         * ([OSHICryptoV2.jsonEscape]); the name is sanitized first, because it may
         * have come from a share-sheet URI whose display name the sender never chose.
         */
        internal fun crossPlatformMediaEnvelope(
            dataBase64: String,
            iosMediaType: String,
            fileName: String,
            size: Int,
            caption: String?,
            isViewOnce: Boolean,
            /** __VIDEO_NOTE_2026_09_24__ spec §2.E — "" for anything but a note. */
            videoNoteFragment: String = "",
        ): String {
            val c = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(caption ?: "")
            val t = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(iosMediaType)
            val n = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(
                com.oshi.messenger.util.AttachmentFileName.sanitize(fileName) ?: fileName
            )
            return """{"data":"$dataBase64","mediaType":"$t","fileName":"$n","fileSize":$size,"caption":"$c","isViewOnce":$isViewOnce$videoNoteFragment}"""
        }

        /**
         * __GIF_WIRE_TYPE_2026_09_13__ (LAN mesh leg) Les octets décident, pas l'enum.
         *
         * Le chemin blob/inline V2 renifle déjà les octets et émet `gif` /
         * `image/gif` / `.gif`; le chemin CrossPlatformMesh, lui, lisait le seul
         * [MediaType] et étiquetait `image` avec une extension `.jpg`. iOS rend
         * STRICTEMENT sur l'étiquette de cet enveloppe
         * (`handleCrossPlatformMediaMessage` → `mediaType`), donc un GIF envoyé sur
         * le LAN arrivait figé sur sa première image et s'enregistrait en `.jpg` —
         * alors que les octets transmis étaient bien le GIF animé (la compression
         * est déjà gif-aware et le laisse INTACT).
         *
         * Deux fonctions pures, à côté de [crossPlatformMediaEnvelope], pour que la
         * règle soit couverte par un test JVM plutôt que par deux téléphones.
         *
         * La table d'extension reste CELLE DE CE CHEMIN (`bin` pour un document, pas
         * le `dat` de `wireFileName`): seul le cas GIF est ajouté, tout le reste doit
         * rester octet pour octet ce qui est déjà livré.
         */
        internal fun crossPlatformMeshWireType(mediaType: MediaType, isGif: Boolean): String = when {
            isGif -> "gif"
            mediaType == MediaType.IMAGE -> "image"
            mediaType == MediaType.VIDEO -> "video"
            mediaType == MediaType.AUDIO -> "audio"
            mediaType == MediaType.DOCUMENT -> "document"
            else -> mediaType.name.lowercase()
        }

        /** L'extension synthétisée pour l'enveloppe mesh. Voir [crossPlatformMeshWireType]. */
        /**
         * __LEAK_FIX_2026_09_25__ Largest ratchet envelope put on ONE cross-platform
         * mesh frame. Both ends accept frames up to 100 MiB (`MAX_FRAME_SIZE` here,
         * `maxFrameSize` in `OSHI/CrossPlatformMesh.swift`); the envelope is wrapped in
         * a `CrossPlatformMessage` JSON, so keep generous headroom. Above this the
         * encrypted VPS path carries the media alone.
         */
        internal const val CROSS_PLATFORM_MESH_MAX_ENCRYPTED_BYTES = 48 * 1024 * 1024

        /**
         * __LEAK_FIX_2026_09_25__ The payload an Android → iPhone mesh MEDIA send puts
         * on the wire: the ratchet `EncryptedMessage` JSON itself (what a text sends,
         * iOS `handleCrossPlatformTextMessage` path 1), or null when it must not ride
         * the mesh (too big for a frame, or not an EncryptedMessage JSON object). The
         * caller never substitutes a plaintext envelope for a null.
         */
        internal fun crossPlatformMeshEncryptedPayload(encrypted: ByteArray): String? {
            if (encrypted.isEmpty() || encrypted.size > CROSS_PLATFORM_MESH_MAX_ENCRYPTED_BYTES) return null
            val s = String(encrypted, Charsets.UTF_8)
            if (!s.startsWith("{")) return null
            return s
        }

        /**
         * __MESH_STABLE_ID_2026_09_27__ The mesh leg of a dual-send, wrapped so the
         * iPhone files it under the SAME id as the relay copy.
         *
         * A bare `EncryptedMessage` on the mesh reaches iOS
         * `handleCrossPlatformTextMessage` path 1, which wraps it in a SecureMessage with
         * `id: UUID().uuidString` (`MeshNetworkManager.swift:376-378`) — so it could never
         * be matched against the relay copy (`id` = [messageId]) and the same photo or
         * text could land twice. That handler tries a full `SecureMessage` decode FIRST
         * (`:357`) and keeps ITS id; `receiveMessage` then drops the second arrival on
         * `messages.contains { $0.id == message.id }` (`MessageManager.swift:6187`)
         * before any decrypt. This is that SecureMessage: every field its decoder
         * requires (`MessageManager.swift:9212-9221`) — `id`, `senderAddress`,
         * `recipientAddress`, `encryptedContent` (the EncryptedMessage OBJECT),
         * `timestamp` (Apple-epoch seconds: bare `JSONDecoder`), `isRead`,
         * `deliveryStatus` (a raw value iOS has: `delivered`) — plus the two key fields
         * path 1 would have filled in. Android's receiver already unwraps this shape
         * (`handleCrossPlatformMessage`, "iOS SecureMessage wrapper").
         *
         * Null when [encryptedJson] is not a JSON object (the caller then sends nothing
         * on the mesh — never a plaintext fallback).
         */
        internal fun crossPlatformMeshSecureMessage(
            encryptedJson: String,
            messageId: String,
            senderPublicKey: String,
            recipientPublicKey: String,
            timestampMillis: Long,
        ): String? {
            val inner = try { JSONObject(encryptedJson) } catch (_: Exception) { return null }
            return JSONObject().apply {
                put("id", messageId)
                put("senderAddress", senderPublicKey)
                put("recipientAddress", recipientPublicKey)
                put("encryptedContent", inner)
                put("timestamp", timestampMillis / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS)
                put("isRead", false)
                put("deliveryStatus", "delivered")
                put("senderPublicKey", senderPublicKey)
                put("recipientPublicKey", recipientPublicKey)
            }.toString()
        }

        /**
         * __MESH_CONTROL_PAYLOAD_2026_09_27__ How a control payload (reaction, action)
         * rides the cross-platform mesh. iOS reads a mesh TEXT_MESSAGE as JSON first,
         * then as base64 (`MeshNetworkManager.swift:398-417`). A reaction is sealed by
         * `CryptoManager.encryptMessage` into RAW bytes, and `String(bytes, UTF_8)` of
         * those is neither — and base64 of it is not an `EncryptedMessage` either, so
         * there is no form of those bytes the iPhone's mesh reader can use. A JSON
         * envelope goes as-is; anything else returns null and the caller skips the
         * mesh leg (the relay copy, which iOS does read, still goes).
         */
        internal fun crossPlatformMeshControlPayload(encrypted: ByteArray): String? {
            val asText = String(encrypted, Charsets.UTF_8)
            val isJsonObject = asText.startsWith("{") &&
                try { JSONObject(asText); true } catch (_: Exception) { false }
            return if (isJsonObject) asText else null
        }

        /**
         * The id a cross-platform mesh payload should be stored and deduplicated under:
         * the wrapped SecureMessage's own `id` when there is one (iOS mesh, and Android
         * since [crossPlatformMeshSecureMessage]), else the frame id.
         */
        internal fun meshEnvelopeMessageId(payload: String, frameId: String): String {
            if (!payload.startsWith("{")) return frameId
            val obj = try { JSONObject(payload) } catch (_: Exception) { return frameId }
            if (obj.optJSONObject("encryptedContent") == null) return frameId
            return obj.optString("id", "").takeIf { it.isNotBlank() } ?: frameId
        }

        internal fun crossPlatformMeshExtension(mediaType: MediaType, isGif: Boolean): String = when {
            isGif -> "gif"
            mediaType == MediaType.IMAGE -> "jpg"
            mediaType == MediaType.VIDEO -> "mp4"
            mediaType == MediaType.AUDIO -> "m4a"
            mediaType == MediaType.DOCUMENT -> "bin"
            else -> "bin"
        }

        /**
         * The text a legacy-transport media message shows on its bubble.
         *
         * 📄 M-6, receive half over the LEGACY transport. `handleMediaContent` read
         * the caption and nothing else, but iOS's legacy encoder sends a document's
         * real name in `fileName` and leaves the body EMPTY
         * (`MessageManager.swift:5294-5296`) — so an iPhone document over the pre-V2
         * transport landed with `content = ""`, drew an empty document row, and
         * "Download" wrote a file called "".
         *
         * Precedence, and the reason for it:
         *  * a non-empty CAPTION always wins — Android→Android already carries the
         *    name as `[DOCUMENT] <name>` there, and that spelling must not change;
         *  * otherwise, for a DOCUMENT only, the peer's `fileName`;
         *  * otherwise the caption, empty or not. Other media types keep the caption
         *    untouched: a photo's `fileName` is not its bubble text.
         *
         * 🔒 [wireFileName] is PEER-CONTROLLED and reaches
         * `java.io.File(oshiDir, name)` in `ChatScreen.saveDocumentToDownloads` on
         * API < 29. `DocumentMessageContent` sanitizes at render; it is sanitized
         * again HERE so a traversal is never even STORED. `sanitize` answering null
         * (nothing usable survived) falls back to the caption rather than inventing
         * a name.
         */
        internal fun inboundMediaDisplayText(
            mediaType: MediaType,
            caption: String,
            wireFileName: String?,
            /** A CONTACT's card bytes, to name the person when no caption came. */
            contactBytes: ByteArray? = null,
        ): String {
            if (caption.isNotEmpty()) return caption
            if (mediaType == MediaType.CONTACT) return contactRowText(contactBytes) ?: caption
            if (mediaType == MediaType.DOCUMENT) {
                return com.oshi.messenger.util.AttachmentFileName.sanitize(wireFileName)
                    ?: placeholderFor(mediaType)
            }
            // __A_PHOTO_WITH_NO_CAPTION_WAS_A_BLANK_ROW_2026_11_04__ (Anatole, with a
            // screenshot: a conversation row showing the sender, the avatar and
            // "1h ago", and nothing else).
            //
            // This used to `return caption` here, "empty or not". For a photo with no
            // typed caption that is the EMPTY STRING, and it travels all the way to
            // the conversation list, where `listPreview` matches none of its branches
            // and hands the empty string back. The row then renders a sender, a time,
            // and a blank space where the message should be.
            //
            // It is not an edge case and it is not iOS-specific. `wireCaption` STRIPS
            // our own `[IMAGE]` marker before sending, precisely so a peer is not shown
            // a word nobody typed, so EVERY captionless photo arrives with an empty
            // caption, Android to Android included.
            //
            // The placeholder is what this app's own sender already stores locally
            // (`content = caption ?: "[IMAGE]"`), so this is not a new convention: the
            // bubble suppresses anything starting with "[" (`ChatScreen:4301`) and the
            // list turns it into "📷 Photo". Receiving now stores what sending
            // always stored.
            return placeholderFor(mediaType)
        }

        /**
         * The local row marker for a media message with no caption, e.g. `[IMAGE]`.
         *
         * LOCATION is excluded: it is not drawn as a media bubble at all
         * (`ChatScreen:4274`) and has no entry in the preview catalog, so a
         * `[LOCATION]` would be the one placeholder that reached a user's eyes
         * literally.
         */
        private fun placeholderFor(mediaType: MediaType): String =
            if (mediaType == MediaType.LOCATION) "" else "[${mediaType.name}]"

        /**
         * `[CONTACT] Alice` — the row text this app's own sender writes, which the
         * conversation list turns into "👤 Alice". Derived from the card itself, since
         * neither an iPhone nor (after D2) an Android sender puts it on the wire.
         */
        internal fun contactRowText(cardBytes: ByteArray?): String? {
            val card = cardBytes?.let {
                com.oshi.messenger.data.model.ContactCardWire.decode(String(it, Charsets.UTF_8))
            } ?: return null
            val name = card.alias ?: return null
            return com.oshi.messenger.data.model.OSHIControlPayload.CONTACT_CAPTION_PREFIX + name
        }

        /**
         * __WIRE_CAPTION_2026_09_27__ (D2) The caption a PEER should be shown, or null.
         *
         * Android keeps a local marker in the caption slot — `[AUDIO] 0:05`
         * (`ChatViewModel.sendVoiceMessage`), `[DOCUMENT] name` (`sendDocumentMessage`),
         * `[CONTACT] Alice` (`sendContactCard`), and `[IMAGE]`/`[VIDEO]`/… as the default
         * row text a forward re-sends. iOS prints whatever arrives in `caption` under the
         * bubble (`parseAndStoreMedia` → `plaintextContent` → `MediaChatBubble.captionText`).
         * None of those are words the user typed, so none leave the device. Android
         * receivers never needed them: the voice player probes the real duration from the
         * bytes, a document's name travels as its file name, and a card names itself.
         */
        internal fun wireCaption(mediaType: MediaType, caption: String?): String? {
            val c = caption ?: return null
            val t = c.trim()
            if (t.isEmpty()) return null
            if (t.startsWith("[${mediaType.name}]")) return null
            // A document never carries a typed caption on this platform: whatever is in
            // the slot is its name (marker or bare, e.g. a forwarded iPhone document).
            if (mediaType == MediaType.DOCUMENT) return null
            return c
        }

        /**
         * The name a document should travel under when the caller passed none — a
         * forward, which only has the row's text: `[DOCUMENT] name` from this app, or the
         * bare name an iPhone's document is stored under ([dispatchV2InboundFile]).
         */
        internal fun documentNameFromCaption(caption: String?): String? {
            val raw = caption?.trim()?.removePrefix(DOCUMENT_CAPTION_MARKER)?.trim()
            if (raw.isNullOrEmpty() || raw.contains('\n')) return null
            return com.oshi.messenger.util.AttachmentFileName.sanitize(raw)
        }

        /**
         * __V2_CAPTION_2026_09_27__ (D1) The id of the text that repeats a media caption
         * for peers that cannot read [com.oshi.messenger.network.v2.V2FileKeyMessage.caption]
         * (every shipped iPhone: `sendFile` has no caption field there, and its inline
         * parser ignores `caption`). Name-based, so sender and receiver derive it from the
         * media id alone and an Android receiver can drop the duplicate whichever arrives
         * first.
         */
        internal fun captionFollowUpId(mediaMessageId: String): String =
            java.util.UUID.nameUUIDFromBytes(
                "oshi-caption:${mediaMessageId.lowercase()}".toByteArray(Charsets.UTF_8)
            ).toString()

        /** Only a photo or video ever has a typed caption. */
        internal fun needsCaptionFollowUp(mediaType: MediaType, peerCaption: String?): Boolean =
            !peerCaption.isNullOrBlank() && (mediaType == MediaType.IMAGE || mediaType == MediaType.VIDEO)

        /**
         * This app's local marker for "the document's name is in the caption".
         * NOT a wire convention: iOS has a `mediaFileName` field and never parses
         * this. Written by `ChatViewModel.sendDocumentMessage`, stripped by
         * `ChatScreen.DocumentMessageContent` and by [webDownloadName].
         */
        internal const val DOCUMENT_CAPTION_MARKER = "[DOCUMENT]"

        /**
         * The name the WEB client should download an attachment under.
         *
         * The last leg of M-6. `WebSessionManager.notifyNewMessage` and
         * `sendMediaResponse` both take a `fileName` and every call site passed
         * `null`, so a document opened in the browser companion saved as the bare
         * message id with no extension — the same nameless-file symptom as the wire
         * bug, one hop further out.
         *
         * A DOCUMENT's name lives in `content` on this platform: either
         * `[DOCUMENT] <name>` (what this app's own sender writes) or the bare name
         * (what [dispatchV2InboundFile] stores off the V2 wire). Everything else has
         * no user-chosen name and gets `null`, which is what the web client already
         * handles.
         *
         * Pure, and sanitized: `content` may be peer-supplied, and the browser turns
         * this into a `Content-Disposition` filename.
         */
        internal fun webDownloadName(mediaTypeName: String?, content: String?): String? {
            if (!mediaTypeName.equals(MediaType.DOCUMENT.name, ignoreCase = true)) return null
            // Same marker and the same strip as `ChatScreen.DocumentMessageContent`
            // (`filename.removePrefix("[DOCUMENT]").trim()`) — one convention, read
            // identically wherever a document's name is needed.
            val raw = content?.removePrefix(DOCUMENT_CAPTION_MARKER)?.trim()
            return com.oshi.messenger.util.AttachmentFileName.sanitize(raw)
        }

        // 🔧 iOS unified message-action prefix — `MessageActionPayload.messagePrefix`,
        // `OSHI/MessageManager.swift:8090` (`"\u{1F527}ACTION\u{1F527}"`).
        // Mirrors the private constant at `MessageActionsManager.kt:41`.
        private const val ACTION_PREFIX_IOS = "🔧ACTION🔧"

        /**
         * Seconds between the Unix epoch and Apple's 2001-01-01 reference date.
         * A Swift `Date` encoded by a default `JSONEncoder` (`.deferredToDate`) is a
         * Double in THIS epoch — see `MessageActionPayload` at
         * `OSHI/MessageManager.swift:8098,8110-8114`.
         */
        internal const val APPLE_EPOCH_OFFSET_SECONDS = 978307200.0

        /**
         * Build a 🔧ACTION🔧 payload byte-identical to what iOS's `MessageActionPayload`
         * encodes. `internal` + `nowMs` injectable so the wire shape is unit-testable.
         */
        internal fun buildActionPayload(
            actionType: String,
            targetMessageId: String,
            newContent: String?,
            nowMs: Long = System.currentTimeMillis()
        ): String {
            val json = JSONObject().apply {
                put("actionType", actionType)
                put("targetMessageId", targetMessageId)
                // Swift's synthesized Codable uses encodeIfPresent for Optionals, so a
                // nil `newContent` is an ABSENT key — not `null`, and certainly not the
                // JSONObject.NULL the dead emitter in MessageActionsManager.kt:465 puts.
                if (newContent != null) put("newContent", newContent)
                put("timestamp", (nowMs / 1000.0) - APPLE_EPOCH_OFFSET_SECONDS)
            }
            return "$ACTION_PREFIX_IOS$json"
        }

        // 📬 Delivery & read receipt prefixes (cross-platform)
        private const val DELIVERY_RECEIPT_PREFIX = "📬DELIVERY_RECEIPT📬"
        private const val READ_RECEIPT_PREFIX = "📖READ_RECEIPT📖"

        // 👥 Group message prefixes
        private const val GROUP_MESSAGE_PREFIX = "\uD83D\uDC65GROUP_MESSAGE\uD83D\uDC65"  // 👥GROUP_MESSAGE👥
        private const val GROUP_UPDATE_PREFIX = "\uD83D\uDCE2GROUP_UPDATE\uD83D\uDCE2"    // 📢GROUP_UPDATE📢

        /**
         * Normalize a base64 / base64url public key into a canonical form for
         * equivalence comparison.
         *
         * Mirrors VoiceCallManager.normalizeBase64Key() in iOS:
         * - trim leading/trailing whitespace
         * - base64url → standard base64 ("-" → "+", "_" → "/")
         * - drop "=" padding
         *
         * Used by:
         * - getConversations() — groups contacts whose stored publicKey strings
         *   differ only by padding/whitespace/case so the chat list does NOT
         *   show two threads for the same person.
         * - The one-shot duplicate-contact migration in `mergeDuplicateContacts()`.
         */
        fun normalizeBase64Key(key: String): String {
            return key.trim()
                .replace('-', '+')
                .replace('_', '/')
                .replace("=", "")
        }

        /** At most this many avatar requests per profile sweep. */
        /**
         * Biggest avatar we will accept FROM a peer.
         *
         * Our own are 200x200 JPEG q50 — a few KB — so this is ~50x headroom for a
         * legitimate picture while staying an order of magnitude under the ~2 MB
         * CursorWindow that a `contacts` row has to fit inside once Room base64-encodes it.
         */
        private const val MAX_INBOUND_AVATAR_BYTES = 256 * 1024

        /** The same cap measured on the wire, before decoding allocates anything. */
        private const val MAX_INBOUND_AVATAR_B64_CHARS = (MAX_INBOUND_AVATAR_BYTES / 3 + 1) * 4

        private const val PROFILE_SWEEP_BATCH = 8

        /** Floor between two profile sweeps, independent of the poll tick rate. */
        private const val PROFILE_SWEEP_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * The canonical STORAGE spelling of an identity key. Byte-for-byte port of
         * iOS `MessageManager.canonicalIdentity(_:)` (`OSHI/MessageManager.swift:6246-6261`).
         *
         * NOT the same relation as [normalizeBase64Key], and the difference is the
         * whole point: `normalizeBase64Key` STRIPS the padding, which produces a
         * string that is not legal base64 and therefore cannot be stored — it is a
         * comparison scratch value only. This one folds base64url back to standard
         * base64, RE-PADS to a multiple of 4, and returns the input untouched unless
         * the result actually decodes. That makes the output a legal key.
         *
         * Why it matters here: everything downstream of a store is an exact match,
         * and on Android the strictest of them is SQL (`AppDatabase.kt:247`,
         * `WHERE senderPublicKey = :publicKey`), which no Kotlin-side normalisation
         * can reach. A message written in a second spelling lands in the table,
         * fires its notification, and never appears in the thread.
         *
         * Deliberately NOT applied to the V2 X3DH identity binding
         * (`V2MessageRouter.kt`, `V2KeysClient.kt`) or to the safety-number sort:
         * those compare the exact wire string on both platforms and must stay strict.
         */
        /**
         * Which contacts a profile sweep should ask, given everything we hold.
         *
         * Pure so it can be tested without an Android runtime, and so the test drives
         * the SAME code the poll tick runs rather than a copy of the rule — a test that
         * re-implements its subject agrees with itself and proves nothing.
         */
        @androidx.annotation.VisibleForTesting
        fun profileSweepTargets(contacts: List<Contact>): List<Contact> =
            contacts.filter { it.profilePicture == null }.take(PROFILE_SWEEP_BATCH)

        /**
         * Put an inbound avatar on the right contact row, and say how many rows moved.
         *
         * Contact rows are keyed on [canonicalIdentity]; the key on the wire is whatever
         * the peer sent. Order is canonical, then raw (a row written by an older build
         * under a non-canonical key), then create-and-retry so a profile arriving before
         * the first stored message is not dropped.
         *
         * Takes the DAO rather than reading `contactDao`, so an instrumented test can
         * hand it a real Room database and exercise this exact function.
         */
        @androidx.annotation.VisibleForTesting
        suspend fun writeProfilePictureTo(
            dao: ContactDao,
            publicKey: String,
            data: ByteArray?
        ): Int {
            val canonical = canonicalIdentity(publicKey)
            var rows = dao.updateProfilePicture(canonical, data)
            if (rows == 0 && canonical != publicKey) {
                rows = dao.updateProfilePicture(publicKey, data)
            }
            if (rows == 0) {
                dao.insert(Contact(publicKey = canonical, alias = canonical.take(8) + "..."))
                rows = dao.updateProfilePicture(canonical, data)
            }
            return rows
        }

        fun canonicalIdentity(key: String): String {
            // Fast path, allocation-free: already standard base64 with legal padding.
            // Runs on every stored row, so the common case must not build three
            // intermediate strings to answer "nothing to do" (iOS :6247-6251).
            if (key.length % 4 == 0 && !key.contains('-') && !key.contains('_')) return key
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return key
            var s = trimmed.replace('-', '+').replace('_', '/')
            s = s.trimEnd('=')
            while (s.length % 4 != 0) s += "="
            // The decode check is what makes this safe to STORE: a pseudo-key like
            // `lora!a1b2c3` (the unbound-radio conversation key) is not base64, must
            // not be rewritten, and comes back out unchanged.
            // `java.util.Base64` rather than `android.util.Base64` so this whole
            // function is exercisable in a JVM unit test (minSdk is 26; the same
            // choice and the same reason as `V2FileKeyMessage.kt:19-22`). The basic
            // decoder is strict — it rejects `-`/`_`, a bad final quantum and any
            // non-alphabet character — which is what `Data(base64Encoded:)` does.
            val decodes = try {
                java.util.Base64.getDecoder().decode(s)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
            return if (decodes) s else key
        }

        /**
         * Protocol traffic the peer must process but must never be alerted about.
         *
         * ONE catalog for every send path — the V2 wake push AND the legacy
         * `VPSClient.sendMessage(suppressPush = …)` sites. Android used to decide
         * this per call site, so each new control payload had to remember to opt
         * out and several never did: a read receipt, a profile sync or a wallpaper
         * push woke the far phone with "New Message" and no message behind it.
         * iOS asks the same question once, at its single `notifyVPS` funnel
         * (`OSHI/MessageManager.swift:4664-4677`) and again for V2
         * (`MessageManager+V2.swift:392-398`) off the SAME list.
         *
         * The second term is not redundant: live-location update/stop pings share
         * the `📍LOCATION📍` prefix with the initial share and can only be told
         * apart by decoding the payload.
         */
        /**
         * __PUSH_SPAM_2026_09_17__ Decided by the CATALOG
         * ([OSHIControlPayload.suppressesPush]), not by the hand-written list that
         * used to live here. The list had drifted: it was missing `🔧ACTION🔧`
         * (every edit / delete / pin / unpin), `📢GROUP_UPDATE📢`,
         * `__SESSION_RESET__`, and the bare group-sync JSON — so a typo correction
         * rang the peer's iPhone, and the PERIODIC sync traffic rang it with no user
         * action at all. Two lists for one decision is exactly how that happens;
         * there is now one, shared with the renderer, and it is the same set iOS
         * suppresses.
         *
         * `LocationMessage` keeps its own rule: a live-location UPDATE is prefixed
         * identically to the initial share and can only be told apart by decoding
         * the payload, which the catalogue cannot do from a prefix.
         */
        internal fun isSilentControlContent(content: String): Boolean =
            OSHIControlPayload.suppressesPush(content) ||
                LocationMessage.isSilentPushContent(content)

        /**
         * Is this msgId a progressive-reveal layer, and which one?
         *
         * An armed reveal sends the SAME photo N times, blurriest first. Layer 0
         * travels under the bare `revealId` and layer k>0 under `"<revealId>#k"`
         * (iOS `TimedReveal.swift:238`), precisely so a queue that dedups by id does
         * not drop the later layers. `dispatchV2InboundFile` uses this to decide
         * INSERT (layer 0, a new bubble) versus UPDATE (layer k>0, the same bubble
         * sharpening). Without it every envelope inserts and the thread shows five
         * photo bubbles for one photo.
         *
         * The split is verified by RE-ENCODING through [V2RevealPlan.layerMsgId] —
         * the one place that convention is written down — so the two cannot drift.
         *
         * ⚠️ This reads the ID, not the wire fields, so it is now the FALLBACK — see
         * [revealLayerFor]. It still matters: a peer that sends the ladder without the
         * four scalars (an older build, or a plan that failed validation) is exactly
         * the N-bubble case, and the id convention still collapses it.
         *
         * @return `revealId to layerIndex`, or null for an ordinary message id.
         */
        internal fun revealLayerOf(msgId: String): Pair<String, Int>? {
            val hash = msgId.lastIndexOf('#')
            if (hash <= 0 || hash == msgId.length - 1) return null
            val k = msgId.substring(hash + 1).toIntOrNull() ?: return null
            // Layer 0 is the BARE id, never `#0`, and iOS caps a ladder at 64 rungs
            // (`V2RevealPlan.MAX_LAYERS`), so anything outside 1..63 is not a layer.
            if (k < 1 || k >= com.oshi.messenger.network.v2.V2RevealPlan.MAX_LAYERS) return null
            val revealId = msgId.substring(0, hash)
            if (com.oshi.messenger.network.v2.V2RevealPlan.layerMsgId(revealId, k) != msgId) return null
            return revealId to k
        }

        /**
         * Which carrier row this envelope must REWRITE, if any — the wire plan first,
         * the msgId convention second.
         *
         * `V2Inbound.File` now carries the four validated `reveal*` scalars, so the
         * common case is authoritative: the sender NAMES the row (`revealId`) and the
         * rank (`revealLayerIndex`), and neither has to be inferred from a string. That
         * matters beyond tidiness — the plan also carries `layerCount` and `totalMs`,
         * which the countdown capsule needs and an id can never supply.
         *
         * Layer 0 returns null: it CREATES the bubble like any other media message.
         *
         * @return `revealId to layerIndex` for a layer that must update an existing
         *   row, or null when this envelope is an ordinary insert.
         */
        internal fun revealLayerFor(
            plan: com.oshi.messenger.network.v2.V2RevealPlan?,
            msgId: String,
        ): Pair<String, Int>? = when {
            plan == null -> revealLayerOf(msgId)
            plan.layerIndex > 0 -> plan.revealId to plan.layerIndex
            else -> null
        }
    }

    private val messageDao = database.messageDao()
    private val contactDao = database.contactDao()

    private val scope = CoroutineScope(Dispatchers.IO)

    init { live = this }   // __BLOCKED_NOTICE_SWITCH_2026_09_23__ see noticeBlockedCaller

    init {
        // __REACTION_WAS_SILENT_2026_11_03__ (Anatole) "réagir à un message
        // avec un émoji ne déclenche pas de notification."
        //
        // Registered ONCE, on the manager's single inbound hook, rather than at
        // each of the four receive funnels — the same shape that left group
        // receipts working only over the relay.
        //
        // Only a reaction to a message WE sent is worth a notification: being
        // told that someone reacted to their own message, or to a third
        // party's in a group, is noise. The repository is the only side that
        // can tell, because only it can look the message up.
        reactionManager.onIncomingReaction = { messageId, emoji, reactorKey ->
            scope.launch {
                runCatching {
                    val target = messageDao.getMessageById(messageId)
                    val fromMyself = myPublicKey.isNotEmpty() &&
                        canonicalIdentity(reactorKey) == myPublicKey
                    if (target != null && target.isFromMe && !fromMyself) {
                        // __RECEIVED_REACTION_NO_BUZZ_2026_11_16__ posted SILENTLY (no
                        // sound, no vibration): a sync burst of reactions must not buzz
                        // the phone once per reaction. Haptics stay on my own taps.
                        showMessageNotification(
                            reactorKey,
                            emoji + " " + context.getString(R.string.reaction_notification),
                            silent = true
                        )
                        // __REACTION_WAS_SILENT_2026_11_04__ …and the
                        // conversation list, which draws `lastMessage.content`
                        // and so had nothing to show for a reaction however
                        // recent it was. Kept beside the conversation rather
                        // than inserted as a message row: a synthetic row would
                        // appear in the thread, count towards unread, sync to
                        // other devices and land in the exported transcript.
                        LastReactionStore.record(
                            target.recipientPublicKey, emoji, System.currentTimeMillis()
                        )
                    }
                }.onFailure { Log.w(TAG, "reaction notification: ${it.message}") }
            }
        }
    }

    // 🔧 FIX: Mutex to serialize message decryption (DoubleRatchet is NOT thread-safe)
    private val decryptionMutex = Mutex()

    // Track messages that failed decryption to avoid re-processing on every poll
    private val failedMessageIds = Collections.synchronizedSet(mutableSetOf<String>())

    // My public key
    private var myPublicKey: String = ""

    // =========================================================================
    // STEGANOGRAPHY & COVERT CHANNEL SETTINGS (read from DataStore)
    // =========================================================================

    private val stegoEnabledKey = booleanPreferencesKey("steganography_enabled")
    private val covertEnabledKey = booleanPreferencesKey("covert_channel_enabled")
    // __STEGO_SWITCH_SIDE_EFFECT_2026_11_18__ (audit finding 2) The old "Image
    // Steganography" switch wrote `steganography_enabled`, and a check here then
    // THREW on every non-V2 send — Bluetooth / LoRa mesh included — while the
    // switch promised a feature that did not exist. Both are gone: the switch was
    // removed from Settings and nothing on any send path reads that key any more.
    // The real feature is "Disguise as photo" (service/stego, docs/stego/STEGO-v1.md),
    // an explicit per-message action, never a transport side effect.

    private val covertPreferredKey = stringPreferencesKey("covert_preferred_channel")

    // Privacy toggles that were DEAD — persisted by SettingsViewModel but read by
    // nothing, so a user who turned them OFF still broadcast the signal (a real
    // privacy leak and an iOS-parity gap; iOS gates all three). Keys must match
    // SettingsViewModel exactly. Default true = matches iOS's default-on.
    private val readReceiptsKey = booleanPreferencesKey("read_receipts_enabled")
    private val lastSeenKey = booleanPreferencesKey("last_seen_enabled")

    private suspend fun isEnabled(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>): Boolean =
        try { context.dataStore.data.first()[key] ?: true } catch (_: Exception) { true }

    /**
     * `steganography_enabled` — read ONLY here, and deliberately never consulted by
     * any send or receive path.
     *
     * ⚠️ PARITY (2026-08-13 media-steg audit §4). On iOS this same toggle
     * (`SteganographyManager.swift:530`) is read exclusively by its own settings view
     * (`:569-599,695,720-723`); `grep -rn "steganographyEnabled" --include="*.swift"`
     * returns NO hit in any send or receive path. Text steganography on iOS runs only
     * inside the covert-relay transport (`CovertChannelManager.swift:806-807`, reached
     * from `MessageManager.swift:4052 sendCovertViaRelay`, gated on
     * `covertChannelEnabled`). A message on the normal iMessage-style path is NEVER
     * stego-wrapped.
     *
     * Android used to apply text-stego to EVERY outgoing 1:1 text message, which meant
     * an Android user with the toggle on silently sent iPhones a block of generated
     * cover text — "Hey! How have you been?" — instead of their message, with no error
     * on either side, for as long as the toggle stayed on. That send-path application
     * is removed; the toggle is now exactly as inert as iOS's.
     */
    @Suppress("unused")
    private suspend fun isSteganographyEnabled(): Boolean {
        return try {
            context.dataStore.data.first()[stegoEnabledKey] ?: false
        } catch (_: Exception) { false }
    }

    private suspend fun isCovertChannelEnabled(): Boolean {
        return try {
            context.dataStore.data.first()[covertEnabledKey] ?: false
        } catch (_: Exception) { false }
    }

    private suspend fun getCovertPreferredChannel(): String {
        return try {
            context.dataStore.data.first()[covertPreferredKey] ?: "auto"
        } catch (_: Exception) { "auto" }
    }

    /**
     * The steganography secret is the RAW X25519 shared secret — nothing more.
     *
     * 🔧 FIX (audit §1 "Key derivation", §3.2). This used to return
     * `Base64(SHA256(sharedSecret ‖ "OSHI_STEGO_V1"))` and hand that STRING to
     * `TextSteganography`, which then hashed it again with the "TEXT_STEG_V1" label.
     * iOS applies exactly ONE hash to the raw bytes:
     *
     *   `encKey = SHA256(sharedSecret ‖ utf8("TEXT_STEG_V1"))`
     *   — `OSHI/TextSteganography.swift:383`
     *
     * and `sharedSecret` there is the raw 32-byte agreement output, not a string, not
     * base64, not re-hashed (`OSHI/MessageManager.swift:4044` →
     * `OSHI/IdentityManager.swift:489-491`, `sharedSecret.withUnsafeBytes { Data($0) }`).
     * Android's extra SHA256 + label + base64 layer meant nothing would ever decrypt
     * across platforms even once the framing was fixed.
     *
     * `cryptoManager.deriveSharedSecret` returns the same raw X25519 agreement bytes
     * (`CryptoManager.kt:237-248`), so this is now byte-identical to iOS.
     */
    @Suppress("unused")
    private fun deriveStegoSecret(recipientPublicKey: String): ByteArray =
        cryptoManager.deriveSharedSecret(recipientPublicKey)

    // Pinned messages: conversationKey -> messageId (one pin per conversation, matches iOS)
    private val _pinnedMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    val pinnedMessages: StateFlow<Map<String, String>> = _pinnedMessages.asStateFlow()

    /**
     * Look up the iOS peer for [recipientPublicKey] in the cross-platform mesh's connected list.
     * If absent but the peer was discovered via BLE/NSD, wait up to ~800 ms for the TCP/identity
     * handshake to land before giving up. This avoids the race where the very first message after
     * discovery loses to the handshake and gets routed to VPS/IPFS unnecessarily.
     */
    private suspend fun findIosPeerOrAwait(recipientPublicKey: String): CrossPlatformPeer? {
        // Use normalized (base64↔base64url, padding-stripped) equality. Without
        // this, a peer stored in `connectedPeers` as `eAbM…cyc=` won't match
        // a recipient query for `eAbM…cyc` and the send falls through to VPS
        // even though the iPhone is sitting on the same TCP/BLE link.
        crossPlatformMesh.findConnectedPeer(recipientPublicKey)?.let { return it }

        // Reachability fallback: if we just received a mesh message from this
        // peer, the route is in `routingTable` but the peer might not be in
        // `_connectedPeers` (handshake not yet completed end-to-end). The
        // socket IS open, so `crossPlatformMesh.sendMessage` would still
        // deliver. Synthesize a placeholder peer so the send-text routing
        // chooses the mesh branch instead of falling through to VPS — which
        // is exactly the "Android replies via IPFS right after a mesh
        // receive" symptom the user reported.
        if (crossPlatformMesh.isPeerReachable(recipientPublicKey)) {
            return CrossPlatformPeer(
                publicKey = recipientPublicKey,
                displayName = "",
                platform = "ios",
                ipAddress = null,
                port = null
            )
        }

        if (!crossPlatformMesh.isPeerDiscovered(recipientPublicKey)) return null
        repeat(4) {
            delay(200)
            crossPlatformMesh.findConnectedPeer(recipientPublicKey)?.let { return it }
            if (crossPlatformMesh.isPeerReachable(recipientPublicKey)) {
                return CrossPlatformPeer(
                    publicKey = recipientPublicKey,
                    displayName = "",
                    platform = "ios",
                    ipAddress = null,
                    port = null
                )
            }
        }
        return null
    }

    // ============================================================================
    // 🆕 WEB SESSION — group / profile / wallpaper / call sync helpers
    // ============================================================================

    /**
     * Convert a Group entity into the JSON shape expected by the web client.
     * Mirrors iOS `syncGroupsToWeb` payload entry.
     */
    private fun groupToJson(g: com.oshi.messenger.data.model.Group): JSONObject {
        val members = org.json.JSONArray().apply {
            for (pk in g.memberPublicKeys) {
                put(JSONObject().apply {
                    put("publicKey", pk)
                    put("alias", "")
                    put("isAdmin", g.adminPublicKeys.contains(pk))
                })
            }
        }
        val lastMsg = groupManager.groupMessages.value[g.id]?.lastOrNull()
        return JSONObject().apply {
            put("id", g.id)
            put("name", g.name)
            put("groupType", g.effectiveGroupType.value)
            put("adminPublicKey", g.adminPublicKeys.firstOrNull() ?: "")
            put("members", members)
            put("lastMessageTime", g.updatedAt)
            put("unreadCount", groupManager.groupMessages.value[g.id]?.count { !it.isRead && !it.isFromMe } ?: 0)
            put("isMuted", g.isMuted)
            g.groupImage?.let { put("groupPictureBase64", Base64.encodeToString(it, Base64.NO_WRAP)) }
            g.groupWallpaperData?.let { put("groupWallpaperBase64", Base64.encodeToString(it, Base64.NO_WRAP)) }
            lastMsg?.let { put("lastMessage", it.content) }
        }
    }

    private fun groupMessageToJson(m: com.oshi.messenger.data.model.GroupMessage): JSONObject {
        val mediaTypeStr = when (m.mediaType) {
            com.oshi.messenger.data.model.MediaType.IMAGE -> "image"
            com.oshi.messenger.data.model.MediaType.VIDEO -> "video"
            com.oshi.messenger.data.model.MediaType.AUDIO -> "audio"
            com.oshi.messenger.data.model.MediaType.DOCUMENT -> "file"
            com.oshi.messenger.data.model.MediaType.CONTACT -> "contact"
            com.oshi.messenger.data.model.MediaType.LOCATION -> "location"
            null -> null
        }
        return JSONObject().apply {
            put("id", m.id)
            put("groupId", m.groupId)
            put("senderPublicKey", m.senderPublicKey)
            put("content", m.content)
            put("timestamp", m.timestamp)
            put("isFromMe", m.isFromMe)
            put("isRead", m.isRead)
            mediaTypeStr?.let { put("mediaType", it) }
            m.mediaData?.let { d ->
                put("mediaSize", d.size)
                if (d.size <= 5_000_000) {
                    put("mediaData", Base64.encodeToString(d, Base64.NO_WRAP))
                } else {
                    put("mediaUrl", "/api/web/group-media/${m.id}")
                }
            }
        }
    }

    /** Push the entire group list to the web session. */
    fun syncGroupsToWebSession() {
        try {
            val groups = groupManager.groups.value.map { groupToJson(it) }
            webSessionManager.pushGroupSync(groups)
        } catch (e: Exception) {
            Log.w(TAG, "syncGroupsToWebSession failed: ${e.message}")
        }
    }

    /** Push messages of a specific group. */
    fun syncGroupMessagesToWebSession(groupId: String) {
        try {
            val msgs = (groupManager.groupMessages.value[groupId] ?: emptyList()).map { groupMessageToJson(it) }
            webSessionManager.pushGroupMessages(groupId, msgs)
        } catch (e: Exception) {
            Log.w(TAG, "syncGroupMessagesToWebSession failed: ${e.message}")
        }
    }

    /** Notify web session of a new group message. */
    fun notifyGroupNewMessageToWeb(m: com.oshi.messenger.data.model.GroupMessage) {
        try {
            val mediaTypeStr = when (m.mediaType) {
                com.oshi.messenger.data.model.MediaType.IMAGE -> "image"
                com.oshi.messenger.data.model.MediaType.VIDEO -> "video"
                com.oshi.messenger.data.model.MediaType.AUDIO -> "audio"
                com.oshi.messenger.data.model.MediaType.DOCUMENT -> "file"
                com.oshi.messenger.data.model.MediaType.CONTACT -> "contact"
                com.oshi.messenger.data.model.MediaType.LOCATION -> "location"
                null -> null
            }
            webSessionManager.notifyGroupNewMessage(
                messageId = m.id,
                groupId = m.groupId,
                senderPublicKey = m.senderPublicKey,
                content = m.content,
                timestamp = m.timestamp,
                isFromMe = m.isFromMe,
                isRead = m.isRead,
                mediaType = mediaTypeStr,
                mediaData = m.mediaData,
                // 📄 M-6, web leg. A group document already HAS its name on the row
                // (`GroupMessage.mediaFileName`); passing null saved it in the browser
                // as the bare message id.
                fileName = m.mediaFileName?.let {
                    com.oshi.messenger.util.AttachmentFileName.sanitize(it)
                } ?: webDownloadName(mediaTypeStr, m.content)
            )
        } catch (e: Exception) {
            Log.w(TAG, "notifyGroupNewMessageToWeb failed: ${e.message}")
        }
    }

    /** Push profile picture update to web. */
    fun pushProfilePictureToWeb(imageBytes: ByteArray?) {
        try { webSessionManager.pushProfilePicture(imageBytes) } catch (_: Exception) {}
    }

    /** Push 1-to-1 wallpaper update to web. */
    fun pushWallpaperToWeb(conversationKey: String, wallpaperData: ByteArray?, wallpaperType: String) {
        try { webSessionManager.pushWallpaper(conversationKey, wallpaperData, wallpaperType) } catch (_: Exception) {}
    }

    /** Push group picture update to web. */
    fun pushGroupPictureToWeb(groupId: String, imageBytes: ByteArray?) {
        try { webSessionManager.pushGroupPicture(groupId, imageBytes) } catch (_: Exception) {}
    }

    /** Push group wallpaper update to web. */
    fun pushGroupWallpaperToWeb(groupId: String, wallpaperData: ByteArray?) {
        try { webSessionManager.pushGroupWallpaper(groupId, wallpaperData) } catch (_: Exception) {}
    }

    /** Notify web of a call state transition. */
    fun pushCallStateToWeb(
        callId: String,
        state: String,
        peerPublicKey: String,
        peerName: String? = null,
        isVideo: Boolean = false,
        direction: String = "incoming",
        errorMessage: String? = null
    ) {
        try {
            webSessionManager.pushCallState(callId, state, peerPublicKey, peerName, isVideo, direction, errorMessage)
        } catch (_: Exception) {}
    }

    private fun mediaTypeToMime(typeName: String?): String = when (typeName?.uppercase()) {
        "IMAGE" -> "image/jpeg"
        "VIDEO" -> "video/mp4"
        "AUDIO" -> "audio/m4a"
        "DOCUMENT" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    // =========================================================================
    // FILE-BACKED MEDIA ATTACHMENTS
    // =========================================================================

    /**
     * Cap on the size of a `mediaData` blob we'll store inline in the messages table.
     * Above this, Android's CursorWindow (~2 MB hard limit) refuses to load the row and the
     * app crashes with `SQLiteBlobTooBigException` on any subsequent SELECT * that touches
     * the row.
     */
    private val MAX_INLINE_MEDIA_BYTES = 1_500_000

    /**
     * The file store the old comment here promised and nobody ever wrote.
     *
     * 🔧 P0 FIX (2026-08-13 media-steg audit, "Local persistence of the blob"):
     * `sanitizeMediaForInsert` used to just `Log.w` and set `mediaData = null`,
     * "expecting" a `MediaAttachmentManager` that did not exist anywhere in the tree
     * (`grep -rn "MediaAttachmentManager" --include="*.kt"` returned exactly one hit:
     * that comment). The blob was applied on all insert sites, send AND receive, so
     * every video and every photo above 1.5 MB delivered over the wire fine and then
     * rendered as an EMPTY BUBBLE on both the sender's and the recipient's device as
     * soon as the row round-tripped through Room.
     *
     * iOS keeps the bytes on disk, keyed by the attachment id, and reads them back
     * lazily: `OSHI/MediaManager.swift:737-771` (`Attachments/<id>.dat`) and
     * `OSHI/FileStorage.swift:330-357` (`saveMedia`/`loadMedia`/`deleteMedia`).
     * [MediaAttachmentStore] is that lifecycle, keyed by messageId so no schema
     * migration is needed to carry the reference on the row.
     */
    private val mediaStore: com.oshi.messenger.service.media.MediaAttachmentStore by lazy {
        com.oshi.messenger.service.media.MediaAttachmentStore(context.filesDir)
    }

    /**
     * The `filename` an outbound attachment is announced under.
     *
     * 🔧 FIX (2026-08-24 feature check). This used to be `"$messageId.$ext"`
     * unconditionally, so the name the user actually picked never left the device:
     * a `Contrat-2026.pdf` reached the peer as `9f3c1b2a-….dat`. iOS puts the field
     * straight on the bubble (`MessageManager+V2.swift:1017`,
     * `msg.mediaFileName = filename`) and its own sender sends the true name
     * (`v2FileName(for:)`, `:1071-1073`), so this was an Android-only regression —
     * and it broke Android→Android too, because [dispatchV2InboundFile] stores
     * `inbound.filename` as the document's displayed name.
     *
     * Only DOCUMENT actually carries a user-chosen name today; the synthetic form
     * stays correct for camera photos and voice notes, which never had one. The
     * name is sanitized on the way out ([AttachmentFileName.sanitize]) because the
     * receiver hands it to a `File` constructor.
     */
    private fun wireFileName(
        messageId: String,
        mediaType: MediaType,
        requested: String?,
        /** __GIF_WIRE_TYPE_2026_09_13__ Les octets SONT un GIF: l'extension synthétisée
         *  doit le dire, sinon un GIF sans nom d'origine part en `<uuid>.jpg` et le
         *  destinataire qui se fie à l'extension l'enregistre sous un nom qui ment. */
        isGif: Boolean = false
    ): String {
        val ext = when (mediaType) {
            MediaType.IMAGE -> if (isGif) "gif" else "jpg"
            MediaType.VIDEO -> "mp4"
            MediaType.AUDIO -> "m4a"
            MediaType.CONTACT -> "vcf"
            else -> "dat"
        }
        return com.oshi.messenger.util.AttachmentFileName.forWire(messageId, ext, requested)
    }

    /**
     * Write an over-cap blob to the file store, then strip it from the row.
     * The messageId IS the reference — nothing new has to be persisted on the row.
     */
    private fun sanitizeMediaForInsert(message: Message): Message {
        val data = message.mediaData ?: return message
        if (data.size <= MAX_INLINE_MEDIA_BYTES) return message
        val saved = mediaStore.save(message.id, data)
        if (saved) {
            Log.d(TAG, "💾 mediaData ${data.size / 1024} KB → file store (row keeps id ${message.id.take(8)})")
        } else {
            // Losing the bytes is bad, but crashing every later SELECT is worse.
            Log.e(TAG, "❌ Could not persist ${data.size / 1024} KB attachment for ${message.id.take(8)} — bubble will be empty")
        }
        return message.copy(mediaData = null)
    }

    /**
     * Every `@Update` of a message goes through here, and that is the whole point.
     *
     * [sanitizeMediaForInsert] kept oversized blobs off the row on INSERT and nothing kept
     * them off on UPDATE — so the media send path did this:
     *
     * ```
     * val message = Message(…, mediaData = compressedData, …)   // 4.3 MB held in memory
     * messageDao.insert(sanitizeMediaForInsert(message))        // stored with mediaData = null
     * …
     * messageDao.update(message.copy(deliveryMethod = VPS, isDelivered = true))  // put it BACK
     * ```
     *
     * Room's `@Update` writes every column and `.copy()` carries `mediaData` with it, so the
     * delivery-status write undid the sanitizer forty lines later. The next `SELECT` over
     * `messages` then threw `SQLiteBlobTooBigException` on the main dispatcher — **on every
     * launch, for ever**, because that read happens before any screen can be drawn. Observed
     * on a shipped 1.6.7/45 handset on 2026-08-26: `📤 Media sent via OSHI V2 blob (VIDEO,
     * 4349268B)` at 12:45:26.693, process dead at 12:45:26.744. Fifty-one milliseconds, and
     * the app could not be opened again.
     *
     * It was never one call site: every `update(message.copy(deliveryMethod = …))` on both the
     * VPS and the mesh branch had the same shape, twenty-three in all. Auditing them one by one
     * is how a twenty-fourth reintroduces it, so the cap is enforced HERE, once, on the only
     * path to `messageDao.update`. Idempotent: a row whose bytes already went to the file store
     * carries `mediaData = null` and comes back unchanged.
     *
     * Rows already poisoned by the old behaviour are cleared by `AppDatabase`'s open-time
     * repair — this stops new ones, that clears the old.
     */
    private suspend fun updateMessage(message: Message) = messageDao.update(sanitizeMediaForInsert(message))


    /**
     * Same, for an inbound V2 blob that is ALREADY on disk — the last mile of M-2.
     *
     * [sanitizeMediaForInsert] takes a `ByteArray`, so routing a 150 MB video through
     * it means allocating the whole plaintext just to write it straight back out. The
     * download already streams to a scratch file one 2 MiB chunk at a time
     * (`OSHICryptoV2Streaming`), so the only thing left holding a whole-file copy was
     * this hand-off. Here the file is MOVED into the store (a rename on the same
     * mount: no read at all) and only media small enough to live on the row is ever
     * read into memory.
     *
     * The caller must not touch `inbound.payload` afterwards — it has been consumed.
     */
    private fun attachInboundPayload(
        message: Message,
        inbound: com.oshi.messenger.network.v2.V2Inbound.File,
    ): Message {
        if (inbound.size <= MAX_INLINE_MEDIA_BYTES) {
            return message.copy(mediaData = inbound.readBytes())
        }
        if (mediaStore.save(message.id, inbound.payload, moveSource = true)) {
            Log.d(TAG, "💾 inbound blob ${inbound.size / 1024} KB → file store (row keeps id ${message.id.take(8)})")
        } else {
            // Losing the bytes is bad, but crashing every later SELECT is worse.
            Log.e(TAG, "❌ Could not persist ${inbound.size / 1024} KB attachment for ${message.id.take(8)} — bubble will be empty")
        }
        return message.copy(mediaData = null)
    }

    /**
     * Bytes for a message's attachment, wherever they live: inline on the row for
     * small media, on disk for anything above [MAX_INLINE_MEDIA_BYTES].
     * This is the single read path every consumer should use.
     */
    fun resolveMediaData(message: Message): ByteArray? =
        message.mediaData ?: mediaStore.load(message.id)

    /** Same, by id — for callers that only hold a messageId. */
    suspend fun loadMediaData(messageId: String): ByteArray? =
        // __CHAT_OPEN_PERF_2026_11_14__ `resolveMediaData` reads and DECRYPTS a file for
        // anything over 1.5 MB, and the bubble calls this from a `LaunchedEffect` — the
        // main thread. Room's suspend DAO hops off Main by itself; the file read did not.
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            messageDao.getMessageById(messageId)?.let { resolveMediaData(it) }
        }

    /**
     * __DOC_STREAM_2026_11_11__ Copy a message's attachment into [out] WITHOUT holding
     * it in memory — the save path of a document bubble. Inline (small) bytes are
     * written as they are; file-backed ones stream through the decrypting reader in
     * 64 KB steps. False when there is nothing to copy. Throws on I/O or on a
     * tampered blob, so a short file is never reported as saved.
     */
    suspend fun copyMediaTo(messageId: String, out: java.io.OutputStream): Boolean {
        val row = messageDao.getMessageById(messageId)
        row?.mediaData?.let { out.write(it); return true }
        val input = mediaStore.openStream(messageId) ?: return false
        input.use { it.copyTo(out, 64 * 1024) }
        return true
    }

    /**
     * Re-attach file-backed blobs to a page of messages before it reaches the UI.
     *
     * Budgeted, deliberately. iOS holds decrypted media in an NSCache limited to
     * `countLimit = 60` / `totalCostLimit = 96 MB`
     * (`OSHI/MediaAttachmentCache.swift:37-38`) and drops it under memory pressure
     * (`:44-51`). Hydrating an unbounded conversation would pull every video in the
     * thread onto the heap on EVERY Flow emission, so the same two limits are applied
     * here, newest-first: recent media renders, an ancient 200 MB backlog does not OOM
     * the app. The store's own LRU means repeat emissions hit memory, not disk.
     */
    private fun hydrateMedia(messages: List<Message>): List<Message> =
        com.oshi.messenger.service.media.MediaHydration.hydrate(messages, mediaStore)

    /**
     * Drop orphaned attachment files — blobs whose message row is gone.
     *
     * A conversation delete (`deleteAllForContact`) removes rows in bulk and would
     * otherwise leak every video in that chat forever. Local analogue of iOS's
     * retention sweep (`OSHI/PinataCleanupService.swift:23,262,305`). Best-effort and
     * off the main thread; a failure here is never fatal.
     */
    fun purgeOrphanAttachments() {
        scope.launch {
            try {
                var removed = mediaStore.sweepTempFiles()
                for (id in mediaStore.listStoredIds()) {
                    // Ask the message table about THIS id. A DB error aborts the sweep —
                    // it must never be read as "this message is gone", which would wipe
                    // every attachment on the device.
                    val row = messageDao.getMessageById(id)
                    if (row == null) {
                        mediaStore.delete(id)
                        removed++
                    }
                }
                if (removed > 0) Log.d(TAG, "🧹 Purged $removed orphaned attachment file(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Attachment purge skipped: ${e.message}")
            }
        }
    }

    /** Notify web session of new/sent message — now includes media metadata + payload. */
    private fun notifyWebSession(message: Message) {
        val webMediaType = when (message.mediaType) {
            com.oshi.messenger.data.model.MediaType.IMAGE -> "image"
            com.oshi.messenger.data.model.MediaType.VIDEO -> "video"
            com.oshi.messenger.data.model.MediaType.AUDIO -> "audio"
            com.oshi.messenger.data.model.MediaType.DOCUMENT -> "file"
            com.oshi.messenger.data.model.MediaType.CONTACT -> "contact"
            com.oshi.messenger.data.model.MediaType.LOCATION -> "location"
            null -> null
        }
        webSessionManager.notifyNewMessage(
            messageId = message.id,
            content = message.content,
            senderPublicKey = message.senderPublicKey,
            recipientPublicKey = message.recipientPublicKey,
            isFromMe = message.isFromMe,
            timestamp = message.timestamp,
            mediaType = webMediaType,
            mediaData = message.mediaData,
            // 📄 M-6, web leg — see [webDownloadName].
            fileName = webDownloadName(message.mediaType?.name, message.content)
        )
    }

    /**
     * 🔐 Check, on arrival, that this contact is still the contact we had.
     *
     * `SafetyNumberManager.hasKeyChanged` has always been able to notice an identity-key
     * substitution; until now its only caller outside that class was the safety-number
     * screen, so it noticed only for a user who already suspected something and went looking.
     * This is the call that makes it proactive, and it is placed here — in the four inbound
     * transport callbacks, before `decryptionMutex` — for two reasons:
     *
     *  * it is the one place every inbound message passes through regardless of transport
     *    (mesh, cross-platform, VPS poll, V2 relay) and regardless of what the payload turns
     *    out to be, so nothing can arrive unchecked;
     *  * it is OUTSIDE the mutex, so a `SharedPreferences` read and a SHA-256 never sit in
     *    front of the serialized DoubleRatchet work.
     *
     * Deliberately not inside `showMessageNotification`: that returns early when the thread
     * is on screen and when the payload is pure protocol, and a key change is news in both
     * cases.
     *
     * Canonical spelling on both sides — the key comes straight off a transport, while
     * `verified_…` was written from a contact row. Comparing the two spellings would file the
     * same person under two baselines and report a change every time the transport changed.
     *
     * Not `suspend` and not dispatched: every call site is already on [scope]
     * (`Dispatchers.IO`). The result is published on `safetyNumberManager.keyChanges`.
     */
    private fun noteInboundIdentity(senderPublicKey: String) {
        if (senderPublicKey.isBlank() || myPublicKey.isBlank()) return
        runCatching {
            safetyNumberManager.noteObservedIdentity(
                myPublicKey = myPublicKey,
                theirPublicKey = canonicalIdentity(senderPublicKey)
            )
        }.onFailure { Log.w(TAG, "Identity-key check skipped: ${it.message}") }
    }

    /** Show local notification for incoming 1-on-1 message */
    private suspend fun showMessageNotification(
        senderPublicKey: String,
        preview: String,
        silent: Boolean = false,
    ) {
        try {
            // 👀 The user is READING this thread right now. A banner here is pure
            // friction: the message is already on screen and already being marked
            // read by `ChatViewModel`'s unread watcher, so the notification would
            // only be something to dismiss. iOS never gets here because its
            // foreground path is `willPresent` on a thread the user is looking at;
            // Android posts local notifications from the poll path, so the gate has
            // to be explicit. `isDirectActive` is true only while the app is
            // foregrounded AND this exact thread is on screen.
            if (com.oshi.messenger.service.ActiveConversation.isDirectActive(senderPublicKey)) {
                Log.d(TAG, "👀 Thread with ${senderPublicKey.take(8)}… is on screen — no banner")
                return
            }
            // __MUTE_A_CONVERSATION_2026_11_13__ (Anatole N8) muted from the list's
            // long-press sheet or the chat's ⋮ sheet. The message is already stored;
            // only the banner is withheld.
            if (com.oshi.messenger.data.model.ConversationMutes.get(context).contains(senderPublicKey)) {
                Log.d(TAG, "🔕 Thread with ${senderPublicKey.take(8)}… is muted — no banner")
                return
            }
            // 🔑 Contact rows are stored in the canonical spelling; the key handed in
            // here comes straight off a transport. Look it up canonically or the
            // notification falls back to a hex-looking key instead of the alias.
            val contact = contactDao.getContactByPublicKey(canonicalIdentity(senderPublicKey))
                ?: contactDao.getContactByPublicKey(senderPublicKey)
            // __SHARED_NICKNAME_2026_09_22__ user alias > peer's shared nickname > old fallback.
            val senderName = com.oshi.messenger.data.model.PeerNickname.userAlias(contact?.alias, senderPublicKey)
                ?: com.oshi.messenger.data.model.PeerNicknameStore.lookup(senderPublicKey)
                ?: contact?.alias ?: "${senderPublicKey.take(6)}...${senderPublicKey.takeLast(4)}"
            // H-1. This used to run the preview through `MessageSanitizer` and then
            // substitute a hardcoded English "new message" literal for an empty
            // result. Both halves were wrong, in opposite directions:
            //
            //  * `MessageSanitizer` is the retired fourth copy of the prefix
            //    catalog (`OSHIControlPayload.kt:12-18`). Its reply branch tests
            //    the dead Android-legacy spelling "↩️REPLY↩️" while the wire
            //    sentinel is `💬REPLY💬`, and it has no `📢GROUP_UPDATE📢` branch
            //    at all. Both open with an emoji rather than an opening brace, so
            //    none of its JSON catch-alls fired either and it returned the payload
            //    VERBATIM — a reply put `💬REPLY💬{"content":…,"replyTo":{…}}` on
            //    the peer's lock screen, a roster update put the whole member and
            //    admin list there.
            //  * the empty-string fallback after it then inverted the sanitiser's only
            //    correct answer: `""` is how it says "pure protocol, never show
            //    this". A read receipt, a delivery ack, a typing ping, a reaction,
            //    a key rotation and a multi-device sync envelope each raised a
            //    banner with nothing behind it.
            //
            // One catalog, asked on the RAW string (never on a sanitised one —
            // detection on a rewritten string is how a sanitiser gets silently
            // negated). iOS `PushNotificationManager.swift:104-111`.
            val decision = NotificationPresentation.decideCapped(
                raw = preview,
                labels = OSHIControlPayload.labels(context),
                attachmentBody = context.getString(R.string.notification_sent_attachment)
            )
            // 🤫 Pure protocol. Post NOTHING — no banner, no sound, no shade entry.
            // `showMessageNotification` has no other job, so returning is the whole
            // suppression.
            if (decision !is NotificationPresentation.Decision.Show) return
            // __ONE_BANNER_PER_MESSAGE_2026_09_23__ Name + generic body; the decrypted
            // text only if the user explicitly enabled previews. Same id as the FCM
            // banner, and no re-post if that banner went up moments ago.
            val displayPreview = com.oshi.messenger.service.MessageBanner.body(context, decision.body)
            // __NOTIFICATION_HAS_NO_FACE_2026_10_27__ The sender's own photo, from the
            // contacts table. Null when they have none: `setLargeIcon(null)` is fine.
            val avatar = runCatching {
                com.oshi.messenger.service.MessageBanner.avatar(
                    database.contactDao().getContactByPublicKey(senderPublicKey)?.profilePicture
                )
            }.getOrNull()
            val bannerId = com.oshi.messenger.service.MessageBanner.contactNotificationId(senderPublicKey)
            if (com.oshi.messenger.service.MessageBanner.shownRecently(context, bannerId)) {
                Log.d(TAG, "🔕 banner for ${senderPublicKey.take(8)}… already up — not posting a second one")
                return
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("senderKey", senderPublicKey)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, senderPublicKey.hashCode(), intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val notification = NotificationCompat.Builder(context, FCMService.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(senderName)
                .setContentText(displayPreview)
                .setLargeIcon(avatar)
                .setAutoCancel(true)
                .setSound(if (silent) null else soundUri)
                .setSilent(silent)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // __BOT_E2E_2026_09_23__ Lock screen: generic public version, as FCMService.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, FCMService.CHANNEL_MESSAGES)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(R.string.notification_new_message))
                        .build()
                )
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(bannerId, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show message notification: ${e.message}")
        }
    }

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    @Volatile
    private var isInitialized = false

    /**
     * __WEAR_BRIDGE_2026_11_17__ True once [initialize] ran (identity loaded,
     * pipeline wired). The watch bridge refuses to read or send before that.
     */
    val isPipelineInitialized: Boolean get() = isInitialized

    fun initialize(publicKey: String) {
        // __COVER_FAIL_CLOSED_2026_11_18__ messages parked for the disguised path by a
        // previous process have no retry any more: show them as "Not delivered".
        scope.launch { CoverOutbox.recoverAfterRestart(context).forEach { resumeOrFailParked(it) } }
        // Keep the legacy relay's door (VPSClient) in step with the cover switch.
        scope.launch {
            try { context.dataStore.data.collect { CoverOutbox.coverModeOn = it[covertEnabledKey] == true } }
            catch (e: Exception) { Log.w(TAG, "cover flag collector stopped: ${e.message}") }
        }
        if (isInitialized) {
            Log.d(TAG, "📨 MessageRepository already initialized, skipping duplicate init")
            return
        }
        isInitialized = true
        // Our own key is the `recipientPublicKey` of every received row and the
        // `senderPublicKey` of every sent one. Canonicalise it once, here, so the
        // whole table agrees with the receive-side stamp below (no-op in practice —
        // `CryptoManager` emits standard padded base64 — but it makes the invariant
        // hold by construction rather than by luck).
        myPublicKey = canonicalIdentity(publicKey)

        // 🚫 Seed the blocked-key cache before the first poll lands, so a blocked
        // contact's message is dropped on the very first inbound batch and not just
        // after the cache warms.
        refreshBlockedKeyCache()

        // 🧹 Drop attachment files whose message row is gone (bulk conversation
        // deletes leave them behind). Mirrors iOS's retention sweep
        // (`OSHI/PinataCleanupService.swift:23,262,305`) for the local copy.
        purgeOrphanAttachments()

        // 📡 Attach the LoRa radio to this pipeline. Until this ran, everything
        // the radio received went nowhere.
        startLoRaBridge()

        // 🔧 Make an INBOUND 🔧ACTION🔧 stick.
        //
        // `MessageActionsManager.handleIncomingAction` (`:340-385`) only mutated its own
        // `_editedMessages` / `_deletedMessageIds` StateFlows and fanned out to
        // `editCallbacks` / `deleteCallbacks` — and `addEditCallback` / `addDeleteCallback`
        // (`:598,606`) had ZERO callers, while no screen read those flows
        // (`grep -rn "editedMessages\|deletedMessageIds" ui/` → nothing). So an iPhone's
        // edit or unsend arrived, parsed correctly, and changed nothing the user could
        // see; the bubble still rendered `editedContent` / `isDeletedForEveryone` from
        // Room. These two registrations are the missing write.
        messageActionsManager.addEditCallback { messageId, newContent ->
            scope.launch { applyRemoteEdit(messageId, newContent) }
        }
        messageActionsManager.addDeleteCallback { messageId ->
            scope.launch { applyRemoteDeleteForEveryone(messageId) }
        }

        // 📡 R-2: give reactions the SAME transport fan-out ordinary messages get.
        // Until this line, `MessageReactionManager` held a `VPSClient` and nothing else,
        // so a reaction to a mesh-only peer was dropped silently.
        reactionManager.transportSender = { recipientPublicKey, encrypted, plaintext ->
            // __CONTROL_PAYLOADS_NEED_THE_V2_LANE_2026_10_29__ the plaintext rides along so
            // the relay leg can prefer V2; the mesh legs still send `encrypted`.
            dispatchControlPayload(recipientPublicKey, encrypted, "reaction", plaintext = plaintext)
        }

        // 📌 The third hole of the SAME defect (Z-3 of the 2026-08-24 react-reply audit).
        //
        // `handleIncomingAction` parses an inbound `pinMessage` / `unpinMessage`
        // (`MessageActionsManager.kt` `"pinMessage"` / `"unpinMessage"` branches) and
        // dispatches it into `pinCallbacks` — a list `addPinCallback` never appended to,
        // because it had ZERO callers app-wide. So an iPhone could pin a message and
        // Android could not even DISPLAY it: the payload arrived, decoded, and was
        // dropped into an empty list. This registration is the missing write, and it is
        // what makes `ChatViewModel.pinnedMessage` (and the banner it feeds) ever
        // non-null from a remote pin.
        //
        // `targetMessageId` is the peer's id for the message; the conversation key is
        // that peer's public key, which the callback receives as `senderPublicKey`.
        // A null sender means unpin (`MessageActionsManager` passes null on that branch),
        // and unpin has to resolve the conversation from the message row instead.
        messageActionsManager.addPinCallback { targetMessageId, senderPublicKey ->
            scope.launch {
                try {
                    if (senderPublicKey != null) {
                        pinMessage(senderPublicKey, targetMessageId)
                        Log.d(TAG, "📌 Applied remote pin ${targetMessageId.take(8)} from ${senderPublicKey.take(8)}")
                    } else {
                        val conversationKey = messageDao.getMessageById(targetMessageId)
                            ?.let { peerKeyOf(it) }
                            ?: _pinnedMessages.value.entries
                                .firstOrNull { it.value.equals(targetMessageId, ignoreCase = true) }?.key
                        if (conversationKey != null) {
                            unpinMessage(conversationKey)
                            Log.d(TAG, "📌 Applied remote unpin ${targetMessageId.take(8)}")
                        } else {
                            Log.w(TAG, "📌 Remote unpin for unknown message ${targetMessageId.take(8)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to apply remote pin: ${e.message}")
                }
            }
        }

        // Open the relay WebSocket so notifyNewMessage(...) can actually fan out to any
        // browser session linked to this user. Without this the WebSocket stayed closed
        // outside the QR-pair flow and the web client never received messages typed on the phone.
        try {
            webSessionManager.connectToWeb()
        } catch (e: Exception) {
            Log.w(TAG, "WebSession connect at init failed: ${e.message}")
        }

        // 🆕 Hook into group state flows so any change is mirrored to web sessions.
        scope.launch {
            try {
                groupManager.groups.collect { _ ->
                    syncGroupsToWebSession()
                }
            } catch (_: Exception) {}
        }
        scope.launch {
            try {
                var prev: Map<String, List<com.oshi.messenger.data.model.GroupMessage>> = emptyMap()
                groupManager.groupMessages.collect { newMap ->
                    for ((gid, msgs) in newMap) {
                        val prevList = prev[gid] ?: emptyList()
                        if (msgs.size > prevList.size) {
                            // Diff new messages
                            val knownIds = prevList.map { it.id }.toSet()
                            for (m in msgs) {
                                if (m.id !in knownIds) notifyGroupNewMessageToWeb(m)
                            }
                        }
                    }
                    prev = newMap
                }
            } catch (_: Exception) {}
        }

        // 🆕 Wire web → mobile call control. Web client emits web_call_initiate / accept / reject;
        // we forward to CallManager so the device starts/answers/ends the call.
        webSessionManager.onWebCallControl = { action, peer, isVideo ->
            Log.d(TAG, "📞 Web call control: action=$action peer=${peer?.take(12)} isVideo=$isVideo")
            scope.launch {
                try {
                    when (action) {
                        "initiate" -> {
                            if (peer.isNullOrEmpty()) {
                                Log.w(TAG, "📞 web_call_initiate missing peer publicKey")
                            } else {
                                val contact = database.contactDao().getContactByPublicKey(peer)
                                // __SHARED_NICKNAME_2026_09_22__ alias > shared nickname.
                                val name = com.oshi.messenger.data.model.PeerNickname.userAlias(contact?.alias, peer)
                                    ?: com.oshi.messenger.data.model.PeerNicknameStore.lookup(peer)
                                    ?: contact?.alias?.takeIf { it.isNotEmpty() }
                                    ?: contact?.publicKey?.take(8)
                                    ?: peer.take(8)
                                callManager.startCall(peer, name, isVideoCall = isVideo)
                            }
                        }
                        "accept" -> {
                            // 🔧 CRITICAL GUARD: Only accept if there is a real, ringing INCOMING call.
                            // Without this, a stale/replayed `web_call_accept` (from a leftover
                            // browser session) can fire while we're the CALLER waiting for the
                            // callee, or while idle. That blew up cross-platform calls because
                            // the basic CallManager would mutate state (or no-op silently) while
                            // EnhancedCallManager — the manager actually wired to the call UI —
                            // was still ringing. Symptom: caller never transitions out of
                            // ringing (Bug A), or callee in-call but no audio (Bug B).
                            val state = callManager.callState.value
                            val current = callManager.currentCall.value
                            if (state == CallState.RINGING && current != null && !current.isOutgoing) {
                                callManager.acceptCall()
                            } else {
                                Log.w(TAG, "📞 Ignoring web_call_accept — no live incoming call (state=$state, hasIncoming=${current != null}, isOutgoing=${current?.isOutgoing})")
                            }
                        }
                        "reject" -> {
                            // 🔧 CRITICAL GUARD: Only end an actual ringing/in-progress call.
                            // Mirrors the iOS WebSessionManager guard so a stale `web_call_reject`
                            // can't hang up a healthy call the user didn't ask to end.
                            val state = callManager.callState.value
                            if (state == CallState.RINGING || state == CallState.CONNECTING || state == CallState.IN_CALL) {
                                callManager.endCall(CallEndReason.DECLINED)
                            } else {
                                Log.w(TAG, "📞 Ignoring web_call_reject — no live call (state=$state)")
                            }
                        }
                        else -> Log.w(TAG, "📞 Unknown web call control action: $action")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "📞 Web call control failed: ${e.message}")
                }
            }
        }

        // 🆕 Web → mobile TEXT. The browser types, the relay forwards
        // `message_from_web`, and `WebSessionManager` fans it out through
        // `onWebSendMessage` (`WebSessionManager.kt:484-486`) — which was never
        // assigned, so the manager logged "message_from_web dropped — onWebSendMessage
        // is not wired" and every message typed in the web client was silently lost.
        webSessionManager.onWebSendMessage = { content, recipient ->
            scope.launch {
                runCatching { sendTextMessage(recipient, content) }
                    .onFailure { Log.e(TAG, "web→mobile send failed: ${it.message}") }
            }
        }
        // __WEBSESSION_E2E_2026_09_23__ v2 answers every `send_message` with `send_result`, so the
        // browser learns whether the phone actually sent it.
        webSessionManager.onWebSendMessageResult = { content, recipient ->
            runCatching { sendTextMessage(recipient, content).isSuccess }
                .onFailure { Log.e(TAG, "web→mobile send failed: ${it.message}") }
                .getOrDefault(false)
        }
        // Files and voice notes typed in a v2 browser (`send_file_chunk`, reassembled). v1 never had
        // this: its `/api/web/upload` did not exist on the server.
        webSessionManager.onWebSendFile = { f ->
            val type = when (f.kind) {
                "image" -> com.oshi.messenger.data.model.MediaType.IMAGE
                "video" -> com.oshi.messenger.data.model.MediaType.VIDEO
                "audio" -> com.oshi.messenger.data.model.MediaType.AUDIO
                else -> com.oshi.messenger.data.model.MediaType.DOCUMENT
            }
            runCatching {
                sendMediaMessage(f.recipient, type, f.bytes, caption = null,
                    attachmentFileName = f.fileName.takeIf { it.isNotBlank() }).isSuccess
            }.onFailure { Log.e(TAG, "web→mobile file send failed: ${it.message}") }.getOrDefault(false)
        }

        // 🆕 Server asks us to retransmit a >5 MB media payload via WS. Look up the
        // message, base64-encode the attachment, send back as `media_response`.
        webSessionManager.onRequestMedia = { messageId, requestScope ->
            scope.launch {
                try {
                    val data: ByteArray?
                    val contentType: String
                    val fileName: String?
                    if (requestScope == "group") {
                        val gm = database.groupDao().getMessageById(messageId)
                        data = gm?.mediaData
                        contentType = mediaTypeToMime(gm?.mediaType?.name)
                        fileName = gm?.mediaFileName?.let {
                            com.oshi.messenger.util.AttachmentFileName.sanitize(it)
                        } ?: webDownloadName(gm?.mediaType?.name, gm?.content)
                    } else {
                        val m = database.messageDao().getMessageById(messageId)
                        // [resolveMediaData], not `m.mediaData`: anything over
                        // [MAX_INLINE_MEDIA_BYTES] keeps `mediaData = NULL` on the row
                        // BY DESIGN, so reading the column alone answered "not_found"
                        // for every photo big enough that the browser had to ask
                        // (audit M-11). Since inbound V2 media is now file-backed
                        // without ever being materialised, this is the only read that
                        // can see it at all.
                        data = m?.let { resolveMediaData(it) }
                        contentType = mediaTypeToMime(m?.mediaType?.name)
                        fileName = webDownloadName(m?.mediaType?.name, m?.content)
                    }
                    if (data != null && data.isNotEmpty()) {
                        webSessionManager.sendMediaResponse(
                            messageId = messageId,
                            scope = requestScope,
                            contentType = contentType,
                            fileName = fileName,
                            dataBase64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                        )
                    } else {
                        webSessionManager.sendMediaResponse(messageId, requestScope, null, null, null, error = "not_found")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "request_media lookup failed: ${e.message}")
                    webSessionManager.sendMediaResponse(messageId, requestScope, null, null, null, error = "exception")
                }
            }
        }

        // 🆕 Web → mobile group create. Web emits {type:'web_create_group', name, type, members:[pk,…]}.
        // We create the group locally then echo a fresh sync_groups so the web UI updates immediately.
        webSessionManager.onWebCreateGroup = { name, groupType, memberPublicKeys ->
            scope.launch {
                try {
                    val gType = com.oshi.messenger.data.model.GroupType.fromValue(groupType)
                    val isPublic = gType == com.oshi.messenger.data.model.GroupType.PUBLIC
                    val group = groupManager.createGroup(
                        name = name,
                        memberPublicKeys = memberPublicKeys,
                        isPublic = isPublic,
                        groupType = gType
                    )
                    Log.d(TAG, "👥 Web created group ${group.id} (${group.name}) with ${memberPublicKeys.size} members")
                    // Push the updated group list back to the web session
                    syncGroupsToWebSession()
                } catch (e: Exception) {
                    Log.e(TAG, "Web create group failed: ${e.message}", e)
                }
            }
        }

        // 🆕 Web → mobile add member. Web emits {type:'web_add_group_member', groupId, memberPublicKey, alias?}.
        webSessionManager.onWebAddGroupMember = { groupId, memberPublicKey, _ ->
            scope.launch {
                try {
                    groupManager.addMember(groupId, memberPublicKey)
                    Log.d(TAG, "👥 Web added member ${memberPublicKey.take(12)}… to group $groupId")
                    syncGroupsToWebSession()
                } catch (e: Exception) {
                    Log.e(TAG, "Web add group member failed: ${e.message}", e)
                }
            }
        }

        // 🆕 Wire wallpaper-change → web push so all linked sessions stay in sync.
        wallpaperManager.onWallpaperChanged = { conversationId, wallpaper ->
            try {
                val bytes: ByteArray? = when (wallpaper) {
                    is com.oshi.messenger.ui.components.ChatWallpaper.ImageWallpaper -> wallpaper.imageData
                    else -> null
                }
                val type: String = when (wallpaper) {
                    is com.oshi.messenger.ui.components.ChatWallpaper.ImageWallpaper -> "image"
                    is com.oshi.messenger.ui.components.ChatWallpaper.SolidColor -> "color"
                    is com.oshi.messenger.ui.components.ChatWallpaper.Gradient -> "gradient"
                    is com.oshi.messenger.ui.components.ChatWallpaper.EmojiPattern -> "emoji"
                    // The web bridge has no renderer for an animated backdrop; it is
                    // told "none" rather than a type it would draw as a blank.
                    is com.oshi.messenger.ui.components.ChatWallpaper.OshiDynamic -> "none"
                    com.oshi.messenger.ui.components.ChatWallpaper.Default -> "none"
                }
                pushWallpaperToWeb(conversationId ?: "global", bytes, type)
            } catch (e: Exception) {
                Log.w(TAG, "Wallpaper push to web failed: ${e.message}")
            }
        }

        // PERF: move startup I/O (SharedPreferences read + Base64 decode + JSON parse +
        // EncryptedSharedPreferences open) off the Main thread. These three calls together
        // can take 200-500 ms on mid-tier devices (Samsung A17 etc.) and were a top ANR
        // source at app launch. They are independent of any subsequent work on this
        // thread (the mesh/VPS callbacks below register lazily).
        scope.launch(Dispatchers.IO) {
            try {
                loadMyProfilePicture()
                loadPinnedMessages()
                doubleRatchet.loadSessions(context)
            } catch (e: Exception) {
                Log.w(TAG, "Background init load failed: ${e.message}")
            }
        }

        // Setup mesh message callback (Android-Android via Nearby Connections)
        // 🔧 FIX: Use decryptionMutex to serialize — DoubleRatchet is NOT thread-safe
        meshManager.addMessageCallback { meshMessage ->
            scope.launch {
                noteInboundIdentity(meshMessage.senderPublicKey)
                decryptionMutex.withLock { handleMeshMessage(meshMessage) }
            }
        }

        // Setup cross-platform mesh callback (iOS-Android via TCP/BLE)
        crossPlatformMesh.addMessageCallback { crossPlatformMessage ->
            scope.launch {
                noteInboundIdentity(crossPlatformMessage.senderPublicKey)
                decryptionMutex.withLock { handleCrossPlatformMessage(crossPlatformMessage) }
            }
        }

        // Setup VPS message callback — delayed 8s so group sync (5s) completes first,
        // preventing group envelope messages from being discarded before keys are loaded
        scope.launch {
            delay(8_000)
            vpsClient.startMessagePolling { vpsMessage ->
                scope.launch {
                    noteInboundIdentity(vpsMessage.senderPublicKey)
                    decryptionMutex.withLock { handleVPSMessage(vpsMessage) }
                }
            }
        }

        // ── OSHI V2 transport wiring (dormant unless V2Config is enabled) ────────
        // Deliver each decrypted V2 inbound through the SAME serialized dispatcher
        // as VPS/mesh (DoubleRatchet handlers are not thread-safe → decryptionMutex),
        // publish our prekey bundle so peers can open a session, and poll the
        // /v2/messages relay. Every V2 call is gated by V2Config inside the router,
        // so in the shipping build (V2 off) this loop is a cheap no-op.
        // SUSPEND callback, AWAITED inside poll() before it acks the relay — so a message
        // is persisted before the relay is told to drop it. Serialized under the same
        // decryptionMutex as the VPS/mesh receive paths (DoubleRatchet is not thread-safe).
        // __PER_DEVICE_MAILBOX_2026_09_23__ "sent from another of my devices" (CLIENT_SPEC §3.6):
        // an OUTGOING row, status sent — no notification, no receipt, no control processing.
        v2MessageRouter.onSentCopyDelivered = { copy, envTs ->
            decryptionMutex.withLock { ingestSentCopyFromOwnDevice(copy, envTs) }
        }
        // __RESEND_LIST_ANDROID_2026_10_12__ A peer named the messages its ratchet could not
        // open. Serving the request lives here, not in the router: only this class can reach
        // the message store, and the ownership guard needs the stored row.
        v2MessageRouter.onResendRequested = { peer, ids ->
            // Off the poll loop: see the callback's own doc for why it must not be awaited.
            scope.launch { serveResendRequest(peer, ids) }
        }
        v2MessageRouter.onMessageDelivered = { inbound ->
            noteInboundIdentity(inbound.from)
            decryptionMutex.withLock {
                when (inbound) {
                    is com.oshi.messenger.network.v2.V2Inbound.Text ->
                        if (inbound.groupId != null) {
                            dispatchV2GroupText(inbound.text, inbound.from, inbound.groupId)
                        } else {
                            // __LEGACY_COMPAT_2026_09_23__ a group DEFINITION over v2 1:1 proves a
                            // v2-group build (old builds never sent one there) ⇒ no legacy copy for it.
                            if (com.oshi.messenger.network.v2.GroupLegacyCompat.isV2DefinitionProof(inbound.text, GROUP_UPDATE_PREFIX)) {
                                com.oshi.messenger.network.v2.GroupLegacyCompat.noteV2DefinitionFrom(inbound.from)
                            }
                            // __RESTRICTED_NETWORK_HINT_2026_11_18__ strip the invisible
                            // suffix before anything reads the text; remember the id.
                            val rn = com.oshi.messenger.util.RestrictedNetworkHint.strip(inbound.text)
                            if (rn.flagged) com.oshi.messenger.util.RestrictedNetworkHintStore.add(context, inbound.msgId)
                            dispatchV2Inbound(rn.text, inbound.from, inbound.msgId, inbound.ts)
                        }
                    is com.oshi.messenger.network.v2.V2Inbound.File ->
                        if (inbound.groupId != null) {
                            dispatchV2GroupFile(inbound)
                        } else {
                            dispatchV2InboundFile(inbound)
                        }
                }
            }
        }
        // 🔋 COALESCED ONTO THE MESSAGE POLL (2026-08-23). This used to be its own
        // `while (true) { … delay(3_000) }` on the same process-lifetime scope: a
        // SECOND schedule waking the radio to talk to the SAME host as the 5 s message
        // poll, 20 more requests a minute, forever, backgrounded and screen-off
        // included. It is now a callback the message poll invokes once per tick, so
        // one wakeup covers both — and it inherits the whole schedule: 5 s foreground
        // and busy, escalating to 30 s foreground and idle, 120 s backgrounded.
        //
        // This is also the iOS shape rather than a new invention: iOS has no v2 timer
        // at all, it drives the v2 pull from inside `checkPendingIPFSMessages()`, the
        // same routine as the legacy fetch (`MessageManager.swift:1914` is the only
        // message timer, and `SecureWeb3MessengerApp.swift:290-330` bursts it on
        // foreground rather than running a second loop).
        vpsClient.onPollTick = {
            try {
                v2MessageRouter.refreshConfig()   // TTL-bounded; ~1 /v2/config GET per minute
                // ⚠️ NOT a no-op. The comment that used to sit here claimed this
                // "returns instantly while V2 is disabled" — that has been false for
                // months: GET https://oshi-messenger.com/v2/config answers
                // {"v2_enabled":true,"rollout_percent":100} in production (verified
                // 2026-08-23), so every tick was a real relay pull, a decrypt pass and
                // two SharedPreferences writes.
                v2MessageRouter.poll()
                // 🔁 Retry wake pushes that failed. iOS drains on every
                // foregrounding (`PushRetryQueue.swift:78-86`); this is the Android
                // analogue, and the queue's own 30 s backoff — not the tick rate —
                // decides how often a given entry is actually tried. It also returns
                // immediately when the queue is empty, so it costs nothing at rest.
                drainPushRetryQueue()
                // 📸 Ask for avatars we still do not have. Until now Android only ever
                // requested a profile on RECEIVING a message, so a picture set on the
                // peer's phone after the last message never arrived — the contact sat
                // on its letter avatar forever. Coalesced onto this same tick for the
                // battery reason described above, and self-limiting: the sweep only
                // considers contacts whose `profilePicture` is null, and
                // `requestProfileIfNeeded` returns early for anything already known.
                sweepMissingProfilePictures()
            } catch (e: Exception) {
                Log.w(TAG, "V2 poll error: ${e.message}")
            }
        }
        scope.launch {
            delay(8_000)
            try {
                v2MessageRouter.publishBundleIfEnabled()
            } catch (e: Exception) {
                Log.w(TAG, "V2 bundle publish skipped: ${e.message}")
            }
        }

        // Wire group envelope decryption: provide group keys for envelope decryption
        vpsClient.groupKeyProvider = { hint ->
            val groups = groupManager.groups.value
            Log.d(TAG, "🔑 groupKeyProvider called with hint='$hint', ${groups.size} groups available")
            groups.forEach { group ->
                Log.d(TAG, "🔑   Group: '${group.name}' id=${group.id.take(8)} hint4='${group.id.take(4)}'")
            }
            // Filter by hint (first 4 chars of group UUID) — fallback to all if hint empty
            val candidates = if (hint.isNullOrEmpty()) {
                groups
            } else {
                // iOS hint = groupId.prefix(4) = first 4 chars of UUID string
                val matched = groups.filter { it.id.startsWith(hint, ignoreCase = true) }
                if (matched.isEmpty()) {
                    Log.w(TAG, "🔑 No exact hint match for '$hint', falling back to all ${groups.size} groups")
                    groups
                } else {
                    Log.d(TAG, "🔑 Hint '$hint' matched ${matched.size} group(s)")
                    matched
                }
            }
            candidates.map { group ->
                val groupKey = deriveGroupKey(group.id)
                group.id to groupKey
            }
        }

        // CV-002 — open a group envelope v4. There is no group key to try: our own
        // wrap is found by hint, and the sessionId inside it names the 1:1 session
        // able to open it. Lives here rather than in VPSClient so that class keeps
        // no knowledge of the ratchet or the session store.
        vpsClient.groupEnvelopeV4Opener = { envelope ->
            runCatching {
                val myKey = cryptoManager.getPublicKeyString()
                val salt = android.util.Base64.decode(
                    envelope.getString("salt"), android.util.Base64.NO_WRAP)
                val mine = GroupEnvelopeV3.hint(myKey, salt)
                val wraps = envelope.getJSONArray("wraps")
                var wrapped: ByteArray? = null
                for (i in 0 until wraps.length()) {
                    val w = wraps.optJSONObject(i) ?: continue
                    if (w.optString("h") == mine) {
                        wrapped = android.util.Base64.decode(
                            w.getString("k"), android.util.Base64.NO_WRAP)
                        break
                    }
                }
                // Not addressed to us: a group we are not in. Quiet, not an error.
                val wrapBytes = wrapped ?: return@runCatching null
                // The wrap's own ratchet header names the session that can open
                // it, so no sender field and no trial decryption are needed.
                val ratchetMsg = IOSRatchetMessage.fromJSON(
                    JSONObject(String(wrapBytes, Charsets.UTF_8)))
                val session = doubleRatchet.sessionById(ratchetMsg.header.sessionId)
                    ?: return@runCatching null   // reinstall, or a heal not landed yet

                String(
                    GroupEnvelopeV3.open(envelope, myKey) { _ ->
                        doubleRatchet.decrypt(session, ratchetMsg)
                    },
                    Charsets.UTF_8
                )
            }.getOrNull()
        }

        // __BOT_IN_THE_PIPELINE_2026_11_09__ Build the bot runtime as soon as group traffic
        // can arrive: its init is what sets GroupManager's bot hook (and starts scheduled
        // bots). Waiting for the user to open a bot or group screen left an incoming `/help`
        // with nothing listening. Off the main thread: it reads Room.
        scope.launch {
            runCatching { botManager.get() }
                .onFailure { Log.w(TAG, "BotManager init failed: ${it.message}") }
        }

        // Provide contact alias resolution to GroupManager and retroactively fix stored names
        groupManager.contactAliasResolver = { publicKey ->
            // __SHARED_NICKNAME_2026_09_22__ A typed alias first, then the member's own
            // shared nickname, then whatever the row held (placeholder) — so a nickname
            // only ever replaces a key-prefix placeholder, never a name the user chose.
            runCatching {
                val alias = contactDao.getContactByPublicKeySync(publicKey)?.alias
                com.oshi.messenger.data.model.PeerNickname.userAlias(alias, publicKey)
                    ?: com.oshi.messenger.data.model.PeerNicknameStore.lookup(publicKey)
                    ?: alias
            }.getOrNull()
        }
        scope.launch { groupManager.refreshAllSenderNames() }

        // Handle decrypted group messages from iOS envelopes
        vpsClient.onGroupEnvelopeDecrypted = { senderKey, groupContent ->
            scope.launch {
                groupManager.handleIncomingGroupMessage(groupContent, senderKey)
            }
        }

        // Handle iOS full-group-update broadcasts (group rename, member change, picture/wallpaper from broadcastFullGroupUpdate)
        vpsClient.onGroupUpdateBroadcast = {
            groupId, groupName, membersJson, groupImageBytes, adminPublicKey, groupType,
            groupWallpaperBytes, groupWallpaperUpdatedBy, groupWallpaperUpdatedAtMs,
            pinnedMessageId, hasPinnedField, pinnedBy, avatar, hasAvatarField ->
            scope.launch {
                groupManager.applyiOSGroupUpdateBroadcast(
                    groupId, groupName, membersJson,
                    groupImageBytes, adminPublicKey, groupType,
                    groupWallpaperBytes, groupWallpaperUpdatedBy, groupWallpaperUpdatedAtMs,
                    // 📌🎨 The pin and the emoji avatar travel in this payload and were
                    // dropped on the floor here (VPSClient never even parsed them), so an
                    // iPhone pinning a message never moved the pin on Android.
                    pinnedMessageId, hasPinnedField, pinnedBy, avatar, hasAvatarField
                )
            }
        }

        // Handle iOS delivery receipts: {"encrypted": "base64..."}
        vpsClient.onEncryptedReceiptReceived = { encryptedBase64 ->
            scope.launch {
                handleiOSEncryptedReceipt(encryptedBase64)
            }
        }

        // 🔧 One-shot duplicate-conversation fix (FIX 2): merge contact rows whose
        // publicKey strings differ only by base64url padding/whitespace, and rewrite
        // every message's senderPublicKey/recipientPublicKey to the canonical form.
        // Runs ONCE per app install via SharedPreferences flag.
        scope.launch {
            delay(2_000)
            mergeDuplicateContactsOnce()
            // 🔑 Then converge every remaining key on the canonical STORAGE spelling.
            // Order matters: dedup collapses two contact rows into one, and this pass
            // then moves whatever survived onto the form the receive funnels stamp.
            canonicalizeIdentitiesOnce()
        }

        // 🔄 Group sync: after VPS stabilizes, ask all contacts to push groups we're missing
        scope.launch {
            delay(5_000)
            val peers = contactDao.getAllContactsList().map { it.publicKey }
            Log.d(TAG, "🔄 Requesting group sync from ${peers.size} contact(s) + mesh")
            groupManager.requestGroupSync(peers)
        }

        // 🔄 Re-announce public group membership to iOS admins via group envelope.
        // This ensures iOS has Android in its members list and will queue future messages for Android.
        // Runs 10s after startup to let mesh and VPS connections stabilize first.
        scope.launch {
            delay(10_000)
            groupManager.reannounceGroupMembership()
        }

        Log.d(TAG, "📨 MessageRepository initialized")
    }

    // ============================================================================
    // CONVERSATIONS
    // ============================================================================

    /**
     * Get all conversations with latest message.
     *
     * 🔧 FIX: groups contacts by normalized public key so a single contact
     * stored under two slightly-different keys (whitespace, base64 vs base64url,
     * trailing "=" padding) shows ONE conversation instead of duplicates.
     *
     * For each normalization-group we pick a single canonical contact (preferring
     * the one with a non-empty alias, then the longest publicKey string), merge
     * unread counts across all aliases of that key, and unify the latest message
     * by scanning every alias's message history.
     */
    fun getConversations(): Flow<List<Conversation>> {
        // __SHARED_NICKNAME_2026_09_22__ The shared-nickname map is a third input so a
        // peer's rename redraws the list without any Room write.
        return combine(
            contactDao.getAllContacts(),
            messageDao.getUnreadMessages(),
            com.oshi.messenger.data.model.PeerNicknameStore.nicknames,
            // __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18)
            com.oshi.messenger.data.model.SavedContactThreads.get(context).keys
        ) { contacts, unreadMessages, nicknames, savedContacts ->
            // Group contacts by normalized key so duplicates collapse.
            val groups: Map<String, List<Contact>> =
                contacts.groupBy { normalizeBase64Key(it.publicKey) }
            // PERF: heavy work below (per-contact DAO query + multi-list flatten/sort)
            // is dispatched away from Main via flowOn() at the end of the chain. We
            // build the result inline; the suspending DAO calls inside this transform
            // run on the dispatcher provided by flowOn(Dispatchers.Default).

            groups.mapNotNull { (normKey, groupContacts) ->
                // Canonical contact: prefer one with non-empty alias, else longest publicKey.
                val canonical = groupContacts
                    .sortedWith(
                        compareByDescending<Contact> { it.alias.isNotEmpty() }
                            .thenByDescending { it.publicKey.length }
                    )
                    .first()

                // Pull the latest non-sentinel message across every alias in the group.
                val allKeysInGroup = groupContacts.map { it.publicKey }
                // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ A channel message must appear
                // in the channel and NOWHERE else.
                //
                // `getMessagesForContact` selects on `sender = :key OR recipient = :key`,
                // so a broadcast addressed to the room still matches the speaker's own
                // one-to-one thread. Without this filter every public message would be
                // listed twice: once in the room, once as what looks like a private
                // message from that person. That second one is the dangerous copy,
                // because it invites a reply the user believes only one person can read.
                val isChannelThread = LoRaChannelThread.isChannelKey(canonical.publicKey)
                val mergedMessages = allKeysInGroup
                    .flatMap { key -> messageDao.getMessagesForContact(key).first() }
                    .let { msgs ->
                        if (isChannelThread) msgs
                        else msgs.filterNot { LoRaChannelThread.isChannelKey(it.recipientPublicKey) }
                    }
                    .sortedByDescending { it.timestamp }

                val latestMessage = mergedMessages.firstOrNull { msg ->
                    val c = msg.content
                    !c.startsWith("📬DELIVERY_RECEIPT📬")
                        && !c.startsWith("📖READ_RECEIPT📖")
                        && !c.startsWith("⌨️TYPING⌨️")
                        && !c.startsWith("✍️TYPING✍️")
                        && !c.startsWith("🔧ACTION🔧")
                        && !c.startsWith("📸PROFILE_UPDATE📸")
                        && !c.startsWith("📸PROFILE_REQUEST📸")
                        && !c.startsWith("🔗LINKS_VISIBILITY🔗")
                } ?: mergedMessages.firstOrNull()
                    // __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18) A contact
                    // the user SAVED is listed before anyone has written, with a
                    // stand-in "last message" dated when it was saved (so it lands at
                    // the top, where the user is looking). Only a saved one: a contact
                    // whose conversation was deleted, or a LoRa speaker whose messages
                    // all live in a channel room, stays out exactly as before.
                    ?: if (com.oshi.messenger.data.model.SavedContactThreads.showsEmptyThread(
                            canonical.publicKey,
                            savedContacts,
                            isRadioKey = isChannelThread || canonical.publicKey.startsWith("lora!")
                        )
                    ) {
                        Message(
                            id = "saved-contact:" + canonical.publicKey,
                            senderPublicKey = canonical.publicKey,
                            recipientPublicKey = canonical.publicKey,
                            content = "",
                            timestamp = groupContacts.maxOf { it.createdAt },
                            isFromMe = false,
                            isRead = true
                        )
                    } else return@mapNotNull null
                val hasMessages = mergedMessages.isNotEmpty()

                // Unread count: sum across every alias of the same normalized key.
                val keysSet = allKeysInGroup.toHashSet()
                val unreadCount = if (isChannelThread) {
                    // A room's unread messages are the ones addressed TO it. Counting
                    // by sender would give every channel a count of zero, since no
                    // message is ever sent *from* a channel.
                    unreadMessages.count { it.recipientPublicKey == canonical.publicKey }
                } else {
                    // ...and a person's own count must not include what they said in
                    // public, or clearing the room would leave a badge on them.
                    unreadMessages.count {
                        it.senderPublicKey in keysSet &&
                            !LoRaChannelThread.isChannelKey(it.recipientPublicKey)
                    }
                }

                // Online status: any alias seen within 5 minutes counts as online.
                val isOnline = groupContacts.any { c ->
                    c.lastSeen?.let { (System.currentTimeMillis() - it) < 5 * 60 * 1000 } ?: false
                }

                Conversation(
                    participantPublicKey = canonical.publicKey,
                    participantAlias = com.oshi.messenger.data.model.PeerNickname.resolve(
                        canonical.alias,
                        com.oshi.messenger.data.model.PeerNickname.lookup(nicknames, canonical.publicKey),
                        canonical.publicKey
                    ),
                    participantProfilePicture = canonical.profilePicture,
                    lastMessage = latestMessage,
                    unreadCount = unreadCount,
                    isOnline = isOnline,
                    isVerified = canonical.isVerified,
                    hasMessages = hasMessages
                )
            }.sortedByDescending { it.lastMessage.timestamp }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * 🔧 FIX 2: One-shot duplicate-conversation migration.
     *
     * Some contacts were inserted with whitespace-trimmed / base64url-vs-base64 /
     * trailing-"="-padded variants of the same public key. The chat list groups
     * by exact string equality, so the user saw two threads for the same person.
     *
     * For every group of contacts whose `normalizeBase64Key(publicKey)` is equal:
     *  1. Pick the canonical row — prefer the one with non-empty alias, else the
     *     row whose `publicKey` is longest in normalized-equivalent form.
     *  2. Rewrite every message whose senderPublicKey or recipientPublicKey
     *     equals a duplicate to the canonical key (Room queries above).
     *  3. Delete the duplicate contact row.
     *
     * Idempotent: a SharedPreferences flag prevents repeat runs after success.
     */
    private suspend fun mergeDuplicateContactsOnce() {
        val prefs = context.getSharedPreferences("oshi_migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean("dedup_contacts_v1_done", false)) return
        try {
            val all = contactDao.getAllContactsList()
            // Include blocked rows too — duplicates can hide there. getAllContactsList
            // already returns only non-blocked; for blocked rows we'd need a separate
            // query. For now we operate on the visible set since chat-list dups are
            // what the user reported.
            val groups = all.groupBy { normalizeBase64Key(it.publicKey) }
                .filter { it.value.size > 1 }

            if (groups.isEmpty()) {
                prefs.edit().putBoolean("dedup_contacts_v1_done", true).apply()
                Log.d(TAG, "🧹 dedup: no duplicate contacts found")
                return
            }

            var mergedRows = 0
            var rewroteSender = 0
            var rewroteRecipient = 0
            var deletedContacts = 0

            for ((normKey, dupes) in groups) {
                // Canonical = non-empty alias preferred, then longest publicKey.
                val canonical = dupes
                    .sortedWith(
                        compareByDescending<Contact> { it.alias.isNotEmpty() }
                            .thenByDescending { it.publicKey.length }
                    )
                    .first()
                val duplicates = dupes.filter { it.publicKey != canonical.publicKey }
                if (duplicates.isEmpty()) continue
                Log.w(TAG, "🧹 dedup: norm='${normKey.take(12)}...' canonical='${canonical.publicKey.take(12)}...' alias='${canonical.alias}' dupes=${duplicates.size}")
                for (dup in duplicates) {
                    rewroteSender += messageDao.rewriteSenderPublicKey(dup.publicKey, canonical.publicKey)
                    rewroteRecipient += messageDao.rewriteRecipientPublicKey(dup.publicKey, canonical.publicKey)
                    contactDao.deleteByPublicKey(dup.publicKey)
                    deletedContacts++
                }
                mergedRows += duplicates.size
            }

            Log.w(TAG, "🧹 dedup DONE: contactsMerged=$mergedRows messagesSenderRewrites=$rewroteSender messagesRecipientRewrites=$rewroteRecipient contactsDeleted=$deletedContacts")
            prefs.edit().putBoolean("dedup_contacts_v1_done", true).apply()
        } catch (e: Exception) {
            // Don't set the flag — we'll retry on next launch.
            Log.e(TAG, "❌ dedup contacts failed: ${e.message}", e)
        }
    }

    /**
     * 🔑 One-shot: move every key already on disk to the [canonicalIdentity]
     * spelling, messages AND contacts, in one pass.
     *
     * Without this, stamping the receive funnels would be a REGRESSION, not a fix.
     * A peer whose contact row and whose message rows are today consistently
     * base64url is visible right now — the thread query and the stored rows agree.
     * Stamping only new arrivals would leave the contact row (the query parameter)
     * in the old spelling and make every NEW message from that peer vanish, which
     * is the exact symptom being fixed, inverted.
     *
     * iOS has no analogue because it filters in Swift and canonicalises both sides
     * of the comparison at read time (`ChatView.swift:183-189`). Android's filter is
     * SQL `=`, which no Kotlin-side normalisation can reach, so convergence has to
     * happen in the table.
     *
     * Idempotent, and cheap after the first run: the flag short-circuits it, and even
     * without the flag `canonicalIdentity` returns its input for anything already
     * canonical, so the pass rewrites nothing.
     */
    private suspend fun canonicalizeIdentitiesOnce() {
        val prefs = context.getSharedPreferences("oshi_migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean("canonical_identity_v1_done", false)) return
        try {
            var senderRewrites = 0
            var recipientRewrites = 0
            var contactsMoved = 0
            var contactsDropped = 0

            for (k in messageDao.distinctSenderKeys()) {
                val c = canonicalIdentity(k)
                if (c != k) senderRewrites += messageDao.rewriteSenderPublicKey(k, c)
            }
            for (k in messageDao.distinctRecipientKeys()) {
                val c = canonicalIdentity(k)
                if (c != k) recipientRewrites += messageDao.rewriteRecipientPublicKey(k, c)
            }

            // Contacts second, so the rows they index are already converted.
            for (contact in contactDao.getEveryContact()) {
                val c = canonicalIdentity(contact.publicKey)
                if (c == contact.publicKey) continue
                if (contactDao.getContactByPublicKey(c) != null) {
                    // The canonical row already exists and now owns the messages —
                    // the stale spelling is a duplicate conversation, so drop it.
                    contactDao.deleteByPublicKey(contact.publicKey)
                    contactsDropped++
                } else {
                    // `publicKey` is the PRIMARY KEY, so "rename" is insert + delete.
                    contactDao.insert(contact.copy(publicKey = c))
                    contactDao.deleteByPublicKey(contact.publicKey)
                    contactsMoved++
                }
            }

            Log.w(
                TAG,
                "🔑 canonical identity DONE: senderRewrites=$senderRewrites " +
                    "recipientRewrites=$recipientRewrites contactsMoved=$contactsMoved " +
                    "contactsDropped=$contactsDropped"
            )
            prefs.edit().putBoolean("canonical_identity_v1_done", true).apply()
        } catch (e: Exception) {
            // Don't set the flag — retry next launch. A half-converted table is safe:
            // the pass is idempotent and only ever moves rows toward the canonical form.
            Log.e(TAG, "❌ canonical identity migration failed: ${e.message}", e)
        }
    }

    /**
     * Get messages for a specific contact.
     *
     * Every emission goes through [hydrateMedia], which re-attaches file-backed blobs
     * (anything over [MAX_INLINE_MEDIA_BYTES]) to the rows. Without this the bubble
     * reads `message.mediaData == null` for every video and every large photo and
     * renders empty — the P0 this repository's blob-stripping created. Hydration runs
     * on the Flow's collection dispatcher and is budgeted exactly like iOS's decrypted
     * media cache (see [hydrateMedia]).
     */
    fun getMessagesForContact(publicKey: String): Flow<List<Message>> {
        // __CHAT_OPEN_PERF_2026_11_14__ `.map` runs in the COLLECTOR's context, and the
        // collectors are `viewModelScope` (Main): the stat + decrypt of up to 60 files /
        // 96 MB ran on the UI thread on every emission. Now on IO.
        return messageDao.getMessagesForContact(publicKey)
            .map { hydrateMedia(it) }
            .flowOn(Dispatchers.IO)
    }

    /**
     * __CHAT_OPEN_PERF_2026_11_14__ The rows the 1:1 THREAD renders: light rows
     * ([MessageDao.getThreadRowsForContact] — no `encryptedContent`, attachments over
     * [com.oshi.messenger.data.local.ThreadListProjection.INLINE_MAX_BYTES] left
     * off) and NO hydration. The bubble resolves its own bytes when it is composed,
     * through [loadMediaData], so opening a chat reads the media of the ten bubbles on
     * screen instead of decrypting up to 60 files and copying every inline photo of the
     * conversation on each emission.
     */
    fun getThreadMessagesForContact(publicKey: String): Flow<List<Message>> =
        messageDao.getThreadRowsForContact(publicKey)
            // __THREAD_ORDER_AFTER_RETRY_2026_11_18__ already newest-first from SQL; a
            // pass-through check (O(n), no copy) that keeps the rule in one tested place.
            .map { com.oshi.messenger.data.model.ThreadOrder.newestFirst(it) }
            .flowOn(Dispatchers.IO)

    /**
     * Get unread count for a contact
     */
    fun getUnreadCountForContact(publicKey: String): Flow<Int> {
        return messageDao.getUnreadCountForContact(publicKey)
    }

    /** Unread incoming message ids of one thread, oldest first (open-at-first-unread). */
    suspend fun getUnreadIncomingIds(publicKey: String): List<String> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { messageDao.getUnreadIncomingIdsForContact(publicKey) }

    /**
     * Get total unread message count across all conversations
     */
    fun getTotalUnreadCount(): Flow<Int> {
        return messageDao.getUnreadMessages().map { it.size }
    }

    /**
     * 📞 A message that is RECORDED, never sent.
     *
     * The call summary is the case this exists for. Both ends of a call observe it
     * and each writes its own, in its own direction, so transmitting it produced two
     * bubbles per side — and the peer's copy carries the PEER's direction, which is
     * never flipped on read, so a call you RECEIVED displayed as "outgoing". iOS
     * moved the write out of the view into `MessageManager` and stopped sending it
     * (`OSHI/MessageManager.swift:5658-5677`, the `deliveryMethod: nil` at `:5677`).
     *
     * [dedupeWindowMs] > 0 drops an identical body for the same peer inside ±window.
     * `CallEnded` can fire twice for one call (local hang-up, then the peer's signal
     * arriving right after) and every write mints a fresh id, so dedup by id cannot
     * see it — iOS guards the same way at `MessageManager.swift:5645-5654`.
     *
     * ### Why [deliveryMethod] has no default, and what it should eventually be
     *
     * iOS writes `deliveryMethod: nil` — "nothing was sent, so claim nothing".
     * Android cannot express that: `Message.deliveryMethod` is a non-null enum
     * (`data/model/Message.kt:22`) and `ChatScreen`'s `DeliveryMethodBadge` renders a
     * coloured transport badge off it, so `VPS` here would put "Sent via Internet" on
     * a row that never left the device. The honest fix is a `LOCAL` member — the enum
     * is persisted as `value.name` TEXT (`AppDatabase.kt` `Converters`), so adding one
     * needs no migration, exactly as the `LORA` member already documents.
     *
     * It is NOT added here because the `when (deliveryMethod)` at
     * `ui/screens/ChatScreen.kt:5103` is exhaustive with no `else`, so a new member is
     * a compile error in a file another team is editing this pass. Until both land,
     * this parameter is explicit so no caller can pick a transport by accident.
     *
     * Multi-device: nothing to schedule. Android's server-mediated sync uploads
     * straight from the database (`MultiDeviceSyncManager.kt:1176-1180`), so the row
     * reaches the user's other devices on the next tick; iOS's
     * `scheduleUploadMessagesForSync()` only brings that forward.
     *
     * @return the stored row, or null when the dedup window swallowed it.
     */
    suspend fun insertLocalMessage(
        peerPublicKey: String,
        content: String,
        deliveryMethod: DeliveryMethod,
        dedupeWindowMs: Long = 0L,
    ): Message? {
        // 🔑 Same canonical spelling as every receive-side store, or the row lands in
        // the table and never appears in the thread.
        val peer = canonicalIdentity(peerPublicKey)
        val now = System.currentTimeMillis()

        if (dedupeWindowMs > 0) {
            val already = messageDao.countSameContentForPeer(
                peer = peer,
                content = content,
                fromTs = now - dedupeWindowMs,
                toTs = now + dedupeWindowMs,
            )
            if (already > 0) {
                Log.d(TAG, "📞 Local record already present for ${peer.take(8)} — not writing a second")
                return null
            }
        }

        val message = Message(
            id = UUID.randomUUID().toString(),
            senderPublicKey = myPublicKey,
            recipientPublicKey = peer,
            content = content,
            timestamp = now,
            isFromMe = true,
            // Ours already; there is no peer to hear from and nothing to mark read.
            isRead = true,
            deliveryMethod = deliveryMethod,
        )
        messageDao.insert(sanitizeMediaForInsert(message))
        Log.d(TAG, "📝 Local record written for ${peer.take(8)} — nothing sent to the peer")
        return message
    }

    // ============================================================================
    // SEND MESSAGES
    // ============================================================================

    // 📸 My profile picture (loaded from SharedPreferences at init)
    @Volatile private var myProfilePicture: ByteArray? = null

    /**
     * __PROFILE_PICTURE_SYNC_2026_09_23__ True once [myProfilePicture] reflects storage.
     * The init load runs on a background coroutine; a profile update sent before it
     * finished went out with `profileImageData: null`, which iOS applies as "photo
     * removed" (`ContactPresenceManager.processProfileUpdate`) — the Android user's face
     * vanished from iPhones until the next change. [sendProfileUpdate] now loads first.
     */
    @Volatile private var myProfilePictureLoaded = false

    /**
     * Set my profile picture for sharing with contacts.
     * Compresses to 200x200 JPEG to match iOS format, and persists to SharedPreferences.
     *
     * PERF: suspend + Dispatchers.Default — bitmap decode + scale + JPEG compress is
     * 100-400 ms on mid-tier devices and was a top ANR source on profile-edit.
     */
    suspend fun setMyProfilePicture(profilePicture: ByteArray?) {
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            if (profilePicture != null) {
                // Compress to match iOS format (512x512, JPEG)
                myProfilePicture = compressProfileImage(profilePicture)
            } else {
                myProfilePicture = null
            }
            myProfilePictureLoaded = true
            // Persist to SharedPreferences so it survives app restart
            val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
            if (myProfilePicture != null) {
                val base64 = Base64.encodeToString(myProfilePicture, Base64.NO_WRAP)
                prefs.edit().putString("profile_picture", base64).apply()
            } else {
                prefs.edit().remove("profile_picture").apply()
            }
            Log.d(TAG, "📸 My profile picture set and saved: ${myProfilePicture?.size ?: 0} bytes")
        }
    }

    /**
     * __DEVSYNC_PROFILE_2026_09_23__ Adopt the picture another device of this account already
     * compressed, byte for byte (re-compressing would change its sha256 and look like a new
     * local edit). No broadcast: the device where it was edited told the contacts.
     */
    fun devSyncAdoptProfilePicture(bytes: ByteArray?) {
        myProfilePicture = bytes
        myProfilePictureLoaded = true
        val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
        if (bytes != null) prefs.edit().putString("profile_picture", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        else prefs.edit().remove("profile_picture").apply()
    }

    /**
     * Load my profile picture from SharedPreferences (called at init)
     */
    private fun loadMyProfilePicture() {
        try {
            val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
            val base64 = prefs.getString("profile_picture", null)
            if (!myProfilePictureLoaded) {
                myProfilePicture = base64?.let {
                    Base64.decode(it, Base64.NO_WRAP)
                }
            }
            myProfilePictureLoaded = true
            Log.d(TAG, "📸 Profile picture loaded: ${myProfilePicture?.size ?: 0} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load profile picture: ${e.message}")
        }
    }

    /**
     * Compress profile image to 200x200 JPEG (matches iOS format)
     */
    private fun compressProfileImage(imageData: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size) ?: return imageData
            // 512px / Q80 — this used to be 200px/Q50 and re-crushed the already-good
            // 512px/Q80 bytes `ProfileEditScreen.compressBitmap()` hands in, a second lossy
            // pass that made every profile picture look pixelated regardless of what the
            // picker produced. Matches iOS's own 200→512/Q0.5→Q0.8 fix (SettingsView.swift).
            val targetSize = 512
            val ratio = targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaledBitmap = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                )
            } else {
                bitmap
            }
            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val compressed = outputStream.toByteArray()
            Log.d(TAG, "📸 Profile image compressed: ${imageData.size / 1024}KB → ${compressed.size / 1024}KB (${scaledBitmap.width}x${scaledBitmap.height})")
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            compressed
        } catch (e: Exception) {
            Log.e(TAG, "❌ Profile compression failed: ${e.message}")
            imageData
        }
    }

    /**
     * Send a text message
     * Automatically chooses best delivery method
     *
     * @param resendOf the message being SENT AGAIN, when this is a retry of one that
     *   failed. Its id and its timestamp are reused, so the retry is the same logical
     *   message rather than a new one: the bubble returns to the place in the thread
     *   where it was written, on both phones, and the peer's cross-transport dedup
     *   (which keys on the message id) still recognises it.
     *
     *   __RESEND_TIMESTAMP_2026_09_17__ Retry used to delete the failed row and call
     *   this afresh, minting a new id and a new `System.currentTimeMillis()`. A text
     *   written at 09:00 that only got through on the third tap at 18:00 was dated
     *   18:00 for everyone — filed under the wrong day, below replies that answered
     *   it. iOS keeps the original date across a resend
     *   (`ChatView.swift:1984`, `originalTimestamp: message.timestamp`).
     */
    suspend fun sendTextMessage(
        recipientPublicKey: String,
        rawContent: String,
        resendOf: Message? = null,
    ): Result<Message> {
        // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ CR / CRLF / U+2028 / U+2029 / VT
        // become LF so the paragraphs survive on every platform. Prose only;
        // control payloads and envelopes are returned unchanged.
        val content = MessageSendPolicy.normalizedForSend(rawContent)

        // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ Writing into a channel is a
        // BROADCAST, not a message to a person.
        //
        // Everything below this point assumes `recipientPublicKey` is somebody's
        // public key: it is handed to the ratchet, to the relay and to the block
        // list. A channel key is none of those things and would fail at the first
        // of them, so the room gets its own short path rather than a special case
        // threaded through a send designed for one recipient.
        if (LoRaChannelThread.isChannelKey(recipientPublicKey)) {
            return sendToLoRaChannel(recipientPublicKey, content)
        }
                // 🔧 P0 FIX (device walkthrough #2, 2026-08-13).
        //
        // The local row is inserted BEFORE any transport is attempted (so the bubble
        // appears instantly). When a transport then threw — e.g. `bad base-64` on a
        // malformed recipient key — the catch below only logged and returned
        // Result.failure: the already-inserted row stayed in the DB looking like a
        // perfectly normal sent message, and (with the old `isRead = true`) rendered
        // as blue ✓✓ "Read". A message that never left the device was shown to the
        // user as READ BY THE RECIPIENT.
        //
        // `persistedMessageId` is set the moment the row exists, so the catch can
        // stamp THAT row `isFailed = true`. `Message.isFailed` already existed and
        // ChatScreen already renders a red "Not delivered · tap to retry" affordance
        // for it (ChatScreen.kt, `if (message.isFailed && isFromMe)`) — nothing was
        // ever setting the flag. iOS marks the same state via
        // `MessageSendPolicy` / `DeliveryStatus.failed` and renders it in
        // `DeliveryMethodBadge.swift:54,74`.
        var persistedMessageId: String? = null

        // ✂️ THE BYTE BUDGET, CHECKED BEFORE ANY EXPENSIVE WORK.
        //
        // iOS decides this FIRST, before the row exists and before the ratchet is
        // touched (`MessageManager.swift:4861-4871` → `MessageSendPolicy.swift:56-60`).
        // Android used to compose, encrypt, chunk and only THEN refuse at frame
        // 13 inside `sendSecureMessage` — the user watched a bubble appear and
        // then quietly fail, having spent a ratchet step on it.
        //
        // The limit is 500 UTF-8 bytes only while a radio is PAIRED (the LoRa
        // lane is reachable); 4000 otherwise, which nothing typed ever hits.
        //
        // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ The 500 B radio budget applies
        // only when LoRa IS the route (paired AND off-grid) — it used to apply to
        // every message as soon as a radio had ever been paired.
        // Either radio counts: a MeshCore radio carries the same OMP frames (MeshCoreManager.kt).
        val loRaPaired = runCatching {
            MeshtasticManager.isRadioPaired(context) || MeshCoreManager.isPaired(context)
        }.getOrDefault(false)
        val loRaBudget = MessageSendPolicy.loRaBudgetApplies(
            radioPaired = loRaPaired,
            hasPhysicalPath = !loRaPaired || hasPhysicalNetworkPath(),
        )
        // __LORA_LONG_TEXT_OMP_2026_11_18__ A radio that answered the OSHI Mesh probe carries the whole
        // envelope as OMP (64 x 182 B), so the budget is 4,000 B, not 500. Remembered per paired radio,
        // because the decision is made here — often while the radio is out of Bluetooth range.
        val oshiFirmware = loRaBudget && runCatching { MeshtasticManager.lastKnownOshiFirmware(context) }.getOrDefault(false)
        if (MessageSendPolicy.check(content, loRaBudget, oshiFirmware) == MessageSendPolicy.Outcome.TOO_LONG) {
            val limit = MessageSendPolicy.maxUTF8Bytes(loRaBudget, oshiFirmware)
            val bytes = MessageSendPolicy.utf8Size(content)
            Log.w(TAG, "⛔️ send refused: $bytes B over the $limit B budget (loRaBudget=$loRaBudget)")
            // Surfaced, not swallowed: `ChatViewModel.reportSendResult`
            // (`ChatViewModel.kt:330-334`) turns this exception's message into
            // the toast `ChatScreen` already renders. No row is inserted, so the
            // refusal does not leave a failed bubble behind either.
            //
            // Resolved through resources rather than shipped as English prose —
            // `lora_error_too_large` is the very string the LoRa settings screen
            // uses for the radio's own version of this refusal.
            return Result.failure(
                com.oshi.messenger.service.MessageTooLongException(
                    context.getString(R.string.lora_error_too_large, bytes, limit)
                )
            )
        }

        // 🚫 BLOCKED: refuse at the REPOSITORY, not only in the UI.
        //
        // `ChatScreen` already replaces the composer with a banner for a blocked
        // contact, so a user cannot type one by hand. That is a screen, though, and it
        // is not the only way a send starts: the notification quick-reply, a share
        // intent, a scheduled message firing later, the forward picker and the
        // watch/web surfaces all call straight into here. Blocking has to mean
        // "nothing leaves for this key", and the only place that can be true for every
        // caller is the one they all pass through.
        //
        // Checked against the same NORMALIZED key the receive paths use, so a block
        // stored base64url still matches a send addressed base64 (and vice versa).
        if (isBlockedSender(recipientPublicKey)) {
            Log.d(TAG, "🚫 Refusing to send to blocked ${recipientPublicKey.take(8)}…")
            return Result.failure(
                IllegalStateException(context.getString(R.string.chat_contact_blocked))
            )
        }

        return try {
            // A retry keeps the identity of the message it retries — see `resendOf`.
            val messageId = resendOf?.id ?: UUID.randomUUID().toString()

            // 📸 Check if this is first message to this contact
            val existingContact = contactDao.getContactByPublicKey(recipientPublicKey)
            // __FIRST_CONTACT_NICKNAME_2026_09_23__ "First" = we have never WRITTEN to them, not
            // "no contact row": a contact added by QR / address before the first send has a row,
            // so the profile (nickname) never went and Desktop titled the chat with our address.
            // Read BEFORE this message is inserted. iOS sends it for a new conversation too.
            val isFirstMessage = existingContact == null ||
                (resendOf == null && !messageDao.hasOutgoingUserMessage(recipientPublicKey))

            // Create contact if doesn't exist (so conversation appears in list)
            ensureContactExists(recipientPublicKey)

            // Create message
            val message = Message(
                id = messageId,
                senderPublicKey = myPublicKey,
                recipientPublicKey = recipientPublicKey,
                content = content,
                // ...and its date. A resend delivers the message that was written,
                // not a new one written now (see `resendOf`).
                timestamp = resendOf?.timestamp ?: System.currentTimeMillis(),
                isFromMe = true,
                // 🔧 P0/P1 FIX (device walkthrough #2, 2026-08-13): this was
                // `isRead = true`. Every outgoing message was born READ, so the
                // bubble rendered blue ✓✓ "Read" the instant it left — a FALSE
                // read receipt in an E2E messenger, shown even for messages the
                // recipient could not possibly have opened, and even for messages
                // that never left the device at all.
                //
                // It also DISABLED real read receipts: `handleReadReceipt` (below)
                // collects rows via `getUnreadSentMessages`, whose query is
                // `isFromMe = 1 AND isRead = 0` (AppDatabase.kt:309) — with every
                // sent row already isRead=1 that query always returned empty and a
                // genuine 📖READ_RECEIPT📖 from the peer updated nothing.
                //
                // A sent message is now born unread. It becomes `isDelivered` when a
                // transport accepts it, and `isRead` ONLY when the peer's read
                // receipt arrives — which is what iOS does
                // (OSHI/DeliveryReceiptManager.swift drives `.read`; the bubble
                // status comes off the receipt, never off the send).
                //
                // Safe for badges: every unread query filters `isFromMe = 0`
                // (AppDatabase.kt:255,259), so own messages never inflate a count.
                isRead = false
            )

            // Save locally first (original content for local display)
            messageDao.insert(sanitizeMediaForInsert(message))
            persistedMessageId = messageId
            notifyWebSession(message)

            // 🕵️ Steganography is NOT applied here — deliberately, and this is the fix.
            //
            // iOS never stego-wraps a message on this path. `steganographyEnabled`
            // (`SteganographyManager.swift:530`) has zero consumers in any iOS send or
            // receive path; text-stego lives entirely inside the covert relay
            // (`CovertChannelManager.swift:806-807` ← `MessageManager.swift:4052`,
            // gated on `covertChannelEnabled`).
            //
            // Android used to replace `content` with generated cover text here whenever
            // the toggle was on. The recipient — iPhone or Android — decrypted and
            // displayed the COVER TEXT; the real message was simply gone, silently,
            // every message, for as long as the toggle stayed on. See the note on
            // `isSteganographyEnabled()` above.
            val contentToEncrypt = content
            // __RESTRICTED_NETWORK_HINT_2026_11_18__ (docs/RESTRICTED_NETWORK_HINT.md) The
            // V2 (disguised-path) plaintext carries the invisible "restricted network"
            // suffix when cover is ON and the sender allows it. Only the V2 sites use
            // it; mesh/LoRa/legacy keep the bare text. The local row never has it.
            val v2Content = if (com.oshi.messenger.util.RestrictedNetworkHint.shouldMark(
                    isCovertChannelEnabled(), com.oshi.messenger.util.RestrictedNetworkHintSetting.isOn(context))
            ) com.oshi.messenger.util.RestrictedNetworkHint.mark(contentToEncrypt) { com.oshi.messenger.data.model.OSHIControlPayload.isControl(it) }
            else contentToEncrypt

            // 📡 OFF-GRID SHORTCUT — iOS `MessageManager.swift:4140-4157`.
            //
            // LoRa used to be reachable only from the bottom of the fallback
            // chain, which meant a phone with NO network at all still had to walk
            // the V2 relay timeout, the mesh discovery await and the whole VPS
            // attempt — tens of seconds spent on transports that cannot possibly
            // succeed — before the radio was offered the message. Exactly the
            // situation somebody bought a LoRa node for.
            //
            // This reorders NOTHING for anyone with a network: when a physical
            // path exists we still prefer it, because LoRa is ~2 s per frame and
            // text-only. It only skips attempts already known to be impossible.
            //
            // Not gated on the radio being READY: `queueOrSendSecureMessage`
            // queues for a node that is paired but out of Bluetooth range and
            // returns false, so we fall through to the normal chain and the
            // store-and-forward flush becomes a free second chance.
            // Gated on a radio being PAIRED, and the envelope is carried forward
            // rather than thrown away: `encryptWithDoubleRatchet` ADVANCES THE
            // RATCHET. Encrypting here and again below would spend two ratchet
            // steps on one message and leave a permanent gap in the receiver's
            // skipped-key store — for every message, for every off-grid user,
            // including the ones who own no radio at all.
            var legacyEnvelope: ByteArray? = null
            if (loRaPaired && !hasPhysicalNetworkPath()) {
                legacyEnvelope = encryptWithDoubleRatchet(contentToEncrypt, recipientPublicKey)
                if (deliverViaLoRa(messageId, recipientPublicKey, legacyEnvelope, message.timestamp)) {
                    Log.d(TAG, "📡 No physical network path — sent via LoRa, skipping internet timeouts")
                    if (isFirstMessage) sendProfileUpdate(recipientPublicKey)
                    AppReviewManager.trackMessageSent(context, recipientPublicKey.takeUnless { OSHIControlPayload.isControl(content) })
                    return Result.success(message)
                }
                Log.d(TAG, "📡 Off-grid but LoRa refused or only queued — continuing the normal chain")
            }

            // ── OSHI V2 transport (gated by V2Config — OFF by default) ──────────
            // Preferred path when both peers are V2-capable: X3DH + Double Ratchet
            // over the /v2/messages relay, byte-identical to iOS MessageManager+V2.
            // sendText returns false — and we fall through to the legacy encrypt +
            // mesh/VPS routing UNCHANGED — whenever V2 is disabled, the peer has no
            // published prekey bundle, or the relay is unreachable (e.g. offline, so
            // mesh still delivers). In the shipping build V2 is off, so this is a
            // no-op and the legacy path runs exactly as before: zero regression.
            val sentViaV2 = try {
                // Carry the APP's message id onto the envelope (iOS does the same,
                // V2MessageRouter.swift:539-547) so one logical message keeps ONE id
                // across mesh / IPFS / v2 and the receiver's dedup works.
                v2MessageRouter.sendText(
                    recipientPublicKey,
                    v2Content.toByteArray(Charsets.UTF_8),
                    messageId,
                    // __RESEND_TIMESTAMP_2026_09_17__ The bubble's date on the OTHER
                    // phone comes from this envelope and nothing else. Pass the row's
                    // own timestamp so both ends agree; letting the router stamp the
                    // encrypt instead dated the message at whatever moment the send
                    // reached the wire — after the contact upsert, the local insert,
                    // the off-grid probe and, for a first message, a prekey fetch —
                    // and iOS then sorted it below texts that were genuinely later.
                    originalTimestampMs = message.timestamp,
                )
            } catch (e: Exception) {
                Log.w(TAG, "V2 send threw, using legacy path: ${e.message}")
                false
            }
            if (sentViaV2) {
                updateMessage(message.copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true))
                Log.d(TAG, "📤 Message sent via OSHI V2 (Double Ratchet relay)")
                // The V2 relay does not push — fire the same wake the legacy path
                // fires, or the recipient never learns the message arrived.
                pushAfterV2Send(recipientPublicKey, messageId, content)
                if (isFirstMessage) sendProfileUpdate(recipientPublicKey)
                AppReviewManager.trackMessageSent(context, recipientPublicKey.takeUnless { OSHIControlPayload.isControl(content) })
                return Result.success(message)
            }

            // Encrypt with Double Ratchet (iOS-compatible EncryptedMessage format).
            // Reuses the envelope the off-grid LoRa attempt already built, when it
            // built one — one message, one ratchet step.
            // Try Android mesh first (via Nearby Connections)
            val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == recipientPublicKey }
            // Try iOS mesh (via CrossPlatformMesh TCP/BLE)
            val iosPeer = findIosPeerOrAwait(recipientPublicKey)

            // __COVER_FAIL_CLOSED_2026_11_18__ "Disguise OSHI traffic" is ON and the
            // disguised (V2) send did not deliver: never fall back to the legacy
            // relay (plain /api/queue + IPFS upload). Local radios (mesh, LoRa) are
            // not internet traffic and still get their turn; otherwise the message
            // waits for the disguised path. Checked BEFORE the legacy encrypt so a
            // parked message does not spend a ratchet step it will never use.
            val coverOn = isCovertChannelEnabled()
            if (!CoverOutbox.legacyRelayAllowed(coverOn) && meshPeer == null && iosPeer == null &&
                MeshtasticManager.instanceOrNull?.isReady != true &&
                com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context, recipientPublicKey)
            ) {
                val v2Bytes = v2Content.toByteArray(Charsets.UTF_8)
                parkForCover(message, recipientPublicKey, content) {
                    v2MessageRouter.sendText(recipientPublicKey, v2Bytes, messageId, originalTimestampMs = message.timestamp)
                }
                return Result.success(message)
            }

            val encrypted = legacyEnvelope
                ?: encryptWithDoubleRatchet(contentToEncrypt, recipientPublicKey)

            when {
                meshPeer != null -> {
                    // Send via Android mesh — delivery is immediate (peer connected)
                    val meshMessage = MeshMessage(
                        id = messageId,
                        type = MessageType.TEXT_MESSAGE,
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
                        timestamp = System.currentTimeMillis()
                    )
                    meshManager.sendMessageToPublicKey(recipientPublicKey, meshMessage)
                    updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                    Log.d(TAG, "📤 Message sent via Android Mesh")
                }
                iosPeer != null -> {
                    // Cross-platform send: DUAL-DELIVERY via both mesh AND VPS.
                    //
                    // The previous code only fired mesh and assumed `withTimeoutOrNull(3000) { sendMessage(); true }`
                    // signalled delivery. But `crossPlatformMesh.sendMessage` is
                    // `Unit`-returning fire-and-forget — that closure ALWAYS returns `true`
                    // immediately, even if the underlying TCP socket is half-open or the
                    // peer has gone away. Android then marks the message MESH-delivered
                    // and the VPS fallback is unreachable, so the message is silently
                    // lost (the symptom users observed: "iPhone never receives Android
                    // mesh messages, but the reverse works").
                    //
                    // Fix: always send through both transports in parallel. iPhone
                    // dedupes by message id, so dual delivery is harmless. The first
                    // path that arrives wins; if mesh's TCP path is broken, VPS
                    // delivers — and vice versa.
                    val encryptedJson = String(encrypted, Charsets.UTF_8)
                    // __MESH_STABLE_ID_2026_09_27__ Wrapped so the iPhone files the mesh
                    // copy under the relay copy's id and drops whichever arrives second.
                    val meshPayload = crossPlatformMeshSecureMessage(
                        encryptedJson, messageId, myPublicKey, recipientPublicKey, message.timestamp
                    ) ?: encryptedJson
                    try {
                        crossPlatformMesh.sendMessage(recipientPublicKey, meshPayload, "TEXT_MESSAGE")
                        Log.d(TAG, "📤 Message sent via iOS Mesh (${iosPeer.platform}) — dual-send pending VPS")
                    } catch (e: Exception) {
                        Log.w(TAG, "iOS mesh send threw: ${e.message} — VPS will still try")
                    }
                    // __COVER_FAIL_CLOSED_2026_11_18__ no legacy relay leg in cover mode: mesh only.
                    val vpsOk = if (!CoverOutbox.legacyRelayAllowed(coverOn)) false
                        else try { vpsClient.sendMessage(recipientPublicKey, encrypted, messageId, originalTimestampMs = message.timestamp) } catch (_: Exception) { false }
                    if (vpsOk) {
                        updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                        Log.d(TAG, "📤 Cross-platform dual-send OK (mesh + VPS)")
                    } else {
                        // VPS rejected — mesh is our only chance now. Mark sent
                        // so the UI doesn't show a perpetual spinner; if mesh
                        // didn't deliver either, the receiver-side ack flow
                        // would surface that (currently no ack — best-effort).
                        updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                        Log.w(TAG, "⚠️ VPS send failed, relying on mesh path alone")
                    }
                }
                else -> {
                    // 📡 LoRa as a PEER-REACHABILITY transport, not just an
                    // off-grid one. iOS `MessageManager.swift:4159-4180`.
                    //
                    // The shortcut above asks "am *I* off-grid?". Nothing asked
                    // "is the RECIPIENT reachable?" — so with this phone online
                    // and the peer dark, two paired radios sat unused while the
                    // ciphertext parked on the relay waiting for a phone that was
                    // not coming.
                    //
                    // LoRa sits where mesh sits: tried when the peer looks
                    // unreachable by network, BEFORE committing to the relay's
                    // store-and-forward. Deliberately in this `else` branch only
                    // — a live mesh or iOS-mesh peer is a working path and LoRa
                    // must not displace it. Gated on the radio being READY, so
                    // with no radio this branch does not exist and the chain is
                    // exactly what it was for every user without one.
                    // __ROUTE_BY_POLICY_2026_10_20__ The "which comes first"
                    // judgement used to live in this `if`, as `lane(...) ==
                    // PEER_UNREACHABLE`. It now comes from the policy, so the
                    // order this router takes is the order the policy publishes.
                    // `lane()` is still there and still correct — it answers a
                    // narrower question (which of the two original LoRa
                    // situations applies) and its tests keep it honest.
                    //
                    // `peerOnMesh = false` is not a guess: this `else` branch IS
                    // the no-live-mesh-peer case. `carriesMedia = false` because
                    // this is `sendTextMessage`; media has its own path.
                    val radio = MeshtasticManager.instanceOrNull
                    val routing = MessageSendPolicy.RoutingConditions(
                        hasPhysicalPath = true,   // we are here, so a path exists
                        peerOnMesh = false,
                        radioReady = radio?.isReady == true,
                        peerLikelyOnline = MessageSendPolicy.isPeerLikelyOnline(
                            existingContact?.lastSeen, System.currentTimeMillis()),
                        carriesMedia = false,
                        internetAllowedForSending =
                            com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context),
                        preferRadioOverRelay =
                            com.oshi.messenger.service.TransportPreferences.preferRadioOverRelay(context),
                        radioJammed = radio?.isJammed == true,
                    )
                    if (MessageSendPolicy.radioComesBeforeRelay(routing)) {
                        if (deliverViaLoRa(messageId, recipientPublicKey, encrypted, message.timestamp)) {
                            Log.d(TAG, "📡 Radio ahead of the relay by policy — delivered over LoRa")
                            if (isFirstMessage) sendProfileUpdate(recipientPublicKey)
                            AppReviewManager.trackMessageSent(context, recipientPublicKey.takeUnless { OSHIControlPayload.isControl(content) })
                            return Result.success(message)
                        }
                        Log.d(TAG, "📡 LoRa refused or unavailable — falling through")
                    }

                    // __NEVER_OVER_THE_INTERNET_2026_10_20__ The radio and the
                    // mesh have had their turn. Stop here rather than let the
                    // relay pick up the slack: the row stays undelivered and
                    // goes out when a radio or a peer is in range. The clients
                    // refuse on their own too — this branch exists so the
                    // refusal is recorded against the message instead of looking
                    // like a failed upload.
                    if (!routing.internetAllowedForSending) {
                        // __NEVER_DROP_HELD_2026_11_18__ "Keeping it for an offline route" used to keep it
                        // NOWHERE: when the policy had left the radio out (jammed, not connected) the
                        // envelope was dropped here, the row sat pending for ever, and nothing would ever
                        // retry it. Hand it to the radio's durable held queue (it goes out when the radio
                        // is back / the channel clears); with no radio able to hold it, say so on screen.
                        if (holdForRadioOrFail(messageId, recipientPublicKey, encrypted, message.timestamp)) {
                            Log.d(TAG, "🚫 Internet sending is off — ${messageId.take(8)}… held for the radio")
                        } else {
                            Log.d(TAG, "🚫 Internet sending is off and no radio can hold ${messageId.take(8)}… — marked not delivered")
                        }
                        return Result.success(message)
                    }

                    // __COVER_FAIL_CLOSED_2026_11_18__ The old branch here called
                    // `sendViaCovertChannel` (always false) and then the plain relay
                    // "as a fallback" — the leak. In cover mode the radio has had its
                    // turn above; the message now waits for the disguised path.
                    if (!CoverOutbox.legacyRelayAllowed(coverOn)) {
                        val v2Bytes = v2Content.toByteArray(Charsets.UTF_8)
                        parkForCover(message, recipientPublicKey, content) {
                            v2MessageRouter.sendText(recipientPublicKey, v2Bytes, messageId, originalTimestampMs = message.timestamp)
                        }
                        return Result.success(message)
                    }
                    // Normal VPS delivery
                    val success = vpsClient.sendMessage(recipientPublicKey, encrypted, messageId, originalTimestampMs = message.timestamp)
                    if (success) {
                        updateMessage(message.copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true))
                        Log.d(TAG, "📤 Message sent via VPS")
                    } else {
                        throw Exception("Failed to send via VPS")
                    }
                }
            }

            // 📸 If first message, send our profile picture
            if (isFirstMessage) {
                Log.d(TAG, "📸 First message to ${recipientPublicKey.take(8)}... - sending profile")
                sendProfileUpdate(recipientPublicKey)
            }

            // Track successful message send for Play Store review prompt
            AppReviewManager.trackMessageSent(context, recipientPublicKey.takeUnless { OSHIControlPayload.isControl(content) })

            Result.success(message)
        } catch (e: Exception) {
            // Mark the already-persisted row as failed so the UI can tell the truth.
            persistedMessageId?.let { markSendFailed(it) }
            Log.e(TAG, "❌ Send message failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * __INTEROP_REPLY_2026_11_09__ Reply to a bare/stock Meshtastic node — the
     * `lora!<nodehex>` pseudo-identity thread `handleLoRaInteropText` opens for
     * a node that has proved no OSHI identity.
     *
     * This is NOT `sendTextMessage` with a detour; call it INSTEAD, never
     * after it. A pseudo-identity has no key to encrypt to and no route back
     * (the refusal `noticeBlockedSender` already states above, `:6018-6020`),
     * so this never touches the ratchet, the V2 router, or the mesh/VPS
     * fallback chain — the only thing that leaves this device is the
     * plaintext itself, on the same TEXT_MESSAGE_APP port a stock Meshtastic
     * client reads, addressed to the node number the pseudo-identity encodes
     * ([LoRaNodeIdentityMap.nodeNumFromFallbackKey] — there is nothing else to
     * look up; the hex IS the node number, by construction).
     *
     * The bubble this inserts gets the UNVERIFIED · PLAINTEXT marker from
     * `ChatScreen` the same way an inbound interop bubble does — both derive
     * it from [LoRaNodeIdentityMap.isFallbackConversationKey] on the
     * conversation key, not from a stored flag — because a raw Meshtastic
     * packet is unauthenticated, and a reply typed into this thread is
     * exactly as unauthenticated as the message that opened it.
     */
    suspend fun sendLoRaInteropReply(conversationKey: String, content: String): Result<Message> {
        val nodeNum = LoRaNodeIdentityMap.nodeNumFromFallbackKey(conversationKey)
            ?: return Result.failure(
                IllegalArgumentException("not a lora! pseudo-identity: $conversationKey")
            )
        val radio = MeshtasticManager.instanceOrNull
            ?: return Result.failure(IllegalStateException(context.getString(R.string.lora_error_not_connected)))

        val message = Message(
            id = UUID.randomUUID().toString(),
            senderPublicKey = myPublicKey,
            recipientPublicKey = conversationKey,
            content = content,
            timestamp = System.currentTimeMillis(),
            isFromMe = true,
            isRead = false,
            deliveryMethod = DeliveryMethod.LORA,
        )
        messageDao.insert(sanitizeMediaForInsert(message))
        notifyWebSession(message)

        // `sendText` returns true only once the frame is actually queued to a
        // connected radio — same true-means-written contract `deliverViaLoRa`
        // relies on above, so a reply typed with the node out of range is
        // shown FAILED rather than a false "sent".
        val written = runCatching { radio.sendText(content, nodeNum.toUInt()) }.getOrDefault(false)
        val finalRow = message.copy(isDelivered = written, isFailed = !written)
        updateMessage(finalRow)
        Log.d(TAG, "📡 LoRa interop reply → !${nodeNum.toString(16)}: written=$written")
        return Result.success(finalRow)
    }

    /**
     * Send media message (photo, video, audio, document)
     * Automatically chooses best delivery method: Mesh or VPS
     * @param isViewOnce If true, media disappears after viewing (iOS compatible)
     */
    suspend fun sendMediaMessage(
        recipientPublicKey: String,
        mediaType: MediaType,
        mediaData: ByteArray,
        caption: String? = null,
        isViewOnce: Boolean = false,
        /**
         * The attachment's ORIGINAL name, when the caller knows it (a document picked
         * from the file picker or arriving through the share sheet). See
         * [wireFileName] for why this must reach the wire.
         */
        attachmentFileName: String? = null,
        /**
         * __VIDEO_NOTE_2026_09_24__ A round video message (docs/VIDEO_NOTE_SPEC.md). The
         * bytes are already the final 480×480 H.264/AAC MP4 made by `VideoNoteEncoder`,
         * so they skip the compression ladder, and every envelope below gains the two
         * optional keys. Ignored for anything but [MediaType.VIDEO].
         */
        videoNoteDurationMs: Long? = null,
    ): Result<Message> {
        val isVideoNote = videoNoteDurationMs != null && mediaType == MediaType.VIDEO
        val noteWireDuration = if (isVideoNote) {
            com.oshi.messenger.service.media.VideoNote.wireDuration(videoNoteDurationMs ?: 0L)
        } else null
        val noteFragment = com.oshi.messenger.service.media.VideoNote.jsonFragment(isVideoNote, noteWireDuration)
        // 🚫 BLOCKED — same rule as sendTextMessage: nothing leaves for a blocked key,
        // whatever surface started the send (share sheet, forward picker, watch, web).
        if (isBlockedSender(recipientPublicKey)) {
            Log.d(TAG, "🚫 Refusing to send media to blocked ${recipientPublicKey.take(8)}…")
            return Result.failure(
                IllegalStateException(context.getString(R.string.chat_contact_blocked))
            )
        }

                // 🔧 P0 FIX (device walkthrough #2, 2026-08-13).
        //
        // The local row is inserted BEFORE any transport is attempted (so the bubble
        // appears instantly). When a transport then threw — e.g. `bad base-64` on a
        // malformed recipient key — the catch below only logged and returned
        // Result.failure: the already-inserted row stayed in the DB looking like a
        // perfectly normal sent message, and (with the old `isRead = true`) rendered
        // as blue ✓✓ "Read". A message that never left the device was shown to the
        // user as READ BY THE RECIPIENT.
        //
        // `persistedMessageId` is set the moment the row exists, so the catch can
        // stamp THAT row `isFailed = true`. `Message.isFailed` already existed and
        // ChatScreen already renders a red "Not delivered · tap to retry" affordance
        // for it (ChatScreen.kt, `if (message.isFailed && isFromMe)`) — nothing was
        // ever setting the flag. iOS marks the same state via
        // `MessageSendPolicy` / `DeliveryStatus.failed` and renders it in
        // `DeliveryMethodBadge.swift:54,74`.
        var persistedMessageId: String? = null
        return try {
            val messageId = UUID.randomUUID().toString()

            // Ensure contact exists (so conversation appears in list)
            ensureContactExists(recipientPublicKey)

            // Smart compression (matching iOS MediaManager behavior)
            // __VIDEO_NOTE_2026_09_24__ A note is already encoded to its contract
            // (≤480², ~1.2 Mbit/s, ≤15 s): running the ladder again could only
            // re-encode a square clip for nothing.
            val compressedData = if (isVideoNote) mediaData else try {
                when (mediaType) {
                    // __GIF_WIRE_TYPE_2026_09_13__ Un GIF sort INTACT.
                    //
                    // `compressImage` réencode en JPEG: appliqué à un GIF il n'en
                    // garde que la première image, et le destinataire reçoit une
                    // photo fixe à la place de l'animation. iOS a la même règle
                    // (`MediaManager.processGIF`: « bytes untouched »), et comme
                    // là-bas la décision se prend en RENIFLANT les octets, jamais
                    // sur une étiquette — ainsi tout GIF profite de la règle,
                    // qu'il vienne du sélecteur, du partage ou du gestionnaire de
                    // fichiers.
                    MediaType.IMAGE -> if (GifBytes.isGif(mediaData)) {
                        Log.d(TAG, "🎞️ GIF: ${mediaData.size}B sent uncompressed")
                        mediaData
                    } else {
                        val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == recipientPublicKey }
                        val iosPeer = findIosPeerOrAwait(recipientPublicKey)
                        val target = if (meshPeer != null || iosPeer != null) {
                            MediaCompressionManager.DeliveryTarget.MESH
                        } else {
                            MediaCompressionManager.DeliveryTarget.VPS
                        }
                        val result = mediaCompression.compressImage(mediaData, target)
                        if (result.isCompressed) {
                            Log.d(TAG, "📷 Image compressed: ${result.compressionInfo}")
                        }
                        result.data
                    }
                    MediaType.VIDEO -> {
                        // __VIDEO_MESH_BUDGET_2026_09_18__ A video takes the SAME
                        // budget rule as a photo, and for the same reason.
                        //
                        // This branch was pinned to `VPS`, i.e. to the 200 MB
                        // ceiling, whatever the transport. The ladder picks the
                        // best rung whose ESTIMATE fits the budget it is handed
                        // (`VideoPresetLadder.select`), so a budget of 200 MB
                        // means "1080p at 5 Mbps" for essentially every clip —
                        // and that same 1080p file was then pushed through a
                        // mesh link sized for 10 MB. The photo branch two cases
                        // up has always asked who is actually on the other end;
                        // the video branch simply never did.
                        //
                        // Handing MESH's 10 MB down instead makes the ladder walk
                        // to 720p or 540p, which is the "heavily compressed, like
                        // photos" the owner asked for — and it is what the clip
                        // needed anyway to cross that link at all.
                        val meshPeer = meshManager.connectedPeers.value
                            .find { it.publicKey == recipientPublicKey }
                        val iosPeer = findIosPeerOrAwait(recipientPublicKey)
                        val target = if (meshPeer != null || iosPeer != null) {
                            MediaCompressionManager.DeliveryTarget.MESH
                        } else {
                            MediaCompressionManager.DeliveryTarget.VPS
                        }
                        val result = mediaCompression.compressVideo(mediaData, target)
                        if (result.isCompressed) {
                            Log.d(TAG, "🎥 Video compressed for $target: ${result.compressionInfo}")
                        }
                        result.data
                    }
                    // __CONTACT_CARD_COMPAT_2026_09_27__ Whatever built these bytes
                    // (the old timestamp-less welcome card, a forwarded card from an
                    // older build, a vCard), send the one shape every shipped iPhone
                    // decodes. See `ContactCardWire.canonicalizeForWire`.
                    MediaType.CONTACT -> com.oshi.messenger.data.model.ContactCardWire
                        .canonicalizeForWire(mediaData, System.currentTimeMillis())
                    else -> mediaData  // Audio, documents: no compression
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM during media compression, using original data (${mediaData.size} bytes)")
                mediaData
            }

            val message = Message(
                id = messageId,
                senderPublicKey = myPublicKey,
                recipientPublicKey = recipientPublicKey,
                content = caption ?: "[${mediaType.name}]",
                timestamp = System.currentTimeMillis(),
                isFromMe = true,
                // 🔧 P0/P1 FIX (2026-08-13): born unread — see the long note on
                // sendTextMessage. `isRead` is a RECEIPT, not a send side effect.
                isRead = false,
                mediaType = mediaType,
                mediaData = compressedData,
                isViewOnce = isViewOnce,
                isVideoNote = isVideoNote,
                mediaDurationMs = noteWireDuration?.toLong()
            )

            messageDao.insert(sanitizeMediaForInsert(message))
            persistedMessageId = messageId

            // 📡 Off-grid with a photo: tell the user WHY it will not go.
            //
            // iOS reaches the radio for media too — `fallbackToIPFS` hands every
            // message to `deliverViaLoRa`, and the media guard lives inside
            // `queueOrSendSecureMessage` precisely "so that refusal is recorded
            // and shown like every other one instead of being a silent false"
            // (`MessageManager.swift:4105-4110`, `MeshtasticManager.kt:765-771`).
            //
            // "My photo won't send off-grid" is exactly the moment a person needs
            // to be told LoRa carries text and nothing else, and without this call
            // the refusal is never RECORDED, so the LoRa settings screen's "WHY A
            // MESSAGE DID NOT GO OUT" section stays empty and the user is told
            // nothing at all.
            //
            // The envelope handed over is deliberately empty: `carriesMedia` is
            // checked FIRST and short-circuits before any field of it is read, so
            // building (and encrypting) a real one would be work spent to reach a
            // guaranteed refusal.
            recordLoRaMediaRefusalIfOffGrid(messageId, recipientPublicKey, compressedData)

            // Create payload with media (iOS-compatible format)
            // Include isViewOnce flag for cross-platform ephemeral media support
            // Encode base64 separately to avoid building a huge string literal in one shot
            //
            // __MEDIA_SEND_OOM_2026_11_11__ LAZY, both this and `payload` below. They were
            // built eagerly, before the blob upload that never reads them: a 26 MB
            // document cost a 69 MB UTF-16 base64 String plus the JSON envelope around it
            // ON TOP of the encrypted chunks, and the send died with
            // `OutOfMemoryError: Failed to allocate a 69333352 byte allocation` (release
            // 1.6.93 on a 192 MB-heap emulator, chat already showing 25 + 60 MB files) —
            // "Not Delivered" for any large file. Only the inline fallback (small media)
            // and the legacy ratchet path need them, and only after the blob path failed.
            val mediaBase64 by lazy { android.util.Base64.encodeToString(compressedData, android.util.Base64.NO_WRAP) }
            // 🔴 P1 (2026-08-24 photo/link sweep). The escaping used to be inline here
            // and covered `"` and `\n` and NOTHING ELSE. A caption containing a single
            // backslash — a Windows path, a shrug emoticon — emitted INVALID JSON:
            //   * iOS: `isMediaMessage` still matched on the `"type":"IMAGE"`
            //     substring (MessageManager.swift:7069), then `parseAndStoreMedia`
            //     failed at `JSONSerialization` (:7268-7271) and the whole base64
            //     photo was stored as a text bubble (:6116).
            //   * Android→Android: `JSONObject(decrypted)` throws in
            //     [handleMediaContent] and the message is dropped outright.
            // 📄 …and the name, when the user actually picked one. Omitted otherwise,
            // so a camera photo's envelope is byte-for-byte what it was before.
            // Deliberately NOT [wireFileName] here: that synthesises `<uuid>.jpg`
            // when there is no real name, and iOS would store the UUID as the
            // document's displayed name. iOS omits the key in that case, so we do.
            // __WIRE_CAPTION_2026_09_27__ (D2) What the PEER is shown as a caption.
            // `caption` is also this app's local bubble text, and for audio, documents
            // and contacts that is a marker ("[AUDIO] 0:05", "[DOCUMENT] name",
            // "[CONTACT] Alice") an iPhone printed verbatim under the bubble. The
            // markers stay local; a document's name travels as its file name.
            val peerCaption = wireCaption(mediaType, caption)
            val peerFileName = attachmentFileName
                ?: if (mediaType == MediaType.DOCUMENT) documentNameFromCaption(caption) else null
            val payload by lazy {
                legacyMediaEnvelope(
                    mediaType.name, peerCaption, mediaBase64, isViewOnce,
                    iosMediaType = legacyWireMediaType(mediaType, GifBytes.isGif(compressedData)),
                    fileName = peerFileName,
                    videoNoteFragment = noteFragment
                )
            }

            // ── OSHI V2 media ───────────────────────────────────────────────────
            // PRIMARY: the blob store, exactly as iOS `sendFile` does
            // (V2MessageRouter.swift:610-776) — AEAD-sealed 2 MiB chunks to
            // /v2/blobs + a `{"kind":"v2file",…}` ratchet message carrying the key.
            // That is the ONLY media shape iOS's V2 receiver turns into a media
            // bubble via its `.file` branch (MessageManager+V2.swift:690-727).
            //
            // FALLBACK: an INLINE envelope for small payloads when the blob upload
            // fails. It must be iOS's wrapper — `{"type":"media", …, "content":<b64>}`
            // (parsed by `v2ParseEmbeddedMediaJSON`, MessageManager+V2.swift:947-956)
            // — NOT the `{"type":"IMAGE","data":…}` Android used to send, which iOS's
            // V2 path never parses: `parseAndStoreMedia` is reachable only from the
            // legacy decrypt branches, and `v2Deliver` sets plaintext first so
            // MessageManager.swift:5551 skips it. Every Android→iPhone photo over V2
            // therefore rendered as a giant raw-JSON text bubble. Android's own
            // `handleMediaContent` already accepts this wrapper (:1770-1780).
            // __COVER_FAIL_CLOSED_2026_11_18__ The disguised retry for this attachment,
            // built from the same bytes as the V2 attempt below (blob, then inline).
            var coverRetry: (suspend () -> Boolean)? = null
            run {
                // __GIF_WIRE_TYPE_2026_09_13__ Les octets décident, pas l'étiquette.
                //
                // Étiqueté `image/jpeg`, un GIF se fait réencoder par certains
                // destinataires et l'animation disparaît; `gif`/`image/gif` est
                // exactement ce qu'iOS émet depuis `MediaManager.gifWireType`, et
                // [MediaType.fromWireLabel] mappe déjà `"gif"` → IMAGE à l'arrivée,
                // sur Android comme sur les vieux builds iOS.
                val isGif = GifBytes.isGif(compressedData)
                val iosMediaType = when {
                    isGif -> "gif"
                    mediaType == MediaType.IMAGE -> "image"
                    mediaType == MediaType.VIDEO -> "video"
                    mediaType == MediaType.AUDIO -> "audio"
                    mediaType == MediaType.CONTACT -> "contact"
                    else -> "document"
                }
                val mime = when {
                    isGif -> "image/gif"
                    mediaType == MediaType.IMAGE -> "image/jpeg"
                    mediaType == MediaType.VIDEO -> "video/mp4"
                    mediaType == MediaType.AUDIO -> "audio/m4a"
                    mediaType == MediaType.CONTACT -> "text/vcard"
                    else -> "application/octet-stream"
                }
                val fileName = wireFileName(messageId, mediaType, peerFileName, isGif)
                coverRetry = {
                    v2MessageRouter.sendFile(
                        peerUserKey = recipientPublicKey, data = compressedData, filename = fileName,
                        mime = mime, mediaType = iosMediaType, isViewOnce = isViewOnce, msgId = messageId,
                        videoNote = isVideoNote, durationMs = noteWireDuration, caption = peerCaption,
                    )
                }

                val sentViaBlob = try {
                    v2MessageRouter.sendFile(
                        peerUserKey = recipientPublicKey,
                        data = compressedData,
                        filename = fileName,
                        mime = mime,
                        mediaType = iosMediaType,
                        isViewOnce = isViewOnce,
                        msgId = messageId,
                        videoNote = isVideoNote,
                        durationMs = noteWireDuration,
                        // __V2_CAPTION_2026_09_27__ read by Android, ignored by iOS.
                        caption = peerCaption,
                        // __ATTACHMENT_DATED_AT_UPLOAD_2026_09_17__ Deliberately NOT
                        // the row's timestamp. An attachment is dated when its upload
                        // FINISHES, which is what iOS does (`sendFile` there takes no
                        // `originalTimestamp`) and what we want: a video that took ten
                        // minutes to upload should land as the newest thing in the
                        // thread, where the recipient will see it — not ten minutes up,
                        // buried above messages they have already read.
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "V2 blob media send threw: ${e.message}")
                    false
                }
                if (sentViaBlob) {
                    updateMessage(message.copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true))
                    Log.d(TAG, "📤 Media sent via OSHI V2 blob (${mediaType.name}, ${compressedData.size}B)")
                    pushAfterV2Send(recipientPublicKey, messageId, null)
                    sendCaptionFollowUp(recipientPublicKey, messageId, mediaType, peerCaption)
                    return Result.success(message)
                }

                if (compressedData.size <= V2_INLINE_MEDIA_MAX_BYTES) {
                    // Same escaper as the V2 wire codec — a bare CR or TAB in a caption
                    // is as fatal to `JSONSerialization` as a backslash.
                    val escaped = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(peerCaption ?: "")
                    val escapedName = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(fileName)
                    val iosInline = """{"type":"media","mediaType":"$iosMediaType","content":"$mediaBase64","fileName":"$escapedName","size":${compressedData.size},"caption":"$escaped","isViewOnce":$isViewOnce$noteFragment}"""
                    val mediaSentViaV2 = try {
                        // __ATTACHMENT_DATED_AT_UPLOAD_2026_09_17__ Media is dated
                        // when it is SENT, not when it was picked — same rule as the
                        // blob path above, so small and large attachments behave
                        // alike and both match iOS.
                        v2MessageRouter.sendText(
                            recipientPublicKey,
                            iosInline.toByteArray(Charsets.UTF_8),
                            messageId,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "V2 media send threw, using legacy path: ${e.message}")
                        false
                    }
                    if (mediaSentViaV2) {
                        updateMessage(message.copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true))
                        Log.d(TAG, "📤 Media sent via OSHI V2 (${mediaType.name}, ${compressedData.size}B inline)")
                        pushAfterV2Send(recipientPublicKey, messageId, null)
                        // iOS's inline parser (`v2ParseEmbeddedMediaJSON`) ignores `caption`.
                        sendCaptionFollowUp(recipientPublicKey, messageId, mediaType, peerCaption)
                        return Result.success(message)
                    }
                }
            }

            // Try Android mesh first (via Nearby Connections)
            val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == recipientPublicKey }
            // Try iOS mesh (via CrossPlatformMesh TCP/BLE)
            val iosPeer = findIosPeerOrAwait(recipientPublicKey)

            // __COVER_FAIL_CLOSED_2026_11_18__ Same rule as text (audit finding 1:
            // the media legacy path had no cover check at all). Cover ON + V2 did not
            // deliver + no local mesh peer → wait for the disguised path; never the
            // legacy relay.
            val coverOn = isCovertChannelEnabled()
            if (!CoverOutbox.legacyRelayAllowed(coverOn) && meshPeer == null && iosPeer == null &&
                com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context, recipientPublicKey)
            ) {
                parkForCover(message, recipientPublicKey, null, coverRetry ?: { false })
                return Result.success(message)
            }

            val encrypted = encryptWithDoubleRatchet(payload, recipientPublicKey)

            when {
                meshPeer != null -> {
                    // Send via Android mesh
                    val meshMessage = MeshMessage(
                        id = messageId,
                        type = MessageType.MEDIA_MESSAGE,
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
                        timestamp = System.currentTimeMillis()
                    )
                    meshManager.sendMessageToPublicKey(recipientPublicKey, meshMessage)
                    updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                    Log.d(TAG, "📤 Media sent via Android Mesh (${mediaType.name})")
                }
                iosPeer != null -> {
                    // CROSS-PLATFORM MESH MEDIA — ratchet-ENCRYPTED, inline.
                    //
                    // __LEAK_FIX_2026_09_25__ (audit CRITICAL, Android → iPhone LAN mesh)
                    // This branch used to hand the iPhone a PLAINTEXT
                    // `{data, mediaType, fileName, fileSize, caption, …}` MEDIA_MESSAGE
                    // (see [crossPlatformMediaEnvelope]): photo, document, file name and
                    // caption readable by anyone on the same Wi-Fi / BLE link. iOS fixed
                    // its own send on 2026-09-10 (F-mesh#1: media rides the encrypted
                    // text branch, `meshWireSafe()`), and so do we now: the mesh carries
                    // the SAME `EncryptedMessage` JSON the VPS dual-send below carries —
                    // one ratchet step, one ciphertext — as a TEXT_MESSAGE, exactly like
                    // a text (`sendTextMessage`'s iOS-peer branch). iOS decodes it on
                    // `handleCrossPlatformTextMessage` path 1, ratchet-decrypts it in
                    // `receiveMessage`, and rebuilds the attachment from the decrypted
                    // [legacyMediaEnvelope] via `isMediaMessage` / `parseAndStoreMedia`
                    // (Android-direct `{"type":"IMAGE","data":…}` form) — the path every
                    // legacy VPS media message already takes on every iOS build.
                    //
                    // Too large for one mesh frame → no mesh leg at all; the encrypted
                    // VPS send below delivers it. Never a cleartext fallback.
                    //
                    // Receive side is unchanged: raw MEDIA_MESSAGE / DOCUMENT_MESSAGE
                    // frames from older peers are still ACCEPTED.
                    val meshPayload = crossPlatformMeshEncryptedPayload(encrypted)?.let {
                        // __MESH_STABLE_ID_2026_09_27__ same id as the relay copy below.
                        crossPlatformMeshSecureMessage(
                            it, messageId, myPublicKey, recipientPublicKey, System.currentTimeMillis()
                        )
                    }
                    if (meshPayload != null) {
                        try {
                            crossPlatformMesh.sendMessage(recipientPublicKey, meshPayload, "TEXT_MESSAGE")
                            Log.d(TAG, "📤 Media sent via iOS Mesh (${mediaType.name}, ratchet-encrypted, ${compressedData.size}B) — VPS dual-send pending")
                        } catch (e: Exception) {
                            Log.w(TAG, "iOS mesh media send threw: ${e.message} — VPS will still try")
                        }
                    } else {
                        Log.d(TAG, "📤 Media too large for one mesh frame (${encrypted.size}B encrypted) — VPS only")
                    }
                    // Dual-send: VPS in parallel with the encrypted payload. iPhone
                    // dedupes by message id, so whichever path wins delivers the
                    // image. If the mesh TCP is half-open, VPS still gets through.
                    val vpsOk = if (!CoverOutbox.legacyRelayAllowed(coverOn)) false  // __COVER_FAIL_CLOSED_2026_11_18__ mesh only
                        else try { vpsClient.sendMessage(recipientPublicKey, encrypted, messageId) } catch (_: Exception) { false }  // __ATTACHMENT_DATED_AT_UPLOAD_2026_09_17__ media: dated at send, like iOS
                    if (vpsOk) {
                        updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                        Log.d(TAG, "📤 Media dual-send OK (mesh + VPS)")
                    } else {
                        // VPS rejected — mesh is our only chance. Mark sent so the
                        // UI doesn't spin; receiver-side dedupe handles a duplicate
                        // delivery if both arrive.
                        updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                        Log.w(TAG, "⚠️ VPS media send failed, relying on mesh path alone")
                    }
                }
                else -> {
                    // Send via VPS
                    val success = vpsClient.sendMessage(recipientPublicKey, encrypted, messageId)  // __ATTACHMENT_DATED_AT_UPLOAD_2026_09_17__ media: dated at send, like iOS
                    if (success) {
                        updateMessage(message.copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true))
                        Log.d(TAG, "📤 Media sent via VPS (${mediaType.name})")
                    } else {
                        throw Exception("Failed to send media via VPS")
                    }
                }
            }

            Result.success(message)
        } catch (e: OutOfMemoryError) {
            persistedMessageId?.let { markSendFailed(it) }
            Log.e(TAG, "OOM in sendMediaMessage (${mediaData.size} bytes): ${e.message}")
            Result.failure(Exception("Image too large to process. Please try a smaller image."))
        } catch (e: Exception) {
            // Mark the already-persisted row as failed so the UI can tell the truth.
            persistedMessageId?.let { markSendFailed(it) }
            Log.e(TAG, "❌ Send media message failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Send location message (one-time or live)
     * Automatically chooses best delivery method: Mesh or VPS
     */
    suspend fun sendLocationMessage(
        recipientPublicKey: String,
        locationContent: String  // Already formatted with 📍LOCATION📍 prefix
    ): Result<Message> {
                // 🔧 P0 FIX (device walkthrough #2, 2026-08-13).
        //
        // The local row is inserted BEFORE any transport is attempted (so the bubble
        // appears instantly). When a transport then threw — e.g. `bad base-64` on a
        // malformed recipient key — the catch below only logged and returned
        // Result.failure: the already-inserted row stayed in the DB looking like a
        // perfectly normal sent message, and (with the old `isRead = true`) rendered
        // as blue ✓✓ "Read". A message that never left the device was shown to the
        // user as READ BY THE RECIPIENT.
        //
        // `persistedMessageId` is set the moment the row exists, so the catch can
        // stamp THAT row `isFailed = true`. `Message.isFailed` already existed and
        // ChatScreen already renders a red "Not delivered · tap to retry" affordance
        // for it (ChatScreen.kt, `if (message.isFailed && isFromMe)`) — nothing was
        // ever setting the flag. iOS marks the same state via
        // `MessageSendPolicy` / `DeliveryStatus.failed` and renders it in
        // `DeliveryMethodBadge.swift:54,74`.
        var persistedMessageId: String? = null
        return try {
            val messageId = UUID.randomUUID().toString()

            // Ensure contact exists (so conversation appears in list)
            ensureContactExists(recipientPublicKey)

            val message = Message(
                id = messageId,
                senderPublicKey = myPublicKey,
                recipientPublicKey = recipientPublicKey,
                content = locationContent,
                timestamp = System.currentTimeMillis(),
                isFromMe = true,
                // 🔧 P0/P1 FIX (2026-08-13): born unread — see the long note on
                // sendTextMessage. `isRead` is a RECEIPT, not a send side effect.
                isRead = false,
                mediaType = MediaType.LOCATION
            )

            messageDao.insert(sanitizeMediaForInsert(message))
            persistedMessageId = messageId

            // __CONTROL_OVER_V2_2026_10_28__ LAZY, and that is load-bearing.
            // `encryptWithDoubleRatchet` ADVANCES THE LEGACY RATCHET, so computing it
            // eagerly would spend a step even when the V2 lane carries the share, leaving
            // a permanent gap in the receiver's skipped-key store. The mesh branches still
            // touch it and still get it; the relay branch touches it only if V2 declines.
            val encrypted by lazy { encryptWithDoubleRatchet(locationContent, recipientPublicKey) }

            // Try Android mesh first (via Nearby Connections)
            val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == recipientPublicKey }
            // Try iOS mesh (via CrossPlatformMesh TCP/BLE)
            val iosPeer = findIosPeerOrAwait(recipientPublicKey)

            when {
                meshPeer != null -> {
                    // Send via Android mesh
                    val meshMessage = MeshMessage(
                        id = messageId,
                        type = MessageType.TEXT_MESSAGE,  // Location sent as text with 📍 prefix
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
                        timestamp = System.currentTimeMillis()
                    )
                    meshManager.sendMessageToPublicKey(recipientPublicKey, meshMessage)
                    updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                    Log.d(TAG, "📤 Location sent via Android Mesh")
                }
                iosPeer != null -> {
                    // __MESH_STABLE_ID_2026_09_27__ Same dual-send as a text: the mesh leg
                    // as a SecureMessage under THIS id (so a later delete/reaction on the
                    // location matches on the iPhone, and the relay copy is deduped), and
                    // the relay leg so a half-open LAN link cannot lose the share.
                    val meshPayload = crossPlatformMeshSecureMessage(
                        String(encrypted, Charsets.UTF_8), messageId, myPublicKey, recipientPublicKey, message.timestamp
                    ) ?: android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                    try {
                        crossPlatformMesh.sendMessage(recipientPublicKey, meshPayload, "TEXT_MESSAGE")
                    } catch (e: Exception) {
                        Log.w(TAG, "iOS mesh location send threw: ${e.message} — VPS will still try")
                    }
                    try {
                        vpsClient.sendMessage(recipientPublicKey, encrypted, messageId, originalTimestampMs = message.timestamp)
                    } catch (_: Exception) { }
                    updateMessage(message.copy(deliveryMethod = DeliveryMethod.MESH, isDelivered = true))
                    Log.d(TAG, "📤 Location sent via iOS Mesh + VPS (${iosPeer.platform})")
                }
                else -> {
                    // __CONTROL_OVER_V2_2026_10_28__ V2 first, legacy as the fallback —
                    // the same fix as receipts and typing, and for the same reason: this
                    // branch was `vpsClient` ONLY, so a peer that speaks V2 and not the
                    // legacy lane (the desktop client, deliberately — its PARITY row 0.23)
                    // never received a location share at all. Its row 0.19 recorded the
                    // symptom from the other side: "a handset shared its position at a real
                    // desktop and the desktop got nothing", with that client's own codecs
                    // "still right and still byte-tested" and nothing to feed them.
                    //
                    // This is a TRANSPORT fix and not a sharing decision. The recipient is
                    // the contact the user already chose, both lanes are end-to-end
                    // encrypted to that same contact, and both terminate on the same relay
                    // — no new party, no new server, no wider exposure. What changes is
                    // that the share reaches the person it was addressed to instead of
                    // failing silently.
                    val lane = com.oshi.messenger.network.v2.ControlLane.preferV2(
                        v2 = {
                            v2MessageRouter.sendText(
                                recipientPublicKey,
                                locationContent.toByteArray(Charsets.UTF_8),
                                messageId,
                                originalTimestampMs = message.timestamp,
                            )
                        },
                        legacy = {
                            if (!vpsClient.sendMessage(recipientPublicKey, encrypted, messageId, originalTimestampMs = message.timestamp)) {
                                throw Exception("Failed to send location via VPS")
                            }
                        },
                        onV2Error = { Log.w(TAG, "V2 location send threw, using legacy lane: ${it.message}") },
                    )
                    updateMessage(message.copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true))
                    // The V2 relay does not push on its own; the legacy send already did.
                    if (lane == com.oshi.messenger.network.v2.ControlLane.Lane.V2) {
                        pushAfterV2Send(recipientPublicKey, messageId, locationContent)
                    }
                    Log.d(TAG, "📤 Location sent via ${lane.name.lowercase()}")
                }
            }

            Result.success(message)
        } catch (e: Exception) {
            // Mark the already-persisted row as failed so the UI can tell the truth.
            persistedMessageId?.let { markSendFailed(it) }
            Log.e(TAG, "❌ Send location message failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Update an existing location message (for live location updates)
     * Matches iOS behavior: updates same bubble instead of creating new message
     */
    suspend fun updateLocationMessage(
        recipientPublicKey: String,
        locationContent: String,
        sessionId: String
    ): Result<Unit> {
        return try {
            // ONE instant for the local row and the wire. `V2EnvelopeTimestampWiringTest`
            // caught the first version of the V2 branch below sending without it: the
            // recipient would then date the update at the moment the send reached the
            // wire, so a live share would drift from the sender's own row with every hop
            // of latency. The legacy lane had the same hole and nobody could see it,
            // because the legacy lane was the only lane.
            val updatedAtMs = System.currentTimeMillis()

            // Find existing message by sessionId
            val existing = messageDao.findLocationBySessionId(myPublicKey, sessionId)
            if (existing != null) {
                // Update local message content
                messageDao.updateContent(existing.id, locationContent, updatedAtMs)
                Log.d(TAG, "📍 Updated local live location for session $sessionId")
            }

            // Send to recipient (they will deduplicate on their end)
            // __CONTROL_OVER_V2_2026_10_28__ LAZY, and that is load-bearing.
            // `encryptWithDoubleRatchet` ADVANCES THE LEGACY RATCHET, so computing it
            // eagerly would spend a step even when the V2 lane carries the share, leaving
            // a permanent gap in the receiver's skipped-key store. The mesh branches still
            // touch it and still get it; the relay branch touches it only if V2 declines.
            val encrypted by lazy { encryptWithDoubleRatchet(locationContent, recipientPublicKey) }
            val suppressPush = LocationMessage.isSilentPushContent(locationContent)

            val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == recipientPublicKey }
            val iosPeer = findIosPeerOrAwait(recipientPublicKey)

            when {
                meshPeer != null -> {
                    val meshMessage = MeshMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        type = MessageType.TEXT_MESSAGE,
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
                        timestamp = System.currentTimeMillis()
                    )
                    meshManager.sendMessageToPublicKey(recipientPublicKey, meshMessage)
                }
                iosPeer != null -> {
                    // __MESH_STABLE_ID_2026_09_27__ One id for both legs, so the iPhone
                    // applies the tick once; the relay leg covers a half-open LAN link.
                    val updateId = java.util.UUID.randomUUID().toString()
                    val meshPayload = crossPlatformMeshSecureMessage(
                        String(encrypted, Charsets.UTF_8), updateId, myPublicKey, recipientPublicKey,
                        System.currentTimeMillis()
                    ) ?: android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                    try {
                        crossPlatformMesh.sendMessage(recipientPublicKey, meshPayload, "TEXT_MESSAGE")
                    } catch (e: Exception) {
                        Log.w(TAG, "iOS mesh location update threw: ${e.message}")
                    }
                    try {
                        vpsClient.sendMessage(recipientPublicKey, encrypted, updateId, suppressPush = suppressPush)
                    } catch (_: Exception) { }
                }
                else -> {
                    // __CONTROL_OVER_V2_2026_10_28__ the LIVE-location update had the same
                    // legacy-only relay branch as the initial share, so a V2-only peer saw
                    // a share begin (once the share itself was fixed) and then freeze at
                    // the first position forever. One id for both lanes, as the mesh legs
                    // above already do.
                    val updateId = java.util.UUID.randomUUID().toString()
                    val lane = com.oshi.messenger.network.v2.ControlLane.preferV2(
                        v2 = {
                            v2MessageRouter.sendText(
                                recipientPublicKey,
                                locationContent.toByteArray(Charsets.UTF_8),
                                updateId,
                                originalTimestampMs = updatedAtMs,
                            )
                        },
                        legacy = {
                            vpsClient.sendMessage(
                                recipientPublicKey,
                                encrypted,
                                updateId,
                                suppressPush = suppressPush
                            )
                        },
                        onV2Error = { Log.w(TAG, "V2 location update threw, using legacy lane: ${it.message}") },
                    )
                    // `suppressPush` is the legacy lane's switch; on V2 the push is a
                    // separate call, so the same rule has to be applied by not making it.
                    if (lane == com.oshi.messenger.network.v2.ControlLane.Lane.V2 && !suppressPush) {
                        pushAfterV2Send(recipientPublicKey, updateId, locationContent)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Update location message failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ============================================================================
    // RECEIVE MESSAGES
    // ============================================================================

    private suspend fun handleMeshMessage(meshMessage: MeshMessage) {
        // Handle both TEXT_MESSAGE and MEDIA_MESSAGE
        if (meshMessage.type != MessageType.TEXT_MESSAGE && meshMessage.type != MessageType.MEDIA_MESSAGE) return

        // 🔧 FIX: Skip own messages
        if (meshMessage.senderPublicKey == myPublicKey) {
            Log.d(TAG, "⏭️ Skipping self-message from Mesh")
            return
        }

        // 🚫 Blocked sender — drop before any decryption/notification work.
        if (isBlockedSender(meshMessage.senderPublicKey)) {
            Log.d(TAG, "🚫 Dropping mesh message from blocked ${meshMessage.senderPublicKey.take(8)}...")
            return
        }

        try {
            // Decrypt with retry on DoubleRatchet session failure
            val encryptedData = android.util.Base64.decode(meshMessage.payload, android.util.Base64.NO_WRAP)
            val decrypted: String = try {
                decryptIOSMessage(encryptedData, meshMessage.senderPublicKey)
            } catch (primaryError: Exception) {
                Log.w(TAG, "⚠️ Mesh primary decryption failed: ${primaryError.message}")
                if (!resetRatchetUnlessReplay(primaryError, meshMessage.senderPublicKey)) throw primaryError
                Log.d(TAG, "🔄 Retrying mesh decrypt with fresh session...")
                decryptIOSMessage(encryptedData, meshMessage.senderPublicKey)
            }

            // 📬 Receipt messages (delivery/read)
            if (isReceiptMessage(decrypted)) {
                if (decrypted.startsWith(DELIVERY_RECEIPT_PREFIX)) {
                    handleDeliveryReceipt(decrypted, meshMessage.senderPublicKey)
                } else {
                    handleReadReceipt(decrypted, meshMessage.senderPublicKey)
                }
                return
            }

            // 📑 Group receipt, arriving over the mesh.
            if (consumeGroupReceipt(decrypted, meshMessage.senderPublicKey)) return

            // 🔗 Device link handshake — see handleVPSMessage for why this sits here.
            if (MultiDeviceSyncManager.isLinkPayload(decrypted)) {
                multiDeviceSyncManager.handleLinkRequest(decrypted, meshMessage.senderPublicKey)
                return
            }

            // 🔄 Multi-device sync. MUST sit above the media check.
            if (MultiDeviceSyncManager.isSyncPayload(decrypted)) {
                multiDeviceSyncManager.handleSyncData(decrypted, meshMessage.senderPublicKey)
                return
            }

            // Check if it's a media message.
            // `startsWith` is kept purely additively — see [OSHIControlPayload.isMediaEnvelope]
            // for why an iPhone's photo does not reliably put `type` first.
            if (meshMessage.type == MessageType.MEDIA_MESSAGE ||
                decrypted.startsWith("{\"type\":") ||
                OSHIControlPayload.isMediaEnvelope(decrypted)
            ) {
                handleMediaContent(meshMessage.id, meshMessage.senderPublicKey, decrypted, DeliveryMethod.MESH)
                return
            }

            // Check for special message types
            if (decrypted.startsWith("📸PROFILE_UPDATE📸")) {
                handleProfileUpdate(meshMessage.senderPublicKey, decrypted)
                return
            }
            if (decrypted.startsWith("📸PROFILE_REQUEST📸")) {
                sendProfileUpdate(meshMessage.senderPublicKey, answeringRequest = true)
                return
            }
            if (decrypted.startsWith("🎨WALLPAPER_UPDATE🎨")) {
                handleWallpaperUpdate(meshMessage.senderPublicKey, decrypted)
                return
            }
            // 🔗 Profile links visibility messages (iOS LINKS_VISIBILITY)
            if (profileLinksManager.isLinksVisibilityMessage(decrypted)) {
                profileLinksManager.processIncomingVisibility(meshMessage.senderPublicKey, decrypted)
                return
            }
            // 📞 The peer's copy of an ANSWERED call summary is a duplicate: both ends
            // observed the call and each writes its own, in its own direction. Keeping
            // it means two bubbles, one of them reversed.
            // ONLY answered calls — the `|` before the duration is the discriminant. A
            // missed / declined / unanswered call may have been seen by the caller
            // alone, so that message is the sole trace and must pass.
            // iOS MessageManager.swift:5810-5813.
            if (OSHIControlPayload.isAnsweredCallSummary(decrypted)) {
                Log.d(TAG, "\uD83D\uDCDE Answered-call summary from peer ignored \u2014 ours is authoritative")
                return
            }
            // 🛡️ Check-In / Accompagnement messages
            if (decrypted.startsWith("\uD83D\uDEE1\uFE0FCHECK_IN\uD83D\uDEE1\uFE0F")) {
                handleCheckInMessage(meshMessage.id, meshMessage.senderPublicKey, decrypted, DeliveryMethod.MESH)
                return
            }
            // 📍 Location messages
            if (decrypted.startsWith("📍LOCATION📍")) {
                handleLocationMessage(meshMessage.id, meshMessage.senderPublicKey, decrypted, DeliveryMethod.MESH)
                return
            }
            // 🔒 Offline-only. The ANSWER returns true and stops here — an ack
            // is not a second line in the thread. The REQUEST falls through to
            // be stored and rendered like any other sentinel the catalog marks
            // `.rendered`: seeing when this conversation left the relay, and
            // who asked for it, is the point of rendering it at all.
            if (com.oshi.messenger.data.model.OfflineOnlyRequest.isRequest(decrypted) &&
                handleOfflineOnly(decrypted, meshMessage.senderPublicKey)) {
                return
            }
            // 💓 __ALIVE_CHECK_IN_2026_11_04__ Apply the peer's liveness event, then
            // fall through: it is `.rendered`, so it also becomes a line in the
            // thread. The handler never consumes the message — a confirmation the
            // reader cannot find afterwards is one they have to take on trust from
            // a coloured dot.
            if (com.oshi.messenger.data.model.AliveCheckInEvent.isEvent(decrypted)) {
                handleAliveCheckIn(decrypted, meshMessage.senderPublicKey)
            }
            // 📊 Poll ballots. Silent in the catalog: no bubble, no banner — in
            // a group of twenty, twenty votes would be twenty notifications. The
            // VOTER is the sender of the decrypted envelope, never a field in
            // the JSON, which would let one member vote in another's name.
            if (com.oshi.messenger.data.model.PollVotePayload.isVoteMessage(decrypted)) {
                recordPollBallot(decrypted, meshMessage.senderPublicKey)
                return
            }
            // ❤️ Reaction messages (both Android 👍REACTION👍 and iOS 🔥REACTION🔥 formats)
            if (reactionManager.isReactionMessage(decrypted)) {
                reactionManager.handleIncomingReaction(decrypted, meshMessage.senderPublicKey)
                return
            }
            // ✍️ Typing indicators (Android ✍️TYPING✍️ and iOS ⌨️TYPING⌨️)
            if (typingIndicatorManager.isTypingMessage(decrypted)) {
                typingIndicatorManager.handleIncomingTypingIndicator(decrypted, meshMessage.senderPublicKey)
                return
            }
            // ✏️ Edit messages (Android ✏️EDIT✏️)
            if (messageActionsManager.isEdit(decrypted)) {
                messageActionsManager.handleIncomingEdit(decrypted, meshMessage.senderPublicKey)
                return
            }
            // 🗑️ Delete messages (Android 🗑️DELETE🗑️)
            if (messageActionsManager.isDelete(decrypted)) {
                messageActionsManager.handleIncomingDelete(decrypted, meshMessage.senderPublicKey)
                return
            }
            // 🔧 iOS unified action messages (🔧ACTION🔧 — edit/delete/pin)
            if (messageActionsManager.isActionMessage(decrypted)) {
                messageActionsManager.handleIncomingAction(decrypted, meshMessage.senderPublicKey)
                return
            }
            // 🔑 Key rotation messages
            if (decrypted.startsWith("🔑KEY_ROTATION🔑")) {
                keyRotationManager.handleKeyRotationNotification(decrypted, meshMessage.senderPublicKey)
                return
            }
            // 👥 Group messages
            if (decrypted.startsWith(GROUP_MESSAGE_PREFIX)) {
                groupManager.handleIncomingGroupMessage(decrypted, meshMessage.senderPublicKey)
                return
            }
            // 📢 Group update messages
            if (decrypted.startsWith(GROUP_UPDATE_PREFIX)) {
                groupManager.handleGroupUpdate(decrypted, meshMessage.senderPublicKey)
                return
            }

            // 🕵️ Steganography extraction: check if decrypted text contains hidden data
            // 🕵️ No steganographic extraction here — matching iOS.
            //
            // iOS never emits stego on this path (`steganographyEnabled` has zero
            // consumers in any send path — audit §4), so `containsHiddenData` could
            // only ever FALSE-POSITIVE: it fired on ordinary Cyrillic/Greek/Armenian
            // text and on any pasted NBSP, then burned a failing AES-GCM decrypt and
            // logged a misleading "detected hidden data but extraction failed".
            // Inbound covert-relay fragments do not arrive as chat text either — on
            // iOS they are polled and reassembled by `CovertChannelReceiver.swift:326-365`,
            // which Android does not have yet (audit P0 #7, separate epic).
            val meshDisplayContent = decrypted

            // Create regular text message
            val message = Message(
                id = meshMessage.id,
                // 🔑 Canonical spelling on the copy we STORE — see [canonicalIdentity].
                // The wire key is left untouched everywhere it addresses a transport
                // or keys a ratchet session; only the stored row is stamped.
                senderPublicKey = canonicalIdentity(meshMessage.senderPublicKey),
                recipientPublicKey = myPublicKey,
                content = meshDisplayContent,
                timestamp = normalizeTimestamp(meshMessage.timestamp),
                isFromMe = false,
                deliveryMethod = DeliveryMethod.MESH
            )

            // Check for duplicate
            if (messageDao.getMessageById(message.id) == null) {
                messageDao.insert(sanitizeMediaForInsert(message))
                noteBlockedByPeer(meshMessage.senderPublicKey, meshDisplayContent)
                notifyWebSession(message)
                ensureContactExists(meshMessage.senderPublicKey)
                Log.d(TAG, "📥 Received message via Mesh from ${meshMessage.senderPublicKey.take(8)}...")

                // 📬 Send delivery receipt back to sender — never for a 🚫BLOCKED🚫
                // notice (__BLOCKED_BY_PEER_2026_09_24__, same gate as the V2 path).
                if (!isBlockedNotice(meshDisplayContent)) sendDeliveryReceipt(message.id, meshMessage.senderPublicKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process mesh message: ${e.message}")
        }
    }

    /**
     * Handle media content (photo, video, audio, document)
     */
    private suspend fun handleMediaContent(
        messageId: String,
        senderPublicKey: String,
        decrypted: String,
        deliveryMethod: DeliveryMethod
    ) {
        try {
            val json = org.json.JSONObject(decrypted)
            // Accept THREE field-name conventions:
            //   • Android internal mesh:    `{"type":"IMAGE", "data":..., "caption":...}`
            //   • iOS cross-platform mesh:  `{"mediaType":"image", "data":..., "fileName":...}`
            //   • iOS VPS / IPFS wrapper:   `{"type":"media", "mediaType":"image", "content":..., "size":..., "isViewOnce":..., "fileName":...}`
            // Without this, an iPhone image sent via VPS reached the Android
            // notification path but `MediaType.valueOf("MEDIA")` threw and the
            // image silently never reached the conversation list.
            val rawType = json.optString("type", "")
            val rawMediaType = json.optString("mediaType", "")
            // If `type=="media"`, that's the iOS VPS wrapper — the real media
            // type is in `mediaType`. Otherwise treat `type` as the media type.
            val typeStr = when {
                rawType.equals("media", ignoreCase = true) -> rawMediaType
                rawType.isNotEmpty() -> rawType
                else -> rawMediaType
            }.ifEmpty { return }
            val caption = json.optString("caption", "").ifEmpty { json.optString("text", "") }
            // iOS VPS uses `content`, Android internal + iOS mesh use `data`.
            val dataBase64 = json.optString("data", "").ifEmpty { json.optString("content", "") }
                .ifEmpty { return }

            val mediaData = android.util.Base64.decode(dataBase64, android.util.Base64.NO_WRAP)

            // 🔴 AN UNKNOWN LABEL USED TO DELETE THE ATTACHMENT.
            //
            // This was `MediaType.valueOf(typeStr.uppercase())` in a try/catch whose
            // catch did `return` — so a media envelope that had already arrived,
            // decrypted and been base64-decoded was thrown away in full, message and
            // bytes, because of one unrecognised WORD. Not a rendering fallback: the
            // row was never inserted, so the user saw nothing at all and no retry
            // could ever recover it.
            //
            // It fires the moment the other side learns a case this one has not:
            // iOS's incoming `.gif` sends `mediaType:"gif"`, and `valueOf("GIF")`
            // throws. iOS itself now sniffs the bytes instead of trusting the label
            // (`GIF87a`/`GIF89a`), which is the third leg here.
            //
            // Order is label → bytes → DOCUMENT, and the DOCUMENT leg is deliberately
            // conditional. The entry gate above is loose — `startsWith("{\"type\":")`
            // claims ANY JSON whose first key is `type` — so an unrecognised CONTROL
            // envelope that happens to carry a `content` string reaches here too.
            // Turning that into a document bubble full of protocol would be a new bug.
            // So the last leg applies only when the envelope says it is media (iOS's
            // `type:"media"` wrapper, or any envelope carrying a `mediaType` key);
            // anything else is dropped exactly as before.
            val labelled = MediaType.fromWireLabel(typeStr)
            val declaresMedia = rawType.equals("media", ignoreCase = true) || rawMediaType.isNotEmpty()
            val mediaType = labelled
                ?: MediaType.sniff(mediaData)
                ?: (if (declaresMedia) MediaType.DOCUMENT else null)
                ?: run {
                    Log.w(TAG, "Unknown media type '$typeStr' and unrecognised bytes — not a media envelope")
                    return
                }
            if (labelled == null) {
                Log.w(TAG, "⚠️ Unknown media type '$typeStr' — resolved to ${mediaType.name}, keeping the attachment")
            }

            // Ephemeral (view-once) media: iOS sends `isViewOnce` in the payload
            // (MediaManager.swift). The receive path dropped the flag, so an iPhone's
            // view-once image PERSISTED on Android instead of disappearing after one
            // view. Carry it onto the Message so the existing view-once UI applies.
            val isViewOnce = json.optBoolean("isViewOnce", false)

            // 📄 M-6, receive half over the LEGACY transport (2026-08-25). The V2 blob
            // path already stores `inbound.filename` as a document's displayed name
            // ([dispatchV2InboundFile]); this path read no `fileName` key at all, and
            // iOS's legacy encoder sends the document's real name in exactly that key
            // and NO caption (`MessageManager.swift:5294-5296`, `:5288` leaves the
            // text empty for media). So an iPhone document over the pre-V2 transport
            // landed with `content = ""`, drew an empty document row, and "Download"
            // wrote a file called "".
            //
            // 🔒 PEER-CONTROLLED. This string reaches `java.io.File(oshiDir, name)` in
            // `ChatScreen.saveDocumentToDownloads` on API < 29. `DocumentMessageContent`
            // sanitizes at render, and it is sanitized again here so a traversal is
            // never even STORED. `sanitize` returns null when nothing usable survives,
            // which falls back to the empty caption rather than inventing a name.
            val displayText = inboundMediaDisplayText(
                mediaType = mediaType,
                caption = caption,
                wireFileName = json.optString("fileName", "").takeIf { it.isNotEmpty() },
                contactBytes = if (mediaType == MediaType.CONTACT) mediaData else null
            )

            val message = Message(
                id = messageId,
                senderPublicKey = canonicalIdentity(senderPublicKey),   // 🔑 stored spelling
                recipientPublicKey = myPublicKey,
                content = displayText,
                timestamp = System.currentTimeMillis(),
                isFromMe = false,
                mediaType = mediaType,
                mediaData = mediaData,
                isViewOnce = isViewOnce,
                deliveryMethod = deliveryMethod,
                // __VIDEO_NOTE_2026_09_24__ spec §2.C/§2.E: the same two optional keys
                // at the top level of every legacy/inline/mesh media envelope. Rule 1:
                // only a VIDEO can be a note; a malformed value is just "no note".
                isVideoNote = mediaType == MediaType.VIDEO &&
                    com.oshi.messenger.service.media.VideoNote.flagFrom(json) == true,
                mediaDurationMs = if (mediaType == MediaType.VIDEO &&
                    com.oshi.messenger.service.media.VideoNote.flagFrom(json) == true
                ) com.oshi.messenger.service.media.VideoNote.durationFrom(json)?.toLong() else null
            )

            if (messageDao.getMessageById(messageId) == null) {
                messageDao.insert(sanitizeMediaForInsert(message))
                // An Android sender's inline V2 media also repeats the caption as text.
                if (needsCaptionFollowUp(mediaType, caption)) expectCaptionFollowUp(messageId)
                noteBlockedByPeer(senderPublicKey, null)
                ensureContactExists(senderPublicKey)
                Log.d(TAG, "📥 Received ${mediaType.name} via ${deliveryMethod.name} from ${senderPublicKey.take(8)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse media content: ${e.message}")
        }
    }

    /**
     * __RESEND_LIST_ANDROID_2026_10_12__ Re-emit the messages a peer declares it could not
     * open (iOS `MessageManager.v2ResendRequested`, MessageManager+V2.swift:2477).
     *
     * 🔴 THE OWNERSHIP GUARD IS A SECURITY GUARD, NOT AN OPTIMISATION. The ids are chosen by
     * the peer: without it, someone who GUESSES or REPLAYS an id gets a message meant for
     * somebody else re-sent to them. Normalisation is [canonicalIdentity], the same funnel
     * every stored row goes through.
     *
     * WHY THE ORIGINAL ID TRAVELS INSIDE THE CIPHERTEXT, AND NOT AS THE ENVELOPE ID
     * The relay id is cleartext and kept 30 days; reusing it would let the relay pair the
     * two envelopes, hence date the outage and count the messages caught in it. A fresh
     * envelope id goes on the wire, and the original rides inside [RatchetHealSignal.Resent]
     * so the recipient can dedupe against a bubble it may already have.
     *
     * WHY NO NOTIFICATION, AND NO WRITE TO OUR OWN ROW
     * `router.sendText` alone fires no push (the V2 wake is a separate call, made only by
     * [pushAfterV2Send]) — the recipient was notified at the time, and 32 catch-up banners
     * would be the next bug. Nor does a resend touch the stored row: making a week-old
     * bubble blink "sending" would be a lie about what just happened.
     */
    private suspend fun serveResendRequest(peer: String, msgIds: List<String>) {
        // __RESEND_AMPLIFICATION__ The REQUEST is what the peer controls, so the throttle sits
        // on the request itself, before any work: a peer could otherwise attach 32 ids to each
        // of 200 ordinary messages and make us run 6400 lookups. The hourly budget below
        // bounds the SENDS, not the work. A genuinely broken peer re-asks — its ids stay in
        // ITS ledger until they are served.
        val canonicalPeer = canonicalIdentity(peer)
        val prefs = context.getSharedPreferences("v2_resend", Context.MODE_PRIVATE)
        val servedKey = "served_" + canonicalPeer
        val now = System.currentTimeMillis()
        val last = prefs.getLong(servedKey, 0L).takeIf { it > 0 }
        if (!com.oshi.messenger.network.v2.RatchetHealSignal.Policy.maySend(last, now)) {
            Log.i(TAG, "DIAG_V2_RESEND to=${canonicalPeer.take(12)} result=ignore why=cooldown")
            return
        }
        prefs.edit().putLong(servedKey, now).apply()

        val me = canonicalIdentity(myPublicKey)
        if (me.isEmpty()) return
        val budget = v2MessageRouter.resendBudget

        for (id in msgIds.take(com.oshi.messenger.network.v2.RatchetHealSignal.MAX_RESEND_PER_REQUEST)) {
            val stored = try { messageDao.getMessageById(id) } catch (e: Exception) { null }
            val age = stored?.let { now - it.timestamp } ?: 0L
            val decision = com.oshi.messenger.network.v2.V2ResendPolicy.decide(
                found = stored != null,
                isFromMe = stored?.isFromMe == true,
                senderMatchesMe = stored != null && canonicalIdentity(stored.senderPublicKey) == me,
                recipientMatchesPeer = stored != null && canonicalIdentity(stored.recipientPublicKey) == canonicalPeer,
                isDeletedForEveryone = stored?.isDeletedForEveryone == true || stored?.isDeleted == true,
                hasMedia = stored?.mediaType != null,
                messageAgeMs = age,
                attemptsSoFar = budget.attempts(id),
                peerBudgetLeft = budget.peerBudgetLeft(canonicalPeer, now),
            )
            if (decision != com.oshi.messenger.network.v2.V2ResendDecision.SEND || stored == null) {
                Log.i(TAG, "DIAG_V2_RESEND id=${id.take(8)} to=${canonicalPeer.take(12)} result=skip why=$decision")
                continue
            }
            // __RESEND_FIDELITY__ What the sender DISPLAYS today, not what they typed: an edit
            // writes `editedContent` beside `content` rather than over it, so re-emitting the
            // latter would permanently resend the pre-edit text — and the edit action itself
            // went out on the broken session and was lost with everything else.
            val body = stored.editedContent ?: stored.content
            val wrapped = com.oshi.messenger.network.v2.RatchetHealSignal.Resent.encode(id, body)
            val ok = try {
                v2MessageRouter.sendText(
                    peerUserKey = peer,
                    plaintext = wrapped.toByteArray(Charsets.UTF_8),
                    msgId = java.util.UUID.randomUUID().toString(),
                    originalTimestampMs = stored.timestamp,
                )
            } catch (e: Exception) {
                Log.w(TAG, "DIAG_V2_RESEND id=${id.take(8)} to=${canonicalPeer.take(12)} result=error err=${e.message}")
                false
            }
            if (ok) {
                budget.noteResent(id)
                budget.noteSpent(canonicalPeer, now)
                Log.i(TAG, "DIAG_V2_RESEND id=${id.take(8)} to=${canonicalPeer.take(12)} result=sent")
            } else {
                Log.w(TAG, "DIAG_V2_RESEND id=${id.take(8)} to=${canonicalPeer.take(12)} result=not_sent")
            }
        }
    }

    private suspend fun handleVPSMessage(vpsMessage: VPSMessage) {
        try {
            // 🔧 FIX: Skip own messages echoed back from VPS
            if (vpsMessage.senderPublicKey == myPublicKey) {
                Log.d(TAG, "⏭️ Skipping self-message from VPS (own message echoed back)")
                return
            }

            // 🚫 Blocked sender — drop before decryption, storage or notification.
            if (isBlockedSender(vpsMessage.senderPublicKey)) {
                Log.d(TAG, "🚫 Dropping VPS message from blocked ${vpsMessage.senderPublicKey.take(8)}...")
                return
            }

            // Skip messages already in DB or that already failed decryption
            if (messageDao.getMessageById(vpsMessage.id) != null) return
            if (failedMessageIds.contains(vpsMessage.id)) return

            // iOS sends encryptedContent as base64(JSON of EncryptedMessage)
            // EncryptedMessage = { ciphertext: base64(sealedBox), signature, senderPublicKey, timestamp }
            // The ciphertext contains: HKDF(X25519) AES-GCM encrypted ratchet data
            val decrypted: String = try {
                decryptIOSMessage(vpsMessage.encryptedContent, vpsMessage.senderPublicKey)
            } catch (primaryError: Exception) {
                Log.w(TAG, "⚠️ VPS primary decryption failed: ${primaryError.message}")
                // Retry with fresh DoubleRatchet session
                try {
                    if (!resetRatchetUnlessReplay(primaryError, vpsMessage.senderPublicKey)) throw primaryError
                    Log.d(TAG, "🔄 Retrying VPS decrypt with fresh session...")
                    decryptIOSMessage(vpsMessage.encryptedContent, vpsMessage.senderPublicKey)
                } catch (retryError: Exception) {
                    Log.e(TAG, "❌ VPS retry also failed: ${retryError.message}")
                    throw retryError
                }
            }

            // 📬 Delivery receipt (sender's badge update)
            if (decrypted.startsWith(DELIVERY_RECEIPT_PREFIX)) {
                handleDeliveryReceipt(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // 📖 Read receipt (sender's badge turns blue)
            if (decrypted.startsWith(READ_RECEIPT_PREFIX)) {
                handleReadReceipt(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // 📑 __GROUP_RECEIPTS_2026_11_02__ (N8) Someone telling us what they
            // did with a GROUP message WE sent. Silent: it is state about our
            // own message, never a message in its own right.
            if (consumeGroupReceipt(decrypted, vpsMessage.senderPublicKey)) return

            // 📸 Check for profile update (compatible with iOS format)
            if (decrypted.startsWith("📸PROFILE_UPDATE📸")) {
                handleProfileUpdate(vpsMessage.senderPublicKey, decrypted)
                return
            }

            // 📸 Check for profile request
            if (decrypted.startsWith("📸PROFILE_REQUEST📸")) {
                Log.d(TAG, "📸 Received profile request from ${vpsMessage.senderPublicKey.take(8)}...")
                sendProfileUpdate(vpsMessage.senderPublicKey, answeringRequest = true)
                return
            }

            // 🎨 Check for wallpaper update (compatible with iOS format)
            if (decrypted.startsWith("🎨WALLPAPER_UPDATE🎨")) {
                handleWallpaperUpdate(vpsMessage.senderPublicKey, decrypted)
                return
            }

            // 🔗 Profile links visibility messages (iOS LINKS_VISIBILITY)
            if (profileLinksManager.isLinksVisibilityMessage(decrypted)) {
                profileLinksManager.processIncomingVisibility(vpsMessage.senderPublicKey, decrypted)
                return
            }

            // 📞 The peer's copy of an ANSWERED call summary is a duplicate: both ends
            // observed the call and each writes its own, in its own direction. Keeping
            // it means two bubbles, one of them reversed.
            // ONLY answered calls — the `|` before the duration is the discriminant. A
            // missed / declined / unanswered call may have been seen by the caller
            // alone, so that message is the sole trace and must pass.
            // iOS MessageManager.swift:5810-5813.
            if (OSHIControlPayload.isAnsweredCallSummary(decrypted)) {
                Log.d(TAG, "\uD83D\uDCDE Answered-call summary from peer ignored \u2014 ours is authoritative")
                return
            }
            // 🛡️ Check-In / Accompagnement messages
            if (decrypted.startsWith("\uD83D\uDEE1\uFE0FCHECK_IN\uD83D\uDEE1\uFE0F")) {
                handleCheckInMessage(vpsMessage.id, vpsMessage.senderPublicKey, decrypted, DeliveryMethod.VPS)
                return
            }

            // 📍 Check for location message
            if (decrypted.startsWith("📍LOCATION📍")) {
                handleLocationMessage(vpsMessage.id, vpsMessage.senderPublicKey, decrypted, DeliveryMethod.VPS)
                return
            }

            // 🔗 Device link handshake (Android-only lane; iOS syncs over /api/sync/*).
            // handleLinkRequest re-checks the prefix and verifies the pending code itself.
            if (MultiDeviceSyncManager.isLinkPayload(decrypted)) {
                multiDeviceSyncManager.handleLinkRequest(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // 🔄 Multi-device sync. MUST sit above the media check: the envelope is bare
            // JSON whose first key is `type`, so handleMediaContent would claim it.
            // handleSyncData drops anything from an UNLINKED sender, so this branch cannot
            // be used by a stranger to write into our stores.
            if (MultiDeviceSyncManager.isSyncPayload(decrypted)) {
                multiDeviceSyncManager.handleSyncData(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // 📷 Check for media message (JSON envelope, ANY key order —
            // see [OSHIControlPayload.isMediaEnvelope]; this is the path an
            // iPhone photo takes when the peer has no v2 bundle).
            if (decrypted.startsWith("{\"type\":") || OSHIControlPayload.isMediaEnvelope(decrypted)) {
                handleMediaContent(vpsMessage.id, vpsMessage.senderPublicKey, decrypted, DeliveryMethod.VPS)
                return
            }

            // 🔒 Offline-only — see the mesh lane above for why only the answer
            // stops here. Worth noting on THIS lane in particular: a request
            // that asks a conversation off the relay may perfectly well arrive
            // over the relay, sent before the other side knew the new rule.
            if (com.oshi.messenger.data.model.OfflineOnlyRequest.isRequest(decrypted) &&
                handleOfflineOnly(decrypted, vpsMessage.senderPublicKey)) {
                return
            }
            // 💓 __ALIVE_CHECK_IN_2026_11_04__ Apply the peer's liveness event, then
            // fall through: it is `.rendered`, so it also becomes a line in the
            // thread. The handler never consumes the message — a confirmation the
            // reader cannot find afterwards is one they have to take on trust from
            // a coloured dot.
            if (com.oshi.messenger.data.model.AliveCheckInEvent.isEvent(decrypted)) {
                handleAliveCheckIn(decrypted, vpsMessage.senderPublicKey)
            }
            // 📊 Poll ballots. Silent in the catalog: no bubble, no banner — in
            // a group of twenty, twenty votes would be twenty notifications. The
            // VOTER is the sender of the decrypted envelope, never a field in
            // the JSON, which would let one member vote in another's name.
            if (com.oshi.messenger.data.model.PollVotePayload.isVoteMessage(decrypted)) {
                recordPollBallot(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // ❤️ Reaction messages (both Android 👍REACTION👍 and iOS 🔥REACTION🔥 formats)
            if (reactionManager.isReactionMessage(decrypted)) {
                reactionManager.handleIncomingReaction(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // ✍️ Typing indicators (Android ✍️TYPING✍️ and iOS ⌨️TYPING⌨️)
            if (typingIndicatorManager.isTypingMessage(decrypted)) {
                typingIndicatorManager.handleIncomingTypingIndicator(decrypted, vpsMessage.senderPublicKey)
                return
            }
            // ✏️ Edit messages (Android ✏️EDIT✏️)
            if (messageActionsManager.isEdit(decrypted)) {
                messageActionsManager.handleIncomingEdit(decrypted, vpsMessage.senderPublicKey)
                return
            }
            // 🗑️ Delete messages (Android 🗑️DELETE🗑️)
            if (messageActionsManager.isDelete(decrypted)) {
                messageActionsManager.handleIncomingDelete(decrypted, vpsMessage.senderPublicKey)
                return
            }
            // 🔧 iOS unified action messages (🔧ACTION🔧 — edit/delete/pin)
            if (messageActionsManager.isActionMessage(decrypted)) {
                messageActionsManager.handleIncomingAction(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // 🔑 Key rotation messages
            if (decrypted.startsWith("🔑KEY_ROTATION🔑")) {
                keyRotationManager.handleKeyRotationNotification(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // 👥 Group messages
            if (decrypted.startsWith(GROUP_MESSAGE_PREFIX)) {
                groupManager.handleIncomingGroupMessage(decrypted, vpsMessage.senderPublicKey)
                return
            }
            // 📢 Group update messages
            if (decrypted.startsWith(GROUP_UPDATE_PREFIX)) {
                groupManager.handleGroupUpdate(decrypted, vpsMessage.senderPublicKey)
                return
            }

            // 🔧 FIX: Skip messages that failed to decrypt (still look encrypted)
            if (isLikelyEncrypted(decrypted)) {
                if (LOG_SENSITIVE) Log.w(TAG, "⚠️ Message appears to still be encrypted, skipping display: ${decrypted.take(30)}...")
                return
            }

            // 🕵️ Steganography extraction: check if decrypted text contains hidden data
            // 🕵️ No steganographic extraction here — matching iOS.
            //
            // iOS never emits stego on this path (`steganographyEnabled` has zero
            // consumers in any send path — audit §4), so `containsHiddenData` could
            // only ever FALSE-POSITIVE: it fired on ordinary Cyrillic/Greek/Armenian
            // text and on any pasted NBSP, then burned a failing AES-GCM decrypt and
            // logged a misleading "detected hidden data but extraction failed".
            // Inbound covert-relay fragments do not arrive as chat text either — on
            // iOS they are polled and reassembled by `CovertChannelReceiver.swift:326-365`,
            // which Android does not have yet (audit P0 #7, separate epic).
            // __RESTRICTED_NETWORK_HINT_2026_11_18__ never shown, on any lane.
            val rnHint = com.oshi.messenger.util.RestrictedNetworkHint.strip(decrypted)
            if (rnHint.flagged) com.oshi.messenger.util.RestrictedNetworkHintStore.add(context, vpsMessage.id)
            val displayContent = rnHint.text

            // Create regular text message
            val message = Message(
                id = vpsMessage.id,
                // 🔑 The VPS/legacy lane is exactly where a base64url spelling comes
                // from (`VPSClient.publicKeyToBase64url`), so this is the stamp that
                // matters most — see [canonicalIdentity].
                senderPublicKey = canonicalIdentity(vpsMessage.senderPublicKey),
                recipientPublicKey = myPublicKey,
                content = displayContent,
                timestamp = normalizeTimestamp(vpsMessage.timestamp),
                isFromMe = false,
                deliveryMethod = DeliveryMethod.VPS
            )

            // Check for duplicate
            if (messageDao.getMessageById(message.id) == null) {
                messageDao.insert(sanitizeMediaForInsert(message))
                noteBlockedByPeer(vpsMessage.senderPublicKey, displayContent)
                notifyWebSession(message)
                Log.d(TAG, "📥 Received message via VPS from ${vpsMessage.senderPublicKey.take(8)}...")

                // Auto-create contact if new
                ensureContactExists(vpsMessage.senderPublicKey)

                // 📸 Request their profile if we don't have it
                requestProfileIfNeeded(vpsMessage.senderPublicKey)

                // 📬 Send delivery receipt back to sender + local notification — neither
                // for a 🚫BLOCKED🚫 notice (__BLOCKED_BY_PEER_2026_09_24__, V2 gate).
                if (!isBlockedNotice(displayContent)) {
                    sendDeliveryReceipt(message.id, vpsMessage.senderPublicKey)
                    // Show local notification (use extracted content, not raw steganographic text)
                    showMessageNotification(vpsMessage.senderPublicKey, displayContent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process VPS message: ${e.message}")
            failedMessageIds.add(vpsMessage.id)
        }
    }

    /**
     * OSHI V2 inbound entry point. The V2 router has already run X3DH + Double Ratchet
     * and handed us the plaintext, so — unlike [handleVPSMessage], which must first
     * decrypt the iOS EncryptedMessage envelope — we go straight to the SAME prefix
     * handler chain. Every envelope type (receipts, profile, wallpaper, media,
     * reactions, typing, edits, key-rotation, groups, plain text) is routed exactly
     * as the VPS path routes it, so V2 and legacy stay byte-identical to iOS.
     *
     * Self-messages and duplicates are filtered exactly as the VPS path does.
     */
    private suspend fun dispatchV2Inbound(
        decrypted: String,
        senderPublicKey: String,
        messageId: String,
        tsMs: Long,
        /**
         * The transport this envelope ACTUALLY arrived on, stamped on whatever
         * row the chain ends up writing.
         *
         * Added for LoRa: the prefix chain below is the "same path every other
         * inbound mesh message takes", and duplicating its ~20 branches for a
         * second caller is how two receive paths drift apart (Android already
         * has four near-copies of it). Defaulted to VPS so every existing call
         * site is byte-identical.
         */
        method: DeliveryMethod = DeliveryMethod.VPS
    ) {
        try {
            if (senderPublicKey == myPublicKey) return          // own message echoed back
            if (isBlockedSender(senderPublicKey)) {             // 🚫 blocked contact
                Log.d(TAG, "🚫 Dropping V2 message from blocked ${senderPublicKey.take(8)}...")
                noticeBlockedSender(senderPublicKey, decrypted)
                return
            }
            if (messageDao.getMessageById(messageId) != null) return
            if (failedMessageIds.contains(messageId)) return
            // __V2_CAPTION_2026_09_27__ The caption repeat for iPhones; ours is already
            // under the photo it belongs to.
            if (isCaptionFollowUp(messageId)) return

            // 📬 Delivery receipt
            if (decrypted.startsWith(DELIVERY_RECEIPT_PREFIX)) {
                handleDeliveryReceipt(decrypted, senderPublicKey); return
            }
            // 📖 Read receipt
            if (decrypted.startsWith(READ_RECEIPT_PREFIX)) {
                handleReadReceipt(decrypted, senderPublicKey); return
            }
            // 📑 Group receipt, arriving over V2.
            if (consumeGroupReceipt(decrypted, senderPublicKey)) return
            // 📸 Profile update
            if (decrypted.startsWith("📸PROFILE_UPDATE📸")) {
                handleProfileUpdate(senderPublicKey, decrypted); return
            }
            // 📸 Profile request
            if (decrypted.startsWith("📸PROFILE_REQUEST📸")) {
                sendProfileUpdate(senderPublicKey, answeringRequest = true); return
            }
            // 🎨 Wallpaper update
            if (decrypted.startsWith("🎨WALLPAPER_UPDATE🎨")) {
                handleWallpaperUpdate(senderPublicKey, decrypted); return
            }
            // 🔗 Profile links visibility
            if (profileLinksManager.isLinksVisibilityMessage(decrypted)) {
                profileLinksManager.processIncomingVisibility(senderPublicKey, decrypted); return
            }
            // 📞 The peer's copy of an ANSWERED call summary is a duplicate: both ends
            // observed the call and each writes its own, in its own direction. Keeping
            // it means two bubbles, one of them reversed.
            // ONLY answered calls — the `|` before the duration is the discriminant. A
            // missed / declined / unanswered call may have been seen by the caller
            // alone, so that message is the sole trace and must pass.
            // iOS MessageManager.swift:5810-5813.
            if (OSHIControlPayload.isAnsweredCallSummary(decrypted)) {
                Log.d(TAG, "\uD83D\uDCDE Answered-call summary from peer ignored \u2014 ours is authoritative")
                return
            }
            // 🛡️ Check-In / Accompagnement
            if (decrypted.startsWith("🛡️CHECK_IN🛡️")) {
                handleCheckInMessage(messageId, senderPublicKey, decrypted, method); return
            }
            // 📍 Location
            if (decrypted.startsWith("📍LOCATION📍")) {
                handleLocationMessage(messageId, senderPublicKey, decrypted, method); return
            }
            // 🔗 Device link handshake — see handleVPSMessage for why this sits here.
            if (MultiDeviceSyncManager.isLinkPayload(decrypted)) {
                multiDeviceSyncManager.handleLinkRequest(decrypted, senderPublicKey); return
            }
            // 🔄 Multi-device sync. MUST sit above the media check.
            if (MultiDeviceSyncManager.isSyncPayload(decrypted)) {
                multiDeviceSyncManager.handleSyncData(decrypted, senderPublicKey); return
            }
            // 📷 Media (inline JSON envelope, ANY key order — [OSHIControlPayload.isMediaEnvelope]).
            // On V2 this is iOS's `v2ParseEmbeddedMediaJSON` safety net in reverse
            // (MessageManager+V2.swift:841,1086-1096).
            if (decrypted.startsWith("{\"type\":") || OSHIControlPayload.isMediaEnvelope(decrypted)) {
                handleMediaContent(messageId, senderPublicKey, decrypted, method); return
            }
            // ❤️ Reactions
            if (reactionManager.isReactionMessage(decrypted)) {
                reactionManager.handleIncomingReaction(decrypted, senderPublicKey); return
            }
            // ✍️ Typing indicators
            if (typingIndicatorManager.isTypingMessage(decrypted)) {
                typingIndicatorManager.handleIncomingTypingIndicator(decrypted, senderPublicKey); return
            }
            // ✏️ Edit
            if (messageActionsManager.isEdit(decrypted)) {
                messageActionsManager.handleIncomingEdit(decrypted, senderPublicKey); return
            }
            // 🗑️ Delete
            if (messageActionsManager.isDelete(decrypted)) {
                messageActionsManager.handleIncomingDelete(decrypted, senderPublicKey); return
            }
            // 🔧 Unified action (edit/delete/pin)
            if (messageActionsManager.isActionMessage(decrypted)) {
                messageActionsManager.handleIncomingAction(decrypted, senderPublicKey); return
            }
            // 🔑 Key rotation
            if (decrypted.startsWith("🔑KEY_ROTATION🔑")) {
                keyRotationManager.handleKeyRotationNotification(decrypted, senderPublicKey); return
            }
            // 👥 Group message
            if (decrypted.startsWith(GROUP_MESSAGE_PREFIX)) {
                groupManager.handleIncomingGroupMessage(decrypted, senderPublicKey); return
            }
            // 📢 Group update
            if (decrypted.startsWith(GROUP_UPDATE_PREFIX)) {
                groupManager.handleGroupUpdate(decrypted, senderPublicKey); return
            }

            // Still-encrypted guard
            if (isLikelyEncrypted(decrypted)) {
                if (LOG_SENSITIVE) Log.w(TAG, "⚠️ V2 message appears still encrypted, skipping: ${decrypted.take(30)}...")
                return
            }

            // 🕵️ Steganography extraction
            // 🕵️ No steganographic extraction here — matching iOS.
            //
            // iOS never emits stego on this path (`steganographyEnabled` has zero
            // consumers in any send path — audit §4), so `containsHiddenData` could
            // only ever FALSE-POSITIVE: it fired on ordinary Cyrillic/Greek/Armenian
            // text and on any pasted NBSP, then burned a failing AES-GCM decrypt and
            // logged a misleading "detected hidden data but extraction failed".
            // Inbound covert-relay fragments do not arrive as chat text either — on
            // iOS they are polled and reassembled by `CovertChannelReceiver.swift:326-365`,
            // which Android does not have yet (audit P0 #7, separate epic).
            val displayContent = decrypted

            val message = Message(
                id = messageId,
                senderPublicKey = canonicalIdentity(senderPublicKey),   // 🔑 stored spelling
                recipientPublicKey = myPublicKey,
                content = displayContent,
                // Use the sender's envelope timestamp (like the VPS path), not local
                // receive time, so a message polled late doesn't reorder the thread and
                // stays consistent with what iOS stamped.
                timestamp = normalizeTimestamp(tsMs),
                isFromMe = false,
                deliveryMethod = method
            )
            if (messageDao.getMessageById(message.id) == null) {
                messageDao.insert(sanitizeMediaForInsert(message))
                noteBlockedByPeer(senderPublicKey, displayContent)   // __BLOCKED_BY_PEER_2026_09_24__
                notifyWebSession(message)
                Log.d(TAG, "📥 Received message via ${method.name} from ${senderPublicKey.take(8)}...")
                ensureContactExists(senderPublicKey)
                requestProfileIfNeeded(senderPublicKey)
                // __BLOCKED_NOTICE_SWITCH_2026_09_23__ A 🚫BLOCKED🚫 notice is a terminal
                // refusal: no receipt back to the blocker, no banner (the row in the
                // thread is enough — iOS/desktop rule 3).
                if (OSHIControlPayload.matchedPrefix(displayContent) != OSHIControlPayload.BLOCKED_NOTICE) {
                    sendDeliveryReceipt(message.id, senderPublicKey)
                    showMessageNotification(senderPublicKey, displayContent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process V2 message: ${e.message}")
            failedMessageIds.add(messageId)
        }
    }

    // =========================================================================
    // 📡 LoRa (Meshtastic) BRIDGE
    //
    // The radio core parses, reassembles and filters; it deliberately stops at
    // the point where cryptography starts, because it cannot decrypt. Everything
    // below is the half that only the message layer can do: decrypt, PROVE the
    // sender, bind the node, and file the result in the same conversation every
    // other transport files into.
    //
    // iOS reference: `MeshtasticManager.swift:1054-1056,:1080+` post
    // `MeshMessageReceived` into the ordinary receive pipeline;
    // `MessageManager.swift:5545-5572` is where the binding happens, and why it
    // happens THERE and nowhere earlier.
    // =========================================================================

    /**
     * Bumped exactly where the Double Ratchet itself produced plaintext.
     *
     * This is the Android form of iOS's `lastRatchetVerifiedMessageId`
     * (`MessageManager.swift:5551-5566`), and it exists for one reason: a
     * decrypt function returning non-null is NOT proof of identity if any of its
     * branches can pass an envelope through uncrypted. Binding a node to an
     * identity on anything less than cryptographic proof hands anyone with a
     * radio the ability to file their plaintext under a contact's name — public
     * keys are shared openly by QR.
     *
     * Read under `decryptionMutex`, which already serialises every receive path,
     * so a snapshot taken around one decrypt cannot be advanced by another.
     */
    @Volatile
    private var ratchetProofCounter: Long = 0L

    /** Message ids already promoted from `loraAcked`, so the flow can re-emit freely. */
    private val loraAckPromoted = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Wire the radio into this pipeline. Called once from [initialize].
     *
     * The manager is constructed unconditionally rather than only when a radio is
     * already paired: a node paired for the FIRST time in this session would
     * otherwise have no collector until the next launch, and "the first message I
     * ever sent over my new node never arrived" is the worst possible first
     * impression. Construction is cheap — `start()` registers one Bluetooth
     * state receiver and reads the adapter (`MeshtasticManager.kt:354-365`); it
     * does not scan, and `reconnectIfPaired` returns immediately with no pairing.
     */
    private fun startLoRaBridge() {
        val radio = try {
            MeshtasticManager.getInstance(context)
        } catch (e: Exception) {
            Log.w(TAG, "📡 LoRa radio unavailable, bridge not started: ${e.message}")
            return
        }

        // 📡 RECIPIENT FILTERING. Until this is set, `handleOshiChunk` has no key
        // to compare against and publishes EVERY broadcast envelope it hears to
        // this pipeline — every OSHI phone on the mesh, not just the ones
        // addressed to us (`MeshtasticManager.kt:1115-1118`). A lambda rather
        // than a value so it stays correct across a key rotation or an identity
        // restored after this ran.
        // __LORA_HELD_DURABLE_2026_11_18__ What the radio holds is now durable and reported back:
        // skip what was delivered some other way, mark sent what its flush put on the air, and mark
        // FAILED (visibly) what it can never carry, instead of losing it in silence.
        radio.heldListener = object : MeshtasticManager.HeldListener {
            override suspend fun stillWanted(messageId: String): Boolean =
                runCatching { stillUndelivered(messageId) }.getOrDefault(true)

            override suspend fun onHeldSent(messageId: String) {
                runCatching {
                    messageDao.getMessageById(messageId)?.let { row ->
                        LoRaDeliveryLedger.afterSendAttempt(row, framesWritten = true)?.let { updateMessage(it) }
                    }
                }.onFailure { Log.w(TAG, "📡 held send bookkeeping failed: ${it.message}") }
            }

            override suspend fun onHeldUndeliverable(messageId: String) {
                if (runCatching { stillUndelivered(messageId) }.getOrDefault(false)) markSendFailed(messageId)
            }
        }

        radio.myPublicKeyProvider = {
            myPublicKey.ifEmpty {
                runCatching { cryptoManager.getPublicKeyString() }.getOrNull().orEmpty()
            }.ifEmpty { null }
        }

        // OSHI↔OSHI envelopes. Serialized under the SAME `decryptionMutex` as
        // the VPS / mesh / V2 receive paths: DoubleRatchet is not thread-safe,
        // and a LoRa envelope arriving mid-poll would otherwise corrupt a session.
        scope.launch {
            radio.receivedMessages.collect { received ->
                try {
                    decryptionMutex.withLock { handleLoRaEnvelope(received) }
                } catch (e: Exception) {
                    Log.e(TAG, "📡 LoRa envelope handling failed: ${e.message}")
                }
            }
        }

        // Plain text from any Meshtastic client, OSHI or not.
        scope.launch {
            radio.receivedText.collect { text ->
                try {
                    decryptionMutex.withLock { handleLoRaInteropText(text) }
                } catch (e: Exception) {
                    Log.e(TAG, "📡 LoRa interop text handling failed: ${e.message}")
                }
            }
        }

        // Mesh-confirmed deliveries.
        scope.launch {
            radio.loraAcked.collect { acked -> promoteLoRaAcked(acked) }
        }

        // __LORA_BACKGROUND_NOTIFICATION_2026_11_03__ Hold the process while the
        // radio is connected, and let go the moment it is not.
        //
        // The collectors above live in a process-scoped coroutine and the GATT
        // link lives in the MeshtasticManager singleton; neither survives the
        // process being dozed. With no foreground service running, Android is
        // free to freeze OSHI minutes after it leaves the screen, so a message
        // arriving at the node arrived nowhere until the app was reopened.
        //
        // Tied to READY rather than to "a radio is paired": a permanent
        // notification for a radio that is not connected is a claim the user
        // can see is false and cannot dismiss.
        scope.launch {
            radio.state.collect { st ->
                if (st == MeshtasticManager.ConnState.READY) {
                    LoRaLinkService.start(context)
                } else {
                    LoRaLinkService.stop(context)
                }
            }
        }

        // A paired MeshCore radio reconnects on its own once its manager exists. Its envelopes reach this
        // pipeline through `radio.receivedMessages` (MeshtasticManager.deliverForeignEnvelope).
        runCatching { MeshCoreManager.getInstance(context) }
            .onFailure { Log.w(TAG, "📡 MeshCore radio unavailable: ${it.message}") }

        Log.d(TAG, "📡 LoRa bridge attached (paired=${MeshtasticManager.isRadioPaired(context)}, meshCore=${MeshCoreManager.isPaired(context)})")
    }

    /**
     * An OSHI envelope that came off our own radio.
     *
     * Mirrors [handleCrossPlatformMessage]'s shape — the wire payload IS an iOS
     * `EncryptedMessage`, the same object that path unwraps — and then hands the
     * plaintext to the one prefix chain every transport shares, stamped LORA.
     */
    private suspend fun handleLoRaEnvelope(received: LoRaReceivedMessage) {
        val wire = received.message
        // `senderPublicKey` is a CLAIM at this point. It is used to DERIVE the
        // decryption key, which is exactly why a successful decrypt proves it:
        // the ciphertext only opens under DH(claimed_public, our_private), which
        // an impostor cannot produce without the claimed identity's secret half.
        val claimedSender = wire.senderPublicKey.ifEmpty { wire.encryptedContent.senderPublicKey }
        if (claimedSender.isEmpty()) {
            Log.w(TAG, "📡 LoRa envelope ${wire.id.take(8)} carries no sender key — dropped")
            return
        }
        if (claimedSender == myPublicKey) return                       // own broadcast, heard back
        if (isBlockedSender(claimedSender)) {
            Log.d(TAG, "🚫 Dropping LoRa message from blocked ${claimedSender.take(8)}...")
            return
        }
        if (com.oshi.messenger.service.StaleIdentityStore.get(context).isStale(claimedSender)) {
            Log.w(TAG, "🪦 Stale identity sender — dropping LoRa message: ${claimedSender.take(12)}…")
            return
        }
        // Cross-transport dedup by message id: the store-and-forward flush is a
        // deliberate SECOND send of the same id, and the relay may land the same
        // message too.
        if (messageDao.getMessageById(wire.id) != null) {
            Log.d(TAG, "📡 LoRa message ${wire.id.take(8)} already stored — dedup")
            return
        }

        val envelopeJson = wire.encryptedContent.toJsonObject().toString()
            .toByteArray(Charsets.UTF_8)

        val proofBefore = ratchetProofCounter
        val decrypted: String = try {
            decryptIOSMessage(envelopeJson, claimedSender)
        } catch (primaryError: Exception) {
            Log.w(TAG, "⚠️ LoRa primary decryption failed: ${primaryError.message}")
            try {
                if (!resetRatchetUnlessReplay(primaryError, claimedSender)) return
                decryptIOSMessage(envelopeJson, claimedSender)
            } catch (retryError: Exception) {
                // Not for us, or not from who it says. Either way the binding must
                // NOT happen — this is the branch an attacker's forged envelope
                // lands in.
                Log.w(TAG, "📡 LoRa envelope ${wire.id.take(8)} did not decrypt: ${retryError.message}")
                return
            }
        }

        // 📡 THE BINDING — and it is here, after the ratchet, for the reason the
        // radio layer spells out at `MeshtasticManager.kt:1122-1127` and iOS at
        // `MeshtasticManager.swift:1098-1104`: at the radio the sender key is
        // only a CLAIM. This is what later routes a PLAIN Meshtastic text packet
        // from the same node — which carries no identity at all — into this
        // contact's conversation instead of a stray `lora!<nodehex>` thread.
        if (!received.bindsNode) {
            // Came off a MeshCore radio: its id is not a Meshtastic node number (MeshCoreManager.kt).
            Log.d(TAG, "📡 LoRa ${wire.id.take(8)} via MeshCore — no node binding")
        } else if (ratchetProofCounter != proofBefore) {
            val outcome = LoRaIdentityBridge.bind(received.nodeNum, claimedSender)
            Log.d(
                TAG,
                "📡 LoRa node ${received.nodeNum.toString(16)} → ${claimedSender.take(8)}…: $outcome"
            )
        } else {
            // Decrypted, but not by the ratchet. Faithful to iOS, which refuses
            // to bind on anything weaker (`MessageManager.swift:5551-5566`).
            Log.d(TAG, "📡 LoRa ${wire.id.take(8)} decrypted without ratchet proof — not binding")
        }

        dispatchV2Inbound(
            decrypted = decrypted,
            senderPublicKey = claimedSender,
            messageId = wire.id,
            tsMs = wire.timestamp,
            method = DeliveryMethod.LORA,
        )
    }

    /**
     * Plain text from ANY Meshtastic client — the official app included.
     *
     * It carries NO identity, so the conversation it lands in was decided by
     * `LoRaNodeIdentityMap` inside the radio layer and arrives here already
     * resolved as `conversationKey`: a bound OSHI key when the node has proved
     * one, `lora!<nodehex>` when it has not. iOS builds the same row at
     * `MeshtasticManager.swift:1036-1056`.
     *
     * The id is generated locally because a raw Meshtastic packet has none —
     * there is nothing to dedup against, and two identical texts really are two
     * messages.
     */
    private suspend fun handleLoRaInteropText(inbound: LoRaInteropText) {
        if (inbound.text.isEmpty()) return
        if (isBlockedSender(inbound.conversationKey)) {
            Log.d(TAG, "🚫 Dropping LoRa text from blocked ${inbound.conversationKey.take(12)}")
            return
        }
        // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ A broadcast belongs to the CHANNEL,
        // not to a private thread with whoever happened to speak.
        //
        // Everyone in radio range on the same channel hears everyone else, so three
        // people talking on LongFast used to produce three separate one-to-one
        // threads that each looked like a direct message, and the channel itself
        // appeared nowhere. Addressing the row to the channel puts it in one room
        // while `senderPublicKey` keeps the real author, so every speaker stays
        // individually identifiable. See `LoRaChannelThread`.
        //
        // A DIRECT packet is still a private thread: it was addressed to this node
        // alone and nobody else heard it.
        val channelKey = if (inbound.isBroadcast) {
            LoRaChannelThread.keyFor(inbound.channelIndex, inbound.channelName)
        } else null

        val message = Message(
            id = UUID.randomUUID().toString(),
            // 🔑 A bound OSHI key gets the stored spelling; the unbound
            // `lora!<nodehex>` pseudo-key is not base64 and comes back unchanged,
            // which is the reason [canonicalIdentity] carries a decode check.
            senderPublicKey = canonicalIdentity(inbound.conversationKey),
            recipientPublicKey = channelKey ?: myPublicKey,
            content = inbound.text,
            timestamp = System.currentTimeMillis(),
            isFromMe = false,
            // iOS stamps `.delivered`: it is off our own radio, so there is
            // nothing further to wait for. (`MeshtasticManager.swift:1046`.)
            isDelivered = true,
            // __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ This arrived
            // as a raw Meshtastic packet: plaintext, on a channel whose key is
            // public, with a forgeable `from`. LORA means an OSHI envelope the
            // ratchet authenticated; this is the other lane and must not be
            // drawn as though it were the first.
            deliveryMethod = DeliveryMethod.LORA_PLAINTEXT,
        )
        messageDao.insert(sanitizeMediaForInsert(message))
        noteBlockedByPeer(inbound.conversationKey, inbound.text)
        // The speaker is still a contact in their own right: they may be messaged
        // directly, and the channel room needs a name to put on each bubble.
        ensureContactExists(inbound.conversationKey)
        if (channelKey != null) ensureChannelContactExists(channelKey)
        notifyWebSession(message)
        // Notify under the ROOM, not under the speaker: a banner saying the
        // sender's name for a message twenty other radios also received invites a
        // reply the user believes is private.
        showMessageNotification(channelKey ?: inbound.conversationKey, inbound.text)
        Log.d(
            TAG,
            "📥 LoRa text from ${inbound.displayName}" +
                (if (inbound.isKnownIdentity) " (known node)" else "") +
                (if (channelKey != null) " on ${LoRaChannelThread.displayName(channelKey)}" else " (direct)")
        )
    }

    /**
     * Message ids the MESH has confirmed. `MeshtasticManager.kt:1052-1062`.
     *
     * Only ever unicast and only the LAST chunk of a message is tracked — the
     * mesh acks per packet, and Meshtastic never acks a broadcast at all, so an
     * absent ack means nothing and is never treated as a failure here.
     *
     * The promotion that matters is the FAILED one: a message the radio could
     * only queue was reported not-sent and marked failed, and the
     * store-and-forward flush then delivered it. Without this the bubble would
     * stay red for a message that arrived.
     */
    private suspend fun promoteLoRaAcked(acked: Set<String>) {
        for (id in acked) {
            if (!loraAckPromoted.add(id)) continue
            try {
                val row = messageDao.getMessageById(id) ?: continue
                val promoted = LoRaDeliveryLedger.afterMeshAck(row) ?: continue
                updateMessage(promoted)
                Log.d(TAG, "📡 LoRa ACK → delivered: ${id.take(8)}")
            } catch (e: Exception) {
                Log.w(TAG, "📡 Could not promote LoRa ack for ${id.take(8)}: ${e.message}")
            }
        }
    }

    /**
     * Is there a PHYSICAL network path off this device?
     *
     * Android port of iOS's `NetworkReachabilityManager.hasPhysicalPath`
     * (`MessageManager.swift:4132-4139`). VPN transports are excluded on purpose:
     * a tunnel interface keeps looking "connected" after the link underneath it
     * is gone, so a plain `activeNetwork != null` check would report a VPN user
     * as online while off-grid — and the off-grid shortcut, the only route to the
     * radio, could then never fire for them.
     */
    private fun hasPhysicalNetworkPath(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        if (cm == null) {
            true    // cannot tell → assume online, i.e. change nothing
        } else {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Network path probe failed (${e.message}) — assuming online")
        true
    }

    /**
     * Hand an envelope to the paired radio and do the delivery bookkeeping.
     * Port of `MessageManager.deliverViaLoRa` (`MessageManager.swift:4108-4127`).
     *
     * Returns false — leaving the caller's normal transport chain to run — when
     * there is no radio, when the message carries media (LoRa is text-sized
     * only), or when the radio could only QUEUE it. That last case is the
     * important one: `queueOrSendSecureMessage` returns true ONLY when frames
     * actually reached a connected radio, and reporting a queued message as sent
     * turns a lost message into a delivered one on screen
     * (`MeshtasticManager.kt:755-782`).
     */
    private suspend fun deliverViaLoRa(
        messageId: String,
        recipientPublicKey: String,
        encryptedEnvelope: ByteArray,
        timestampMs: Long,
    ): Boolean {
        val radio = MeshtasticManager.instanceOrNull ?: return false
        val wire = buildLoRaWireMessage(
            messageId, recipientPublicKey, encryptedEnvelope, timestampMs
        ) ?: return false

        // The media guard, the region guard and the size guard all live inside
        // this call, so every LoRa refusal is RECORDED in one place and reaches
        // the user through `lastRefusal` on the LoRa settings screen instead of
        // being a silent `false` here.
        //
        // Every LoRa radio this phone holds: a Meshtastic/OSHI Mesh radio and/or a MeshCore radio. The same
        // OMP frames go out on each, so the message reaches whichever network the recipient's radio is on.
        // Mirrors MessageManager.swift deliverViaLoRa.
        val meshCore = MeshCoreManager.instanceOrNull
        val viaMeshCore = meshCore?.isReady == true && meshCore.sendSecureMessage(wire)
        val viaMeshtastic = (MeshtasticManager.isRadioPaired(context) || !viaMeshCore) &&
            radio.queueOrSendSecureMessage(wire)
        val framesWritten = viaMeshtastic || viaMeshCore

        // The bookkeeping is NOT inlined here: `afterSendAttempt` returns null for
        // a merely-queued message, which is the single rule that keeps a queued
        // message from being shown as sent, and it is unit-tested.
        messageDao.getMessageById(messageId)?.let { row ->
            LoRaDeliveryLedger.afterSendAttempt(row, framesWritten)
                ?.let { updateMessage(it) }
        }
        return framesWritten
    }

    /**
     * __NEVER_DROP_HELD_2026_11_18__ For the "internet sending off" exit: keep the envelope in the
     * radio's durable held queue unless it already is there, else mark the row failed (red "Not
     * delivered · tap to retry") so the person knows it did not leave. Returns true when held.
     */
    private suspend fun holdForRadioOrFail(
        messageId: String,
        recipientPublicKey: String,
        encryptedEnvelope: ByteArray,
        timestampMs: Long,
    ): Boolean {
        val radio = MeshtasticManager.instanceOrNull
        val held = radio != null && (radio.isHeld(messageId) || run {
            val wire = buildLoRaWireMessage(messageId, recipientPublicKey, encryptedEnvelope, timestampMs)
            wire != null && radio.holdForRadio(wire)
        })
        if (!held) markSendFailed(messageId)
        return held
    }

    /** True while the row still needs to leave this phone: present, not delivered, not read. */
    private suspend fun stillUndelivered(messageId: String): Boolean {
        val row = messageDao.getMessageById(messageId) ?: return false
        return !row.isDelivered && !row.isRead
    }

    /**
     * Ask the radio to carry media it cannot carry, so the refusal is RECORDED.
     *
     * Only when off-grid: the refusal machinery is already scoped to people who
     * own a node (`MeshtasticManager.refuse` returns early with no pairing,
     * `:1140-1150`), but there is no reason to log "LoRa can't take a photo" at
     * someone whose photo is about to sail out over Wi-Fi.
     */
    private fun recordLoRaMediaRefusalIfOffGrid(
        messageId: String,
        recipientPublicKey: String,
        mediaBytes: ByteArray,
    ) {
        val radio = MeshtasticManager.instanceOrNull ?: return
        if (hasPhysicalNetworkPath()) return
        runCatching {
            radio.queueOrSendSecureMessage(
                LoRaWireMessage(
                    id = messageId,
                    senderAddress = myPublicKey,
                    recipientAddress = recipientPublicKey,
                    encryptedContent = LoRaEncryptedContent("", "", myPublicKey, 0L),
                    timestamp = System.currentTimeMillis(),
                    isRead = false,
                    senderPublicKey = myPublicKey,
                    recipientPublicKey = recipientPublicKey,
                    // The whole point: this is what `carriesMedia` reads.
                    mediaAttachment = mediaBytes,
                )
            )
        }.onFailure { Log.w(TAG, "📡 LoRa media refusal probe failed: ${it.message}") }
    }

    /**
     * The iOS `SecureMessage` that goes inside the "OM" frames.
     *
     * [encryptedEnvelope] is exactly what [encryptWithDoubleRatchet] returns —
     * the iOS `EncryptedMessage` JSON `{ciphertext, signature, senderPublicKey,
     * timestamp}` — which is the same object `LoRaEncryptedContent` models, so
     * this is a re-parse and not a re-encryption.
     */
    private fun buildLoRaWireMessage(
        messageId: String,
        recipientPublicKey: String,
        encryptedEnvelope: ByteArray,
        timestampMs: Long,
    ): LoRaWireMessage? = try {
        val enc = LoRaEncryptedContent.fromJsonObject(
            JSONObject(String(encryptedEnvelope, Charsets.UTF_8))
        )
        if (enc == null) null else LoRaWireMessage(
            id = messageId,
            senderAddress = myPublicKey,
            recipientAddress = recipientPublicKey,
            encryptedContent = enc,
            timestamp = timestampMs,
            isRead = false,
            deliveryStatus = LoRaWireMessage.STATUS_SENT,
            senderPublicKey = myPublicKey,
            // The field the RECIPIENT filter compares against. Empty here means
            // every OSHI phone on the mesh keeps the ciphertext it cannot open.
            recipientPublicKey = recipientPublicKey,
        )
    } catch (e: Exception) {
        Log.w(TAG, "📡 Could not build LoRa envelope for ${messageId.take(8)}: ${e.message}")
        null
    }

    /**
     * Inbound V2 GROUP text. iOS fans a group message out as ONE ratchet envelope per
     * member with `type:"group"` + `groupId`, and the ratchet plaintext is bare
     * **base64(JSON GroupMessage)** — NO `👥GROUP_MESSAGE👥` prefix
     * (V2MessageRouter.swift:574-603, delivered MessageManager+V2.swift:819-830).
     *
     * Android's router used to drop `groupId`, so these landed in the 1:1 thread with
     * the sender as an unreadable base64 blob. Decode, re-attach the prefix Android's
     * group funnel expects, and hand it to the SAME `handleIncomingGroupMessage` the
     * legacy path uses — which dedups on the inner GroupMessage.id, gates stale
     * identities, auto-adds unknown senders and posts the group notification.
     */
    private fun dispatchV2GroupText(base64GroupMessage: String, senderPublicKey: String, envelopeGroupId: String? = null) {
        try {
            if (senderPublicKey == myPublicKey) return
            val json = String(
                android.util.Base64.decode(base64GroupMessage.trim(), android.util.Base64.DEFAULT),
                Charsets.UTF_8
            )
            if (!json.trimStart().startsWith("{")) {
                Log.w(TAG, "⚠️ V2 group payload from ${senderPublicKey.take(8)} is not JSON — dropped")
                return
            }
            // __GROUP_E2E_V2_2026_09_23__ GROUP_E2E_V2_SPEC §3: the author is the ratchet's sender,
            // never a JSON claim, and the payload must belong to the envelope's group.
            if (!GroupManager.v2ContentAuthentic(org.json.JSONObject(json), senderPublicKey, envelopeGroupId)) {
                Log.w(TAG, "🛑 V2 group payload from ${senderPublicKey.take(8)} claims another author/group — dropped")
                return
            }
            groupManager.handleIncomingGroupMessage(GROUP_MESSAGE_PREFIX + json, senderPublicKey)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ V2 group payload decode failed from ${senderPublicKey.take(8)}: ${e.message}")
        }
    }

    /**
     * Inbound V2 GROUP MEDIA. The blob bytes are already downloaded + opened; the
     * sender's `GroupMessage` rides inside the ratchet payload with its inline media
     * STRIPPED (`groupMessage` field, V2MessageRouter.swift:60-71). Re-attach the
     * bytes through the fields `GroupManager` already understands — top-level
     * `mediaType` (iOS raw value) + `mediaData` (base64) — and hand it to the same
     * funnel the group text path uses, exactly like iOS `v2DeliverGroupFile`
     * (MessageManager+V2.swift:839-853).
     *
     * A group file envelope with no embedded GroupMessage cannot be placed in any
     * conversation, so it is logged and dropped rather than mis-filed into the 1:1
     * thread with the sender (same rule as iOS :840-843).
     */
    private fun dispatchV2GroupFile(inbound: com.oshi.messenger.network.v2.V2Inbound.File) {
        val b64 = inbound.groupMessageB64
        if (b64 == null) {
            Log.w(TAG, "⚠️ V2 group file from ${inbound.from.take(8)} carried no GroupMessage — dropped")
            return
        }
        try {
            val json = org.json.JSONObject(
                String(android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)
            )
            // __GROUP_E2E_V2_2026_09_23__ GROUP_E2E_V2_SPEC §3 (same rule as the text path).
            if (!GroupManager.v2ContentAuthentic(json, inbound.from, inbound.groupId)) {
                Log.w(TAG, "🛑 V2 group file from ${inbound.from.take(8)} claims another author/group — dropped")
                return
            }
            json.put("mediaType", inbound.mediaType ?: iosMediaTypeForMime(inbound.mime))
            // __VIDEO_NOTE_2026_09_24__ spec §1 rule 5: a note if EITHER the file key or
            // the embedded GroupMessage says so; duration from the file key first.
            val (noteFlag, noteDuration) = com.oshi.messenger.service.media.VideoNote.merge(
                fileKeyFlag = inbound.videoNote, fileKeyDurationMs = inbound.durationMs,
                innerFlag = com.oshi.messenger.service.media.VideoNote.flagFrom(json),
                innerDurationMs = com.oshi.messenger.service.media.VideoNote.durationFrom(json),
            )
            if (noteFlag) {
                json.put(com.oshi.messenger.service.media.VideoNote.KEY_FLAG, true)
                noteDuration?.let { json.put(com.oshi.messenger.service.media.VideoNote.KEY_DURATION, it) }
            }
            // GROUP media is the one path that still needs the whole file in RAM:
            // `GroupManager` has no file store of its own (audit M-3) and takes the
            // bytes base64'd inside the `GroupMessage`. The router caps this case at
            // `maxMemory()/4` before it ever gets here.
            json.put("mediaData", android.util.Base64.encodeToString(inbound.readBytes(), android.util.Base64.NO_WRAP))
            groupManager.handleIncomingGroupMessage(GROUP_MESSAGE_PREFIX + json.toString(), inbound.from)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ V2 group file payload decode failed from ${inbound.from.take(8)}: ${e.message}")
        }
    }

    /**
     * Inbound 1:1 V2 MEDIA delivered over the blob store. This is the path an iPhone
     * photo/video/voice-note/document actually takes: iOS uploads AEAD-sealed chunks
     * to `/v2/blobs` and sends `{"kind":"v2file",…}` as the ratchet plaintext
     * (V2MessageRouter.swift:745-769). Android had no parser for it, so every one of
     * those arrived as a text bubble containing the raw JSON — file key included.
     *
     * Mirrors iOS `v2DeliverFile` (MessageManager+V2.swift:856-881): the media type is
     * the transported `mediaType` raw value when present, otherwise inferred from the
     * mime; `isViewOnce` survives the transport; the caption does not (iOS's
     * `sendFile` carries no caption field either, so this is parity, not a loss).
     */
    // =========================================================================
    // __V2_CAPTION_2026_09_27__ (D1) media captions over V2
    // =========================================================================

    /**
     * After a photo/video left over V2, repeat its caption as a plain text under
     * [captionFollowUpId]. A shipped iPhone has nowhere else to read it from and shows
     * it as the next bubble; an Android receiver already has it on the media (the
     * `caption` key) and drops this copy ([isCaptionFollowUp]). Best-effort: a failure
     * here costs the caption on an iPhone, never the media.
     */
    private suspend fun sendCaptionFollowUp(
        recipientPublicKey: String,
        mediaMessageId: String,
        mediaType: MediaType,
        peerCaption: String?,
    ) {
        if (!needsCaptionFollowUp(mediaType, peerCaption)) return
        try {
            v2MessageRouter.sendText(
                recipientPublicKey,
                peerCaption!!.toByteArray(Charsets.UTF_8),
                captionFollowUpId(mediaMessageId),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Caption follow-up for ${mediaMessageId.take(8)} not sent: ${e.message}")
        }
    }

    private val captionFollowUpPrefs by lazy {
        context.getSharedPreferences("oshi_caption_followups", Context.MODE_PRIVATE)
    }

    /**
     * A media message arrived WITH its caption: the sender's follow-up text for it is a
     * duplicate. Remember its id (it may still be in flight) and hide it if it already
     * landed first.
     */
    private suspend fun expectCaptionFollowUp(mediaMessageId: String) {
        val id = captionFollowUpId(mediaMessageId)
        synchronized(captionFollowUpPrefs) {
            val set = (captionFollowUpPrefs.getStringSet("ids", emptySet()) ?: emptySet()).toMutableList()
            if (!set.contains(id)) set.add(id)
            // Bounded: the follow-up normally lands within seconds of its media.
            val bounded = if (set.size > 300) set.takeLast(300) else set
            captionFollowUpPrefs.edit().putStringSet("ids", bounded.toSet()).apply()
        }
        if (messageDao.getMessageById(id) != null) messageDao.softDelete(id)
    }

    /** Is [messageId] a caption follow-up whose media already carried the caption? */
    private fun isCaptionFollowUp(messageId: String): Boolean =
        captionFollowUpPrefs.getStringSet("ids", emptySet())?.contains(messageId) == true

    private suspend fun dispatchV2InboundFile(inbound: com.oshi.messenger.network.v2.V2Inbound.File) {
        try {
            if (inbound.from == myPublicKey) return
            if (isBlockedSender(inbound.from)) {                // 🚫 blocked contact
                Log.d(TAG, "🚫 Dropping V2 file from blocked ${inbound.from.take(8)}...")
                // An attachment is always something a person chose to send, so
                // there is no control payload to screen out here.
                noticeBlockedSender(inbound.from, plaintext = null)
                return
            }

            // 🖼️ PROGRESSIVE REVEAL — collapse the ladder onto ONE row.
            //
            // An armed iOS reveal sends the SAME photo N times, blurriest first, each
            // envelope under its own msgId. Every one of them used to insert a new row,
            // so the thread showed 5 photo bubbles (48px, 96, 192, 384, full) instead of
            // one photo that sharpens. Layer 0 creates the bubble; layer k>0 must
            // REWRITE it (iOS `v2ApplyRevealLayer`, MessageManager+V2.swift:863-892).
            val plan = inbound.reveal
            val layer = revealLayerFor(plan, inbound.msgId)
            if (layer != null) {
                val carrier = messageDao.getMessageById(layer.first)
                if (carrier != null &&
                    carrier.mediaType != null &&
                    carrier.senderPublicKey == canonicalIdentity(inbound.from)
                ) {
                    applyRevealLayer(carrier, inbound, layer.second)
                    return
                }
                if (plan != null) {
                    // The sender NAMED this row, so there is no false positive to guard
                    // against: layer 0 was never delivered (installed later, or lost) and
                    // there is nothing to rewrite. iOS drops it for the same reason
                    // (MessageManager+V2.swift:876-881) — an orphan bubble would show a
                    // blur that never sharpens.
                    Log.w(
                        TAG,
                        "🖼️ reveal layer ${plan.layerIndex}/${plan.finalIndex} for " +
                            "${plan.revealId.take(8)} has no carrier row — dropped"
                    )
                    return
                }
                // Inferred from the msgId, not read off the wire (see [revealLayerOf]):
                // dropping would risk losing a real message to a false positive. Fall
                // through and store it as its own bubble — the pre-fix behaviour, and
                // never worse than it.
                Log.w(
                    TAG,
                    "🖼️ reveal layer ${layer.second} for ${layer.first.take(8)} has no carrier row " +
                        "— stored as a standalone message"
                )
            }

            if (messageDao.getMessageById(inbound.msgId) != null) return

            val mediaType = mediaTypeFromV2(inbound.mediaType, inbound.mime) ?: run {
                Log.w(TAG, "⚠️ V2 file with unmappable type (${inbound.mediaType}/${inbound.mime}) — dropped")
                return
            }
            val message = Message(
                id = inbound.msgId,
                senderPublicKey = canonicalIdentity(inbound.from),   // 🔑 stored spelling
                recipientPublicKey = myPublicKey,
                // 📄 A received DOCUMENT keeps its name. `inbound.filename` was read off
                // the wire and then thrown away, so an iPhone's `Contrat-2026.pdf`
                // rendered as a document row with an EMPTY name and "Download" wrote a
                // file called "". `Message` has no `mediaFileName` column, but
                // `ChatScreen.DocumentMessageContent` already reads the name out of
                // `content` (`filename.removePrefix("[DOCUMENT]")`), which is the app's
                // own convention — so the name goes there rather than behind a schema
                // bump. Other media types keep the empty caption: iOS's `sendFile`
                // carries no caption field either.
                // __V2_CAPTION_2026_09_27__ …and a photo/video from an Android sender
                // now carries its caption (an iPhone's never does: iOS has no field).
                content = if (mediaType == MediaType.DOCUMENT) inbound.filename
                    else inbound.caption?.takeIf { it.isNotBlank() } ?: "",
                timestamp = normalizeTimestamp(inbound.ts),
                isFromMe = false,
                mediaType = mediaType,
                // Attached by [attachInboundPayload] below — the plaintext is on disk
                // and only inline-sized media is ever read into memory.
                mediaData = null,
                isViewOnce = inbound.isViewOnce,
                deliveryMethod = DeliveryMethod.VPS,
                // __VIDEO_NOTE_2026_09_24__ docs/VIDEO_NOTE_SPEC.md §2.A — already
                // validated (flag AND video) by the router.
                isVideoNote = inbound.videoNote && mediaType == MediaType.VIDEO,
                mediaDurationMs = if (inbound.videoNote) inbound.durationMs?.toLong() else null
            )
            val attached = attachInboundPayload(message, inbound)
            // A shared contact names the person on the conversation row.
            val stored = if (mediaType == MediaType.CONTACT && attached.content.isEmpty()) {
                attached.copy(content = contactRowText(attached.mediaData) ?: "")
            } else attached
            messageDao.insert(stored)
            if (needsCaptionFollowUp(mediaType, inbound.caption)) expectCaptionFollowUp(inbound.msgId)
            noteBlockedByPeer(inbound.from, null)   // __BLOCKED_BY_PEER_2026_09_24__
            // File-backed media reaches the web client through `onRequestMedia`, which
            // resolves the file store — pushing it inline here would put the whole
            // video back on the heap, which is the thing this path exists to avoid.
            notifyWebSession(stored)
            ensureContactExists(inbound.from)
            requestProfileIfNeeded(inbound.from)
            sendDeliveryReceipt(message.id, inbound.from)
            showMessageNotification(inbound.from, "[${mediaType.name}]")
            Log.d(TAG, "📥 Received ${mediaType.name} via V2 blob (${inbound.size}B) from ${inbound.from.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process V2 file: ${e.message}")
            failedMessageIds.add(inbound.msgId)
        }
    }

    /**
     * Replace the carrier row's bytes with a sharper layer — UPDATE, never INSERT.
     *
     * All three steps or none, exactly as iOS (`MessageManager+V2.swift:883-889`):
     * rewrite the attachment, evict the superseded blob, and let the UI re-read.
     * Skipping the eviction leaves the previous layer in `MediaAttachmentStore`'s LRU
     * and the bubble keeps showing the blur it already had — the row would be right
     * and the screen wrong.
     *
     * The timestamp is NOT touched: a reveal that sharpens hours later must not jump
     * to the bottom of the thread. No new notification and no second delivery
     * receipt: the message was already announced by layer 0.
     */
    private suspend fun applyRevealLayer(
        carrier: Message,
        inbound: com.oshi.messenger.network.v2.V2Inbound.File,
        layerIndex: Int,
    ) {
        mediaStore.delete(carrier.id)          // superseded blob: cache entry AND file
        val updated = attachInboundPayload(carrier.copy(mediaData = null), inbound)
        updateMessage(updated)
        notifyWebSession(updated)
        Log.d(
            TAG,
            "🖼️ reveal layer $layerIndex applied to ${carrier.id.take(8)} " +
                "(${inbound.size}B) — row UPDATED, no new bubble"
        )
    }

    /**
     * `mediaType` on the wire is the raw value of iOS `MediaManager.MediaType`
     * (MediaManager.swift:37-43: image/video/audio/document/contact). When it is
     * absent iOS falls back to the mime (`v2MediaType(forMime:)`,
     * MessageManager+V2.swift:937-943) — same table here.
     */
    private fun mediaTypeFromV2(rawMediaType: String?, mime: String): MediaType? {
        // The label table is shared with every other transport so a case iOS adds —
        // `gif` next — is learnt once. Unknown ⇒ null ⇒ the mime fallback below,
        // which is total.
        MediaType.fromWireLabel(rawMediaType)?.let { return it }
        return when {
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            mime.contains("vcard") -> MediaType.CONTACT
            else -> MediaType.DOCUMENT
        }
    }

    /** Android MediaType → the iOS raw value used in the group-message JSON. */
    private fun iosMediaTypeForMime(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "document"
    }

    /**
     * The APNs/FCM wake iOS fires on every V2 send (MessageManager+V2.swift:384-419).
     *
     * The V2 relay does NOT push. Android returned as soon as `sendText` succeeded,
     * and the only code that pushes lives further down the legacy branch
     * (`VPSClient.sendMessage` → `sendPushNotification`), so EVERY Android→iPhone and
     * Android→Android V2 message arrived silently — visible only when the recipient
     * next opened the app. Same payload keys as the legacy path so the push service
     * and both receivers are unchanged.
     *
     * Silent control traffic (receipts, typing, profile/wallpaper sync) queues without
     * alerting, mirroring iOS's `v2SilentControlPrefixes` (MessageManager+V2.swift:907-912)
     * and the legacy `?nopush=1`.
     */
    private suspend fun pushAfterV2Send(recipientPublicKey: String, messageId: String, content: String?) {
        // One catalog for every send path — see [isSilentControlContent].
        if (content != null && isSilentControlContent(content)) return
        val data = mapOf(
            "senderPublicKey" to myPublicKey,   // iOS APNs key name
            "senderKey" to myPublicKey,         // Android FCM key name
            "messageId" to messageId,
            "v2" to "1",
            "bodyLocKey" to "notification.push.body.encrypted",
            "titleLocKey" to "notification.push.title.message"
        )
        val ok = try {
            vpsClient.sendPushNotification(
                recipientPublicKey = recipientPublicKey,
                type = "message",
                title = "New Message",
                body = "You have a new encrypted message",
                data = data
            )
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ V2 wake push threw: ${e.message}")
            false
        }
        if (!ok) {
            // 🔁 Not "non-fatal": the message IS on the relay and the recipient has
            // simply not been told, so they see it only when they next open the app
            // by hand. Queue it (iOS `PushRetryQueue.shared.enqueue`,
            // MessageManager+V2.swift:415-424) — the drain in the V2 loop retries it
            // with a backoff, and drops it once the message is no longer news.
            pushRetryQueue.enqueue(
                recipientPublicKey = recipientPublicKey,
                type = "message",
                title = "New Message",
                body = "You have a new encrypted message",
                data = data
            )
            Log.w(TAG, "🔁 V2 wake push failed — queued for retry (${pushRetryQueue.pendingCount} pending)")
        }
    }

    /**
     * Failed wake pushes, persisted. See [PushRetryQueue] for why the age bound
     * matters more than the attempt bound.
     */
    private val pushRetryQueue: com.oshi.messenger.service.PushRetryQueue by lazy {
        com.oshi.messenger.service.PushRetryQueue(context.filesDir)
    }

    /** One pass over the queue. Cheap no-op when it is empty; safe to call on a loop. */
    private suspend fun drainPushRetryQueue() {
        try {
            pushRetryQueue.drain { e ->
                vpsClient.sendPushNotification(
                    recipientPublicKey = e.recipientPublicKey,
                    type = e.type,
                    title = e.title,
                    body = e.body,
                    data = e.data
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔁 push retry drain failed: ${e.message}")
        }
    }

    /**
     * Normalize timestamp to milliseconds.
     * iOS sends seconds (timeIntervalSince1970 ~1.7e9), Android sends milliseconds (~1.7e12).
     * If value > 1e12, it's already ms; if <= 1e12, it's seconds → multiply by 1000.
     */
    private fun normalizeTimestamp(timestamp: Double): Long {
        return if (timestamp > 1e12) timestamp.toLong() else (timestamp * 1000).toLong()
    }

    private fun normalizeTimestamp(timestamp: Long): Long {
        return if (timestamp > 1e12.toLong()) timestamp else timestamp * 1000
    }

    /**
     * Check if content appears to still be encrypted/garbled.
     * Detects base64 blobs, JSON with ciphertext, and non-printable characters.
     */
    private fun isLikelyEncrypted(content: String): Boolean {
        if (content.isEmpty()) return false
        // JSON with ciphertext/header fields = undecrypted EncryptedMessage or DoubleRatchetMessage
        if (content.startsWith("{")) {
            try {
                val json = JSONObject(content)
                if (json.has("ciphertext") || json.has("header") || json.has("envelope")) {
                    return true
                }
            } catch (_: Exception) {}
        }
        // High ratio of non-printable/non-ASCII characters suggests binary/encrypted data
        val nonPrintable = content.count { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }
        if (nonPrintable > content.length / 4) return true
        return false
    }

    /**
     * Derive group key from group ID (iOS compatible)
     * SHA256(groupId_utf8 + "oshi-group-envelope-key"_utf8)
     *
     * 🔧 FIX: Normalize UUID to UPPERCASE to match iOS behavior.
     * iOS's UUID.uuidString always returns uppercase (e.g. "A4B2C1D3-...").
     * Android's UUID.randomUUID().toString() returns lowercase (e.g. "a4b2c1d3-...").
     * Without normalization, groups created on Android produce mismatched keys,
     * causing iOS→Android group message decryption to silently fail.
     */
    private fun deriveGroupKey(groupId: String): ByteArray {
        val canonicalId = groupId.uppercase()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(canonicalId.toByteArray(Charsets.UTF_8))
        md.update("oshi-group-envelope-key".toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /**
     * Decrypt incoming message from iOS or Android.
     *
     * iOS sends TWO formats depending on delivery method:
     * 1. VPS Direct: encryptedContent = base64(JSON(DoubleRatchetMessage))
     *    JSON has "header" + "ciphertext" -> decrypt directly with Double Ratchet
     * 2. IPFS (via EncryptedMessage): encryptedContent = base64(JSON(EncryptedMessage))
     *    JSON has "ciphertext" + "signature" + "senderPublicKey" (NO "header")
     *    AES-GCM decrypt outer layer first, then Double Ratchet decrypt inner
     *
     * Falls back to direct AES-GCM for Android-to-Android messages.
     */
    /**
     * __REPLAY_IS_NOT_A_DESYNC_2026_10_14__ The ONE place that decides whether a
     * failed decrypt justifies tearing down the ratchet session.
     *
     * Four receive paths (mesh, cross-platform mesh, VPS, LoRa) answered ANY
     * decrypt failure by resetting the session and retrying. That is right for a
     * genuine desync and catastrophic for a replay: a second copy of an envelope
     * we have already read proves the session is HEALTHY, and resetting it means
     * the peer's next message cannot be read — which resets it again. Reported
     * 14/10 as "message delivery works one time in two" (Huawei nova 5T, iPad 9).
     *
     * A mesh floods and retries, and every `CrossPlatformMesh.sendMessage` mints a
     * fresh frame id, so a second copy slips past the dedup by id and reaches the
     * ratchet. The ratchet refuses it correctly and rolls its own state back; only
     * this layer did damage.
     *
     * iOS states the same rule in `MessageManager.ratchetFailureProvesSessionIsBroken`.
     *
     * @return true when the session was reset and the caller should retry the
     *         decrypt; false when the envelope was simply one we already hold.
     */
    private fun resetRatchetUnlessReplay(error: Exception, peerPublicKey: String): Boolean {
        if (!RatchetFailurePolicy.provesSessionIsBroken(error)) {
            Log.d(TAG, "⏭️ Envelope already read (${error.message}) — session left intact")
            return false
        }
        val sharedSecret = cryptoManager.deriveSharedSecret(peerPublicKey)
        doubleRatchet.resetSession(peerPublicKey, myPublicKey, sharedSecret)
        doubleRatchet.saveSessions(context)
        return true
    }

    private fun decryptIOSMessage(encryptedContent: ByteArray, senderPublicKey: String): String {
        val contentStr = String(encryptedContent, Charsets.UTF_8)

        if (contentStr.startsWith("{")) {
            try {
                val json = JSONObject(contentStr)

                // Case 1: Direct DoubleRatchetMessage (iOS VPS direct, no AES-GCM wrapper)
                // DoubleRatchetMessage = { header: {...}, ciphertext: "..." }
                if (json.has("header") && json.has("ciphertext")) {
                    Log.d(TAG, "📦 Detected direct DoubleRatchetMessage (VPS)")
                    return decryptDoubleRatchetJSON(json, senderPublicKey)
                }

                // Case 2: EncryptedMessage envelope (iOS IPFS, AES-GCM wrapped)
                // EncryptedMessage = { ciphertext: "...", signature: "...", senderPublicKey: "..." }
                if (json.has("ciphertext")) {
                    Log.d(TAG, "📦 Detected EncryptedMessage envelope (IPFS)")
                    val ciphertextBase64 = json.getString("ciphertext")

                    // 🔴 IMPERSONATION FIX. This used to be
                    //     json.optString("senderPublicKey", senderPublicKey)
                    // i.e. the key came from a field INSIDE the envelope, while every
                    // caller attributes the plaintext to the OUTER routing key
                    // (`vpsMessage.senderPublicKey`, `meshMessage.senderPublicKey`).
                    // Decrypting with one identity and filing the result under another is
                    // the whole bug: an attacker encrypts to you under THEIR OWN keypair,
                    // sets the inner field to their key and the outer field to your
                    // contact's, and the message renders as coming from your contact. No
                    // secret of the contact's is needed — their public key is on a QR code.
                    // It also reached `handleProfileUpdate` and the multi-device link and
                    // sync handlers under a forged identity.
                    //
                    // iOS never had this because it has ONE sender field:
                    // `let senderKey = message.senderPublicKey` (`MessageManager.swift:6397`).
                    //
                    // The attributed key is now the only key used. A DIFFERENT inner value
                    // is not tolerated and not silently ignored — it has no legitimate
                    // cause and is refused, so a forgery attempt fails loudly instead of
                    // falling back to something that happens to decrypt.
                    val claimedInner = json.optString("senderPublicKey", "")
                    if (claimedInner.isNotEmpty() &&
                        canonicalIdentity(claimedInner) != canonicalIdentity(senderPublicKey)
                    ) {
                        Log.w(
                            TAG,
                            "🚫 Envelope claims sender ${claimedInner.take(8)}… but was routed " +
                                "from ${senderPublicKey.take(8)}… — refusing (impersonation attempt)"
                        )
                        throw SecurityException("envelope sender does not match routed sender")
                    }
                    val senderKey = senderPublicKey
                    val sealedBox = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

                    // Decrypt outer AES-GCM layer
                    val innerContent = cryptoManager.decryptMessageToString(sealedBox, senderKey)

                    // Inner content is base64-encoded DoubleRatchetMessage JSON
                    try {
                        val ratchetData = Base64.decode(innerContent, Base64.NO_WRAP)
                        val ratchetJson = JSONObject(String(ratchetData, Charsets.UTF_8))

                        if (ratchetJson.has("header") && ratchetJson.has("ciphertext")) {
                            return decryptDoubleRatchetJSON(ratchetJson, senderKey)
                        }
                    } catch (e: ReplayedEnvelopeException) {
                        // __REPLAY_IS_NOT_A_DESYNC_2026_10_14__ Must reach the caller
                        // WEARING ITS TYPE. Re-wrapped as a generic failure it became
                        // indistinguishable from a broken session, and every mesh receive
                        // path answers a broken session by resetting the ratchet — which
                        // is what destroyed a healthy one and lost the NEXT message.
                        throw e
                    } catch (e: Exception) {
                        // 🔧 FIX: If inner content looks like base64 of a ratchet message,
                        // it's still encrypted — don't return it as plaintext
                        if (innerContent.startsWith("eyJ")) {
                            // eyJ = base64 of "{" — still JSON/encrypted content
                            Log.w(TAG, "⚠️ DoubleRatchet decrypt failed, content still encrypted: ${e.message}")
                            throw Exception("DoubleRatchet decryption failed: ${e.message}")
                        }
                        if (LOG_SENSITIVE) Log.d(TAG, "📨 iOS message decrypted (non-ratchet): ${innerContent.take(50)}...")
                    }

                    return innerContent
                }
            } catch (e: ReplayedEnvelopeException) {
                // See above: the raw AES-GCM fallback cannot read a ratchet envelope
                // anyway, so swallowing this only hid WHY it failed.
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "📨 JSON parse failed, trying direct decrypt: ${e.message}")
            }
        }

        // Fallback: direct AES-GCM decryption (Android-to-Android format)
        return cryptoManager.decryptMessageToString(encryptedContent, senderPublicKey)
    }

    /**
     * Decrypt a DoubleRatchetMessage JSON: { header: {...}, ciphertext: "..." }
     */
    private fun decryptDoubleRatchetJSON(ratchetJson: JSONObject, senderPublicKey: String): String {
        val header = ratchetJson.getJSONObject("header")
        Log.d(TAG, "📦 DoubleRatchet: session=${header.optString("sessionId", "?").take(8)}, msg#${header.optInt("messageNumber", -1)}")

        val ratchetMessage = IOSRatchetMessage.fromJSON(ratchetJson)

        val sharedSecret = cryptoManager.deriveSharedSecret(senderPublicKey)
        // CV-010: the header decides, not what we happen to have learned.
        //
        // Capability arrives asynchronously on a profile, so two clients that
        // have BOTH updated can disagree about each other. If the sender built a
        // rotating (v3) session and we built a v2 one, the strict version check
        // in `decrypt` would kill a conversation between two capable clients. A
        // header can only ever RAISE the version of a session being created here;
        // an established session is never pulled back down, because a header is
        // attacker-controlled.
        val session = doubleRatchet.getOrCreateSession(
            theirPublicKey = senderPublicKey,
            ourPublicKey = myPublicKey,
            sharedSecret = sharedSecret,
            ratcheting = ratchetMessage.header.sessionVersion >= DoubleRatchet.RATCHETING_SESSION_VERSION
        )

        val plaintext = doubleRatchet.decrypt(session, ratchetMessage)
        // ⚠️ `saveSessions` RETHROWS ConcurrentModificationException by design now
        // (`DoubleRatchet.kt:814-816`) — a failed persist must never be mistaken for a
        // successful one. It must not escape from HERE, though: the enclosing
        // `decryptIOSMessage` wraps this call in a broad `catch (Exception)` that falls
        // through to a raw AES-GCM decrypt, so letting it out would throw away a
        // message that HAS been decrypted and whose ratchet is already spent — the
        // swallow, in its worst form. Retry once, then log loudly and hand the
        // plaintext back. The receive chain rewinding costs a later re-key, not a lost
        // message; dropping it here costs the message itself.
        persistRatchetAfterReceive()

        // 📡 The ONLY place cryptographic proof of the sender exists. See
        // `ratchetProofCounter` — the LoRa node→identity binding refuses to
        // happen unless this line ran for that very message.
        ratchetProofCounter++

        if (LOG_SENSITIVE) Log.d(TAG, "✅ DoubleRatchet decrypted: ${plaintext.take(50)}...")
        return plaintext
    }

    /**
     * Persist the ratchet after a RECEIVE, without letting a persist failure destroy
     * the plaintext the caller is already holding.
     *
     * One retry, then a loud log. Never silent, never fatal — see the call site for
     * why the asymmetry with the send path is deliberate.
     */
    private fun persistRatchetAfterReceive() {
        try {
            doubleRatchet.saveSessions(context)
        } catch (e: java.util.ConcurrentModificationException) {
            try {
                doubleRatchet.saveSessions(context)
                Log.w(TAG, "🔐 ratchet persist raced on receive — second attempt succeeded")
            } catch (e2: Exception) {
                Log.e(
                    TAG,
                    "🔐 RATCHET STATE NOT PERSISTED after receive: ${e2.message}. " +
                        "The receive chain will rewind on the next process start.",
                    e2
                )
            }
        }
    }

    /**
     * Encrypt content using Double Ratchet and wrap in iOS-compatible EncryptedMessage format.
     *
     * Produces: base64(JSON(EncryptedMessage)) where EncryptedMessage = {
     *   ciphertext: base64(AES-GCM(base64(JSON(DoubleRatchetMessage)))),
     *   signature: "",
     *   senderPublicKey: myKey,
     *   timestamp: epochSeconds
     * }
     *
     * This matches iOS IdentityManager.wrapRatchetMessage() output.
     * Returns raw bytes suitable for VPSClient.sendMessage().
     */
    private fun encryptWithDoubleRatchet(plaintext: String, recipientPublicKey: String): ByteArray {
        val sharedSecret = cryptoManager.deriveSharedSecret(recipientPublicKey)
        // FORCE v3: starting the conversation, we ALWAYS build a rotating
        // session — the peer's advertised capability is no longer consulted.
        // Message, media and group all ride the rotating ratchet now; a peer on a
        // build that cannot read v3 fails to decrypt and is asked to update by the
        // in-conversation notice (AppVersions/VersionNotice). Mirrors iOS
        // `shouldRatchet` returning true whenever it initiates. [Audit 2026-09-09]
        val session = doubleRatchet.getOrCreateSession(
            theirPublicKey = recipientPublicKey,
            ourPublicKey = myPublicKey,
            sharedSecret = sharedSecret,
            ratcheting = true
        )

        // Step 1: Double Ratchet encrypt
        val ratchetMessage = doubleRatchet.encrypt(session, plaintext)
        // ⚠️ Deliberately NOT caught on the send side. The sending chain has advanced
        // in memory; if the write did not land, process death rewinds it and the NEXT
        // send re-derives a message key that is already on the wire — an AES-256-GCM
        // key reused across two plaintexts. Letting this throw fails the send, the
        // caller stamps the row `isFailed` and the user can retry (the in-memory chain
        // has already moved on, so the retry uses a fresh key). Sending anyway would
        // be the swallow the crypto audit named.
        doubleRatchet.saveSessions(context)

        // Step 2: Serialize ratchet message to JSON, then base64
        val ratchetJson = ratchetMessage.toJSON().toString().toByteArray(Charsets.UTF_8)
        val ratchetBase64 = Base64.encodeToString(ratchetJson, Base64.NO_WRAP)

        // Step 3: AES-GCM encrypt with HKDF(shared_secret, empty_salt, empty_info)
        // Matches iOS: sharedSecret.hkdfDerivedSymmetricKey(salt: Data(), sharedInfo: Data())
        val symmetricKey = cryptoManager.hkdfDerive(sharedSecret, ByteArray(0), ByteArray(0), 32)
        val sealedBox = cryptoManager.encryptAESGCM(ratchetBase64.toByteArray(Charsets.UTF_8), symmetricKey)

        // Step 4: Create iOS-compatible EncryptedMessage JSON
        // IMPORTANT: iOS JSONDecoder uses .deferredToDate (timeIntervalSinceReferenceDate = Apple epoch)
        val appleEpochTimestamp = (System.currentTimeMillis() / 1000.0) - 978307200.0
        val encryptedMessageJson = JSONObject().apply {
            put("ciphertext", Base64.encodeToString(sealedBox, Base64.NO_WRAP))
            put("signature", "")  // iOS doesn't verify signature on decrypt
            put("senderPublicKey", myPublicKey)
            put("timestamp", appleEpochTimestamp)
        }

        // Step 5: Return JSON bytes (will be base64-encoded by VPSClient)
        Log.d(TAG, "🔐 DoubleRatchet encrypted for ${recipientPublicKey.take(8)}...")
        return encryptedMessageJson.toString().toByteArray(Charsets.UTF_8)
    }

    // COVERT CHANNEL TRANSPORT: the dead `sendViaCovertChannel` (always false,
    // then a silent plain-relay fallback) was removed on 2026-11-18 — see
    // [CoverOutbox] / `parkForCover` for what cover mode does now.

    /**
     * Handle location message (one-time or live)
     * Deduplicates live location updates by sessionId (matches iOS behavior)
     */
    private suspend fun handleLocationMessage(
        messageId: String,
        senderPublicKey: String,
        content: String,
        deliveryMethod: DeliveryMethod
    ) {
        // Parse location to check for live updates
        val locationMessage = LocationMessage.fromMessageString(content)

        // A `stopped` payload carries isUpdate=true as well (LocationMessage.stopped),
        // so it must be tested BEFORE the update branch or it is swallowed there and
        // the tracked coordinate is never dropped.
        if (locationMessage != null && locationMessage.isStopped && locationMessage.sessionId != null) {
            // Drop the cached coordinate so the Map tab stops polling a dead session.
            com.oshi.messenger.network.location.LocationSharingManager.clearTrackedLiveLocation(
                locationMessage.sessionId!!
            )
            val existing = messageDao.findLocationBySessionId(senderPublicKey, locationMessage.sessionId!!)
            if (existing != null) {
                messageDao.updateContent(existing.id, content, System.currentTimeMillis())
                Log.d(TAG, "📍 Stopped live location for session ${locationMessage.sessionId}")
                return
            }
        }

        // If this is a live location update with a sessionId, update existing message instead of creating new
        if (locationMessage != null && locationMessage.isUpdate && locationMessage.sessionId != null) {
            // Track latest coordinate for map display
            com.oshi.messenger.network.location.LocationSharingManager.trackReceivedLiveLocation(
                locationMessage.sessionId!!,
                locationMessage.latitude,
                locationMessage.longitude
            )

            val existing = messageDao.findLocationBySessionId(senderPublicKey, locationMessage.sessionId!!)
            if (existing != null) {
                messageDao.updateContent(existing.id, content, System.currentTimeMillis())
                Log.d(TAG, "📍 Updated existing live location for session ${locationMessage.sessionId}")
                return
            }
        }

        val message = Message(
            id = messageId,
            senderPublicKey = canonicalIdentity(senderPublicKey),   // 🔑 stored spelling
            recipientPublicKey = myPublicKey,
            content = content,
            timestamp = System.currentTimeMillis(),
            isFromMe = false,
            mediaType = MediaType.LOCATION,
            deliveryMethod = deliveryMethod
        )

        if (messageDao.getMessageById(messageId) == null) {
            messageDao.insert(sanitizeMediaForInsert(message))
            noteBlockedByPeer(senderPublicKey, null)
            ensureContactExists(senderPublicKey)
            Log.d(TAG, "📥 Received location via ${deliveryMethod.name} from ${senderPublicKey.take(8)}...")
        }
    }

    /**
     * Handle message from CrossPlatformMesh (iOS-Android communication)
     */
    private suspend fun handleCrossPlatformMessage(cpMessage: CrossPlatformMessage) {
        Log.d(TAG, "🌐 handleCrossPlatformMessage: type=${cpMessage.type}, sender=${cpMessage.senderPublicKey.take(8)}, platform=${cpMessage.platform}")
        Log.d(TAG, "   payload preview: ${cpMessage.payload.take(120)}...")

        // Skip own messages
        if (cpMessage.senderPublicKey == myPublicKey) {
            Log.d(TAG, "⏭️ Skipping self-message from CrossPlatformMesh")
            return
        }

        // 🚫 Blocked sender — drop before the group/media branches fan out.
        if (isBlockedSender(cpMessage.senderPublicKey)) {
            Log.d(TAG, "🚫 Dropping cross-platform message from blocked ${cpMessage.senderPublicKey.take(8)}...")
            // __BLOCKED_NOTICE_2026_09_18__ No notice from here, deliberately.
            // `cpMessage.payload` is still SEALED at this point, so we cannot tell
            // a typed message from a typing indicator without decrypting traffic
            // from someone the user blocked — which is precisely the work this
            // early drop exists to avoid. The notice is sent from the V2 paths
            // below, which hand us plaintext and carry all of production today
            // (`/v2/config` = enabled/100).
            return
        }

        // 🪦 Stale identity gate — sender's key has been marked as a rotated
        // (no-longer-valid) identity. Drop silently. This catches BOTH
        // direct messages and the GROUP_MESSAGE/GROUP_UPDATE branches below
        // because we run before they fan out. Important: the GROUP_MESSAGE
        // branch reaches GroupManager.handleIncomingGroupMessage which has
        // its own stale check too, but blocking here saves the IPFS-envelope
        // decryption work for known-stale senders.
        if (com.oshi.messenger.service.StaleIdentityStore.get(context).isStale(cpMessage.senderPublicKey)) {
            Log.w(TAG, "🪦 Stale identity sender — dropping cross-platform message: ${cpMessage.senderPublicKey.take(12)}…")
            return
        }

        // Handle GROUP_MESSAGE type directly — payload is an IPFS group envelope JSON {v:2,...}
        // Same encrypted format as IPFS delivery; bypass DoubleRatchet and use group key decryption.
        if (cpMessage.type == "GROUP_MESSAGE") {
            Log.d(TAG, "📡 CrossPlatform GROUP_MESSAGE from ${cpMessage.senderPublicKey.take(8)}: routing to group envelope decryptor")
            scope.launch {
                val handled = vpsClient.processGroupEnvelopeFromMesh(cpMessage.payload)
                if (!handled) Log.w(TAG, "📡 Mesh GROUP_MESSAGE: group envelope decryption failed (group not yet known?)")
            }
            return
        }

        // iOS-format plaintext mesh media: JSON envelope with `data` + (`mediaType`|`type`).
        // Skip the DoubleRatchet decryption path — both platforms ship cross-platform
        // mesh media as plaintext over the local TCP/BLE link (matches iOS sender's
        // CrossPlatformMesh.sendMedia behavior). Without this bypass, decrypt failed,
        // the plaintextContent fallback found no such field, and the image was dropped.
        if (cpMessage.type == "MEDIA_MESSAGE") {
            try {
                val maybeIosMedia = JSONObject(cpMessage.payload)
                if (maybeIosMedia.has("data") &&
                    (maybeIosMedia.has("mediaType") || maybeIosMedia.has("type"))) {
                    Log.d(TAG, "🖼️ iOS-format mesh media — bypassing decryption, routing direct")
                    handleMediaContent(cpMessage.id, cpMessage.senderPublicKey, cpMessage.payload, DeliveryMethod.MESH)
                    return
                }
            } catch (_: Exception) {
                // Not iOS-format JSON — fall through to encrypted path below.
            }
        }

        // Handle GROUP_UPDATE type directly — payload is plaintext GROUP_UPDATE_PREFIX + json
        // No decryption needed; route straight to GroupManager
        if (cpMessage.type == "GROUP_UPDATE") {
            val groupPayload = cpMessage.payload
            val prefix = GroupManager.GROUP_UPDATE_PREFIX
            if (groupPayload.startsWith(prefix)) {
                Log.d(TAG, "📢 CrossPlatform GROUP_UPDATE: routing to GroupManager from ${cpMessage.senderPublicKey.take(8)}")
                groupManager.handleGroupUpdate(groupPayload, cpMessage.senderPublicKey)
            } else {
                Log.w(TAG, "⚠️ GROUP_UPDATE type but payload missing prefix: ${groupPayload.take(60)}")
            }
            return
        }

        val payload = cpMessage.payload
        var secureJson: JSONObject? = null  // Keep reference for fallback

        try {
            // Handle TWO payload formats from mesh:
            // 1. Base64-encoded encrypted bytes (from Android mesh)
            // 2. Raw JSON string (SecureMessage from iOS mesh via relayMessage)
            val encryptedData: ByteArray = if (payload.startsWith("{")) {
                // iOS sends SecureMessage JSON directly as payload
                // Extract encryptedContent from the SecureMessage wrapper
                try {
                    secureJson = JSONObject(payload)
                    val encryptedContentJson = secureJson!!.optJSONObject("encryptedContent")
                    if (encryptedContentJson != null) {
                        // It's a SecureMessage — extract inner EncryptedMessage
                        val extracted = encryptedContentJson.toString()
                        Log.d(TAG, "📦 CrossPlatform: Detected iOS SecureMessage wrapper, encryptedContent: ${extracted.take(80)}...")
                        extracted.toByteArray(Charsets.UTF_8)
                    } else {
                        // It's some other JSON (EncryptedMessage or DoubleRatchetMessage directly)
                        Log.d(TAG, "📦 CrossPlatform: JSON payload without encryptedContent field")
                        payload.toByteArray(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "📦 CrossPlatform: JSON parse failed, using raw: ${e.message}")
                    payload.toByteArray(Charsets.UTF_8)
                }
            } else {
                // Base64-encoded encrypted content (from Android mesh or some iOS paths)
                Log.d(TAG, "📦 CrossPlatform: Base64-encoded payload (${payload.length} chars)")
                android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
            }

            // __MESH_STABLE_ID_2026_09_27__ The same message rides the mesh AND the
            // relay (dual-send, both directions). The relay copy carries the sender's
            // message id; a SecureMessage-wrapped mesh copy carries it too (`id`), the
            // frame's own id is random per hop. Dedup on the real id BEFORE decrypting:
            // a second decrypt of an already-consumed ratchet step fails, and the
            // failure path below RESETS the session — a duplicate must never get there.
            val effectiveId = meshEnvelopeMessageId(payload, cpMessage.id)
            if (effectiveId != cpMessage.id && messageDao.getMessageById(effectiveId) != null) {
                Log.d(TAG, "⏭️ Mesh copy of ${effectiveId.take(8)} already delivered — skipped before decrypt")
                return
            }
            val routedMessage = if (effectiveId != cpMessage.id) cpMessage.copy(id = effectiveId) else cpMessage

            Log.d(TAG, "🔐 Decrypting ${encryptedData.size} bytes from ${cpMessage.senderPublicKey.take(8)}...")

            // Try decryption with retry on DoubleRatchet session failure
            val decrypted: String = try {
                decryptIOSMessage(encryptedData, cpMessage.senderPublicKey)
            } catch (ratchetError: Exception) {
                Log.w(TAG, "⚠️ Primary decryption failed: ${ratchetError.message}")

                // Retry with fresh DoubleRatchet session (session may be desynced)
                try {
                    Log.d(TAG, "🔄 Resetting DoubleRatchet session and retrying...")
                    if (!resetRatchetUnlessReplay(ratchetError, cpMessage.senderPublicKey)) throw ratchetError
                    decryptIOSMessage(encryptedData, cpMessage.senderPublicKey)
                } catch (retryError: Exception) {
                    Log.w(TAG, "⚠️ Retry decryption also failed: ${retryError.message}")
                    // Throw to trigger plaintext fallback
                    throw retryError
                }
            }

            if (LOG_SENSITIVE) Log.d(TAG, "✅ Decrypted mesh message: ${decrypted.take(80)}...")
            routeDecryptedMeshMessage(decrypted, routedMessage)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Decryption failed for cross-platform message: ${e.message}")

            // 🔧 FALLBACK: iOS sends plaintextContent in SecureMessage JSON (for mesh/local delivery).
            // When DoubleRatchet is desynced, use plaintextContent as reliable fallback.
            if (secureJson != null) {
                val plaintextContent = secureJson!!.optString("plaintextContent", "")
                if (plaintextContent.isNotEmpty()) {
                    if (LOG_SENSITIVE) Log.d(TAG, "🔄 Using plaintextContent fallback: ${plaintextContent.take(80)}...")
                    try {
                        routeDecryptedMeshMessage(
                            plaintextContent,
                            cpMessage.copy(id = meshEnvelopeMessageId(payload, cpMessage.id))
                        )
                        return
                    } catch (fallbackError: Exception) {
                        Log.e(TAG, "❌ Fallback also failed: ${fallbackError.message}")
                    }
                }
            }

            Log.e(TAG, "❌ No fallback available - message dropped", e)
        }
    }

    /**
     * Route a decrypted mesh message to the appropriate handler based on content type.
     */
    private suspend fun routeDecryptedMeshMessage(decrypted: String, cpMessage: CrossPlatformMessage) {
        val deliveryMethod = DeliveryMethod.MESH

        // 🔒 Offline-only. Checked ABOVE the `when` on purpose: the arms of that
        // `when` each CONSUME the message, and a request must not be consumed —
        // it is marked `.rendered` in the catalog and has to reach the `else`
        // arm, the one that stores an ordinary text row. Only the ANSWER is
        // swallowed here. iOS does the same with the return value of
        // `handleOfflineOnlyPayload`.
        if (com.oshi.messenger.data.model.OfflineOnlyRequest.isRequest(decrypted)) {
            if (handleOfflineOnly(decrypted, cpMessage.senderPublicKey)) return
            // A request: fall through. No arm below claims a 🔒 payload.
        }

        // 💓 __ALIVE_CHECK_IN_2026_11_04__ Same shape, and above the `when` for the
        // same reason: the event must reach the `else` arm that stores a text row.
        if (com.oshi.messenger.data.model.AliveCheckInEvent.isEvent(decrypted)) {
            handleAliveCheckIn(decrypted, cpMessage.senderPublicKey)
        }

        when {
            // 📬 Receipt messages (delivery/read) — handled first
            isReceiptMessage(decrypted) -> {
                if (decrypted.startsWith(DELIVERY_RECEIPT_PREFIX)) {
                    handleDeliveryReceipt(decrypted, cpMessage.senderPublicKey)
                } else {
                    handleReadReceipt(decrypted, cpMessage.senderPublicKey)
                }
            }
            // 📑 Group receipt, arriving over the cross-platform mesh.
            consumeGroupReceipt(decrypted, cpMessage.senderPublicKey) -> { }
            // 🔗 Device link handshake — see handleVPSMessage for why this sits here.
            MultiDeviceSyncManager.isLinkPayload(decrypted) -> {
                multiDeviceSyncManager.handleLinkRequest(decrypted, cpMessage.senderPublicKey)
            }
            // 🔄 Multi-device sync. MUST sit above the media arm.
            MultiDeviceSyncManager.isSyncPayload(decrypted) -> {
                multiDeviceSyncManager.handleSyncData(decrypted, cpMessage.senderPublicKey)
            }
            // Key-order-independent — see [OSHIControlPayload.isMediaEnvelope].
            cpMessage.type == "MEDIA_MESSAGE" ||
                decrypted.startsWith("{\"type\":") ||
                OSHIControlPayload.isMediaEnvelope(decrypted) -> {
                handleMediaContent(cpMessage.id, cpMessage.senderPublicKey, decrypted, deliveryMethod)
            }
            decrypted.startsWith("📍LOCATION📍") -> {
                handleLocationMessage(cpMessage.id, cpMessage.senderPublicKey, decrypted, deliveryMethod)
            }
            decrypted.startsWith("📸PROFILE_UPDATE📸") -> {
                handleProfileUpdate(cpMessage.senderPublicKey, decrypted)
            }
            decrypted.startsWith("📸PROFILE_REQUEST📸") -> {
                sendProfileUpdate(cpMessage.senderPublicKey, answeringRequest = true)
            }
            decrypted.startsWith("🎨WALLPAPER_UPDATE🎨") -> {
                handleWallpaperUpdate(cpMessage.senderPublicKey, decrypted)
            }
            // 🔗 Profile links visibility messages (iOS LINKS_VISIBILITY)
            profileLinksManager.isLinksVisibilityMessage(decrypted) -> {
                profileLinksManager.processIncomingVisibility(cpMessage.senderPublicKey, decrypted)
            }
            // 📞 The peer's copy of an ANSWERED call summary is a duplicate — see the
            // note at the mesh funnel. iOS MessageManager.swift:5810-5813.
            OSHIControlPayload.isAnsweredCallSummary(decrypted) -> {
                Log.d(TAG, "\uD83D\uDCDE Answered-call summary from peer ignored \u2014 ours is authoritative")
            }
            // 🛡️ Check-In / Accompagnement messages
            decrypted.startsWith("\uD83D\uDEE1\uFE0FCHECK_IN\uD83D\uDEE1\uFE0F") -> {
                handleCheckInMessage(cpMessage.id, cpMessage.senderPublicKey, decrypted, DeliveryMethod.MESH)
            }
            // 📊 Poll ballots — see recordPollBallot.
            com.oshi.messenger.data.model.PollVotePayload.isVoteMessage(decrypted) -> {
                recordPollBallot(decrypted, cpMessage.senderPublicKey)
            }
            // ❤️ Reaction messages (both Android 👍REACTION👍 and iOS 🔥REACTION🔥 formats)
            reactionManager.isReactionMessage(decrypted) -> {
                reactionManager.handleIncomingReaction(decrypted, cpMessage.senderPublicKey)
            }
            // ✍️ Typing indicators (Android ✍️TYPING✍️ and iOS ⌨️TYPING⌨️)
            typingIndicatorManager.isTypingMessage(decrypted) -> {
                typingIndicatorManager.handleIncomingTypingIndicator(decrypted, cpMessage.senderPublicKey)
            }
            // ✏️ Edit messages (Android ✏️EDIT✏️)
            messageActionsManager.isEdit(decrypted) -> {
                messageActionsManager.handleIncomingEdit(decrypted, cpMessage.senderPublicKey)
            }
            // 🗑️ Delete messages (Android 🗑️DELETE🗑️)
            messageActionsManager.isDelete(decrypted) -> {
                messageActionsManager.handleIncomingDelete(decrypted, cpMessage.senderPublicKey)
            }
            // 🔧 iOS unified action messages (🔧ACTION🔧 — edit/delete/pin)
            messageActionsManager.isActionMessage(decrypted) -> {
                messageActionsManager.handleIncomingAction(decrypted, cpMessage.senderPublicKey)
            }
            // 🔑 Key rotation messages
            decrypted.startsWith("🔑KEY_ROTATION🔑") -> {
                keyRotationManager.handleKeyRotationNotification(decrypted, cpMessage.senderPublicKey)
            }
            // 👥 Group messages
            decrypted.startsWith(GROUP_MESSAGE_PREFIX) -> {
                groupManager.handleIncomingGroupMessage(decrypted, cpMessage.senderPublicKey)
            }
            // 📢 Group update messages
            decrypted.startsWith(GROUP_UPDATE_PREFIX) -> {
                groupManager.handleGroupUpdate(decrypted, cpMessage.senderPublicKey)
            }
            else -> {
                // 🕵️ Steganography extraction: check cross-platform text for hidden data
                    // 🕵️ No steganographic extraction here — matching iOS.
                //
                // iOS never emits stego on this path (`steganographyEnabled` has zero
                // consumers in any send path — audit §4), so `containsHiddenData` could
                // only ever FALSE-POSITIVE: it fired on ordinary Cyrillic/Greek/Armenian
                // text and on any pasted NBSP, then burned a failing AES-GCM decrypt and
                // logged a misleading "detected hidden data but extraction failed".
                // Inbound covert-relay fragments do not arrive as chat text either — on
                // iOS they are polled and reassembled by `CovertChannelReceiver.swift:326-365`,
                // which Android does not have yet (audit P0 #7, separate epic).
                val cpDisplayContent = decrypted

                // Regular text message
                val normalizedTimestamp = normalizeTimestamp(cpMessage.timestamp)
                val message = Message(
                    id = cpMessage.id,
                    senderPublicKey = canonicalIdentity(cpMessage.senderPublicKey),  // 🔑 stored spelling
                    recipientPublicKey = myPublicKey,
                    content = cpDisplayContent,
                    timestamp = normalizedTimestamp,
                    isFromMe = false,
                    deliveryMethod = deliveryMethod
                )

                if (messageDao.getMessageById(message.id) == null) {
                    messageDao.insert(sanitizeMediaForInsert(message))
                    noteBlockedByPeer(cpMessage.senderPublicKey, cpDisplayContent)
                    notifyWebSession(message)
                    ensureContactExists(cpMessage.senderPublicKey)
                    Log.d(TAG, "📥 Received message via Mesh from ${cpMessage.senderPublicKey.take(8)}... (${cpMessage.platform})")

                    // 📬 Send delivery receipt back to sender — never for a 🚫BLOCKED🚫 notice.
                    if (!isBlockedNotice(cpDisplayContent)) sendDeliveryReceipt(message.id, cpMessage.senderPublicKey)
                }
            }
        }
    }

    // ============================================================================
    // PROFILE PICTURE SYNC (iOS-compatible)
    // ============================================================================

    /**
     * 📸 Send profile update to a specific contact
     */
    /**
     * @param answeringRequest true when this is the reply to an explicit
     *   `📸PROFILE_REQUEST📸`, which is not the same thing as pushing our
     *   avatar at somebody unprompted. See the gate below.
     */
    private suspend fun sendProfileUpdate(
        recipientPublicKey: String,
        answeringRequest: Boolean = false
    ) {
        // Receiving a message must not disclose our avatar. This is the Android
        // counterpart of iOS' profile-visibility gate.
        //
        // __PROFILE_NEVER_REACHED_ANDROID_2026_11_03__ (Anatole) An explicit
        // REQUEST is now answered, where an unsolicited push still is not.
        // Both platforms carried this gate and both applied it to the reply, so
        // a photo could only ever travel after the owner had sent that contact
        // a message — which is why "la photo de profil choisie sur iOS ne
        // s'affiche pas pour les utilisateurs Android", and why the mirror
        // image was equally true. Nothing else was broken: the sentinel, the
        // base64 and the `profileImageData` key match across platforms.
        //
        // The trade, stated because it is real: somebody who has messaged you
        // and whom you have never replied to can now obtain your profile
        // picture by asking. Owner decision, 3 November 2026, for the user
        // experience.
        if (!answeringRequest && !messageDao.hasOutgoingUserMessage(recipientPublicKey)) {
            Log.d(TAG, "🔒 Profile withheld until user replies to ${recipientPublicKey.take(8)}...")
            return
        }
        try {
            // __PROFILE_PICTURE_SYNC_2026_09_23__ Never send "no photo" just because the
            // background init load has not run yet — iOS would erase our picture.
            if (!myProfilePictureLoaded) loadMyProfilePicture()
            val profileBase64 = myProfilePicture?.let {
                Base64.encodeToString(it, Base64.NO_WRAP)
            }
            // __SHARED_NICKNAME_2026_09_22__ Read here, outside the JSONObject builder
            // (DataStore read is suspending). Same DataStore key as Settings.
            val ownNickname = runCatching {
                context.dataStore.data.first()[
                    androidx.datastore.preferences.core.stringPreferencesKey("display_name")
                ]
            }.getOrNull()

            // Use JSONObject to properly encode null (not string "null")
            val payloadJson = JSONObject().apply {
                put("type", "profile_update")
                if (profileBase64 != null) {
                    put("profileImageData", profileBase64)
                } else {
                    put("profileImageData", JSONObject.NULL)
                }
                // Only share last-active time if the Last Seen setting is on
                // (was always broadcast, ignoring the toggle). Omitting the field
                // tells the peer "not shared", matching iOS.
                //
                // __SHARED_NICKNAME_2026_09_22__ ...but the KEY is always written: iOS
                // decodes `lastSeen` as a REQUIRED Double, so omitting it made the whole
                // payload (nickname, avatar, capabilities) undecodable on every iPhone.
                // "Not shared" is 0 — see ProfileUpdateWire.
                put(
                    "lastSeen",
                    com.oshi.messenger.data.model.ProfileUpdateWire.lastSeenValue(
                        isEnabled(lastSeenKey), System.currentTimeMillis()
                    )
                )
                put("version", 1)
                // Capabilities, read by iOS `ProfileUpdatePayload`. Optional
                // fields: a build that predates them ignores what it does not
                // know, and absent is always read as "cannot".
                put("groupEnvelopeV3", true)
                put("ratchetV3", true)
                // Notre clé X-Wing, lue par iOS `ProfileUpdatePayload.pqPublicKey`.
                // `null` tant que `PostQuantumIdentity.ADVERTISE_PUBLIC_KEY` est
                // faux, et un champ absent veut dire « pas de post-quantique » —
                // le pair nous parle alors classiquement, exactement comme
                // aujourd'hui.
                com.oshi.messenger.network.encryption.PostQuantumIdentity
                    .advertisedPublicKeyBase64(context)
                    ?.let { put("pqPublicKey", it) }
                // 📦 This build's versionName, so the peer can tell whether we
                // speak the v3 responder key schedule ("ResponderInitialChain-v3",
                // commit 0cfc8c9) without us having to add a capability boolean per
                // wire change. A STRING, and a purely additive key:
                //   • Android reads this payload with JSONObject.opt*, which ignores
                //     every key it was not asked for;
                //   • iOS reads it with JSONDecoder into `ProfileUpdatePayload`
                //     (OSHI/ContactPresenceManager.swift:29), a Codable struct, and
                //     JSONDecoder ignores unknown keys by default.
                // So every already-installed build parses this payload unchanged.
                com.oshi.messenger.network.version.ProfileVersionWire
                    .stamp(this, com.oshi.messenger.BuildConfig.VERSION_NAME)
                // __SHARED_NICKNAME_2026_09_22__ The nickname set in Settings, so the
                // peer can show it instead of our key prefix. Omitted when we have none
                // (absent = "no change" for the receiver). Same key as iOS/Desktop.
                com.oshi.messenger.data.model.PeerNickname.stamp(this, ownNickname)
                // __PROFILE_BIO_2026_11_02__ (N4) the bio and accent colour this
                // user set on their own profile. Additive keys: absent means
                // "no change", so a build that predates them is unaffected.
                com.oshi.messenger.data.model.ProfileBioWire.stamp(
                    this,
                    com.oshi.messenger.data.model.OwnProfileExtras.bio(context),
                    com.oshi.messenger.data.model.OwnProfileExtras.bioUntil(context),
                    com.oshi.messenger.data.model.OwnProfileExtras.accentColor(context),
                )
            }

            val content = "📸PROFILE_UPDATE📸${payloadJson.toString()}"

            // __SHARED_NICKNAME_2026_09_22__ V2 FIRST, legacy only as the fallback —
            // the shape iOS `broadcastProfileToAllContacts` has always had. This went out
            // on the legacy IPFS/VPS lane only (PARITY.md row 0.18), which the desktop
            // client does not have, so an Android user's nickname and avatar could never
            // reach Windows/Linux. No wake push after a V2 send: a profile sync is silent
            // (`pushAfterV2Send` would return early on this prefix anyway).
            val sentViaV2 = try {
                v2MessageRouter.sendText(
                    recipientPublicKey,
                    content.toByteArray(Charsets.UTF_8),
                    UUID.randomUUID().toString(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "📸 V2 profile send threw, using legacy path: ${e.message}")
                false
            }
            if (!sentViaV2) {
                val encrypted = encryptWithDoubleRatchet(content, recipientPublicKey)

                // 🔕 Silent: a profile sync must not ring the far phone. Asked through the
                // catalog rather than hard-coded `true`, so the next control prefix someone
                // adds is covered by construction — that drift is why this survived a sweep.
                vpsClient.sendMessage(
                    recipientPublicKey, encrypted, UUID.randomUUID().toString(),
                    suppressPush = isSilentControlContent(content)
                )
            }
            Log.d(TAG, "📸 Profile update sent to ${recipientPublicKey.take(8)}... via ${if (sentViaV2) "V2" else "legacy"} (${myProfilePicture?.size ?: 0} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send profile update: ${e.message}")
        }
    }

    /**
     * 📸 Ask every contact we have no avatar for to send one.
     *
     * The gap this closes: [requestProfileIfNeeded] was only ever called from the
     * three inbound-message paths. Set a profile picture on an iPhone and send
     * nothing afterwards, and the Android peer never asked again, so it kept drawing
     * the two-letter fallback. iOS answers `📸PROFILE_REQUEST📸` with its current
     * picture (`MessageManager.swift:5883`, `:6019`), so one request is enough.
     *
     * Bounded on purpose:
     *  - only contacts with a null `profilePicture`, so it empties as it succeeds;
     *  - at most [PROFILE_SWEEP_BATCH] per tick, so a large address book does not
     *    turn one poll into a burst of requests;
     *  - no more often than [PROFILE_SWEEP_INTERVAL_MS], independent of tick rate;
     *  - blocked contacts are excluded by `getAllContactsList`.
     */
    private suspend fun sweepMissingProfilePictures() {
        val now = System.currentTimeMillis()
        if (now - lastProfileSweepAt < PROFILE_SWEEP_INTERVAL_MS) return
        lastProfileSweepAt = now
        val missing = profileSweepTargets(contactDao.getAllContactsList())
        if (missing.isEmpty()) return
        Log.d(TAG, "📸 Profile sweep: asking ${missing.size} contact(s) for an avatar")
        for (contact in missing) {
            try {
                requestProfileIfNeeded(contact.publicKey)
            } catch (e: Exception) {
                Log.w(TAG, "📸 Profile request failed for ${contact.publicKey.take(8)}...: ${e.message}")
            }
        }
    }

    /**
     * 📸 Write an inbound profile picture onto the right contact row.
     *
     * Contact rows are keyed on `canonicalIdentity(publicKey)` — that is what
     * [ensureContactExists] inserts. The key on the wire is whatever the peer sent,
     * which for an iPhone can be base64url (`-`/`_`) or unpadded, and then it is NOT
     * equal to the stored key. A bare `UPDATE … WHERE publicKey = <raw>` therefore
     * matched nothing and reported nothing.
     *
     * Tries canonical, then raw, then creates the contact and tries once more, so a
     * profile that arrives before the first stored message is not lost either.
     *
     * @return rows actually updated; 0 means the picture was dropped.
     */
    /** Wall clock of the last [sweepMissingProfilePictures] run. */
    private var lastProfileSweepAt = 0L

    private suspend fun writeProfilePicture(publicKey: String, data: ByteArray?): Int =
        writeProfilePictureTo(contactDao, publicKey, data)

    /**
     * 📸 Request profile from a contact if we don't have it
     */
    suspend fun requestProfileIfNeeded(publicKey: String) {
        // 🔑 Canonical for the LOOKUP (that is how the row is stored); the raw wire
        // key is still what the request is addressed to below.
        val contact = contactDao.getContactByPublicKey(canonicalIdentity(publicKey))
            ?: contactDao.getContactByPublicKey(publicKey)
        if (contact?.profilePicture != null) {
            Log.d(TAG, "📸 Already have profile for ${publicKey.take(8)}...")
            return
        }

        try {
            val content = "📸PROFILE_REQUEST📸"
            // __SHARED_NICKNAME_2026_09_22__ V2 first, like the update itself.
            val sentViaV2 = try {
                v2MessageRouter.sendText(
                    publicKey, content.toByteArray(Charsets.UTF_8), UUID.randomUUID().toString()
                )
            } catch (e: Exception) {
                Log.w(TAG, "📸 V2 profile request threw, using legacy path: ${e.message}")
                false
            }
            if (!sentViaV2) {
                val encrypted = encryptWithDoubleRatchet(content, publicKey)
                vpsClient.sendMessage(
                    publicKey, encrypted, UUID.randomUUID().toString(),
                    suppressPush = isSilentControlContent(content)   // 🔕 protocol traffic
                )
            }
            Log.d(TAG, "📸 Profile request sent to ${publicKey.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to request profile: ${e.message}")
        }
    }

    /**
     * 📸 Handle incoming profile update from contact
     */
    private suspend fun handleProfileUpdate(senderPublicKey: String, decrypted: String) {
        // 🔧 FIX: Prevent duplicate profile update processing (match iOS behavior)
        val now = System.currentTimeMillis()
        // __SHARED_NICKNAME_2026_09_22__ The cooldown used to drop ANY update from the
        // same sender within 30 s, so a nickname change right after an avatar change
        // (or two renames in a row) was lost until the next periodic send. Only an
        // IDENTICAL payload is a duplicate now; the key carries the content hash.
        val dedupKey = senderPublicKey + "#" + decrypted.hashCode()
        synchronized(recentProfileUpdates) {
            val lastUpdate = recentProfileUpdates[dedupKey]
            if (lastUpdate != null && now - lastUpdate < PROFILE_UPDATE_COOLDOWN) {
                Log.d(TAG, "⏭️ Profile update from ${senderPublicKey.take(8)}... skipped (duplicate within ${PROFILE_UPDATE_COOLDOWN/1000}s)")
                return
            }
            recentProfileUpdates[dedupKey] = now

            // 🔧 Clean up old entries if cache grows too large
            if (recentProfileUpdates.size > 100) {
                val sorted = recentProfileUpdates.entries.sortedBy { it.value }
                sorted.take(50).forEach { recentProfileUpdates.remove(it.key) }
            }
        }

        try {
            val jsonString = decrypted.removePrefix("📸PROFILE_UPDATE📸")
            // Record what this peer can do BEFORE the profile is applied, so the
            // next session with them can be negotiated. Absent means "cannot".
            runCatching {
                val caps = JSONObject(jsonString)
                // La clé post-quantique du pair, si elle est publiée. On
                // l'enregistre MÊME quand on n'annonce pas la nôtre: c'est sans
                // effet sur le comportement, et ça veut dire que le jour où l'on
                // active l'annonce l'annuaire est déjà rempli plutôt que vide.
                //
                // La session EN COURS n'est pas touchée — la re-clé d'une
                // conversation vivante laisserait le pair au milieu d'un fil.
                caps.optString("pqPublicKey", "").takeIf { it.isNotEmpty() }?.let { b64 ->
                    runCatching {
                        com.oshi.messenger.network.encryption.PostQuantumPeerDirectory.record(
                            context,
                            android.util.Base64.decode(b64, android.util.Base64.NO_WRAP),
                            senderPublicKey
                        )
                    }
                }
                PeerCapabilities.record(
                    context,
                    peer = senderPublicKey,
                    groupV3 = caps.optBoolean("groupEnvelopeV3", false),
                    ratchetV3 = caps.optBoolean("ratchetV3", false),
                    // 📦 Absent on every build that predates the field, which is
                    // read as "no opinion", never as "old" — see PeerCapabilities.
                    appVersion = com.oshi.messenger.network.version.ProfileVersionWire
                        .read(caps),
                    // 📦 Which version LINE that number belongs to. Android is 1.6.x
                    // and iOS is 1.0.x, so without this the two are not comparable
                    // and every iPhone reads as "behind". Absent = do not judge.
                    appPlatform = com.oshi.messenger.network.version.ProfileVersionWire
                        .readPlatform(caps)
                )
            }
            val json = org.json.JSONObject(jsonString)

            // __SHARED_NICKNAME_2026_09_22__ The peer's own nickname. Stored apart from
            // `contacts.alias` (the user's private override stays untouched); absent key
            // = older build = keep what we had. Silent by construction: this handler
            // stores no message row and raises no notification.
            if (com.oshi.messenger.data.model.PeerNicknameStore.record(
                    context, senderPublicKey,
                    com.oshi.messenger.data.model.PeerNickname.read(json)
                )
            ) {
                Log.d(TAG, "🏷️ Shared nickname updated for ${senderPublicKey.take(8)}...")
            }

            // __PROFILE_BIO_2026_11_02__ (N4) the peer's own bio and accent
            // colour. Like the nickname above, this stores nothing as a message
            // row and raises no notification: it is profile state, not traffic.
            if (com.oshi.messenger.data.model.PeerBioStore.record(
                    context, senderPublicKey,
                    com.oshi.messenger.data.model.ProfileBioWire.read(json),
                    com.oshi.messenger.data.model.ProfileBioWire.readColor(json),
                )
            ) {
                Log.d(TAG, "📝 Profile bio/colour updated for ${senderPublicKey.take(8)}...")
            }

            val profileBase64 = json.optString("profileImageData", null)

            // 🔴 SIZE GATE. There was none, and the asymmetry was the bug: OUTBOUND
            // avatars are compressed to 200x200 JPEG q50 by `compressProfileImage`, but
            // INBOUND ones were trusted to have been compressed by the peer and written
            // straight into `contacts.profilePicture`.
            //
            // Room stores a ByteArray as base64 TEXT, so a 1.6 MB avatar becomes a
            // ~2.1 MB row and blows the ~2 MB CursorWindow. `ContactDao.getAllContacts()`
            // is `SELECT *` with no LIMIT and feeds the conversation list, which is
            // collected with no `.catch` — so the throw lands on app start, every start.
            // Worse, the open-time repair in AppDatabase scrubs `messages.mediaData`
            // ONLY; there is no equivalent for `contacts`. One oversized avatar from any
            // peer would have bricked the app permanently, recoverable only by clearing
            // app data — which destroys the identity.
            //
            // The cap is generous next to what we ourselves send (a 200x200 JPEG is a few
            // KB) and far below the CursorWindow, so a legitimate avatar can never trip it.
            val profileData = profileBase64?.takeIf { it.isNotEmpty() && it != "null" }
                ?.takeIf { encoded ->
                    // Check the ENCODED length first: decoding is what allocates.
                    val ok = encoded.length <= MAX_INBOUND_AVATAR_B64_CHARS
                    if (!ok) {
                        Log.w(
                            TAG,
                            "🚫 Avatar from ${senderPublicKey.take(8)}… is ${encoded.length} b64 chars " +
                                "(cap $MAX_INBOUND_AVATAR_B64_CHARS) — refused, it would not fit a CursorWindow"
                        )
                    }
                    ok
                }
                ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                ?.takeIf { decoded ->
                    val ok = decoded.size <= MAX_INBOUND_AVATAR_BYTES
                    if (!ok) Log.w(TAG, "🚫 Avatar from ${senderPublicKey.take(8)}… is ${decoded.size} B — refused")
                    ok
                }

            // Update contact's profile picture.
            //
            // 🔑 Through [writeProfilePicture], NOT `contactDao` directly. The row is
            // stored under `canonicalIdentity(key)` while `senderPublicKey` is the RAW
            // wire key; writing the raw key updated ZERO rows whenever the peer's key
            // was base64url or unpadded, and the log below still said "Updated". That
            // is the whole reason an iPhone's avatar never appeared on Android: the
            // request went out, iOS answered, Android decoded the bytes and dropped
            // them on the floor. Same lookup order as `requestProfileIfNeeded`.
            if (profileData != null) {
                val rows = writeProfilePicture(senderPublicKey, profileData)
                if (rows > 0) {
                    Log.d(TAG, "📸 Updated profile picture for ${senderPublicKey.take(8)}... (${profileData.size} bytes)")
                } else {
                    Log.w(TAG, "📸 Profile picture for ${senderPublicKey.take(8)}... matched NO contact row — dropped")
                }
            }

            // Update last seen. Same canonical-then-raw order, same reason.
            val lastSeen = (json.optDouble("lastSeen", 0.0) * 1000).toLong()
            if (lastSeen > 0) {
                val canonical = canonicalIdentity(senderPublicKey)
                if (contactDao.updateLastSeen(canonical, lastSeen) == 0 && canonical != senderPublicKey) {
                    contactDao.updateLastSeen(senderPublicKey, lastSeen)
                }
            }

            Log.d(TAG, "✅ Profile update processed for ${senderPublicKey.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle profile update: ${e.message}")
        }
    }

    /**
     * 📸 Broadcast profile update to all contacts
     */
    suspend fun broadcastProfileToAllContacts() {
        val contacts = contactDao.getAllContactsList()
        Log.d(TAG, "📸 Broadcasting profile to ${contacts.size} contacts")

        contacts.forEach { contact ->
            sendProfileUpdate(contact.publicKey)
        }
    }

    // ============================================================================
    // 🛡️ CHECK-IN / ACCOMPAGNEMENT (iOS-compatible)
    // ============================================================================

    /**
     * 🛡️ Handle an incoming check-in message from a contact.
     *
     * 🔴 FIX (2026-08-24 three-gaps). This used to hand the payload to
     * `CheckInManager` **and return without touching the database**, from all four
     * ingest funnels (mesh, VPS, V2, cross-platform mesh). `CheckInManager`'s
     * handler was itself seven `Log` branches, so an iPhone user's real emergency
     * alert arriving on an Android phone produced a logcat line and **nothing
     * else**: no notification, no bubble, no conversation-list preview.
     *
     * Both halves are now real, and they are separate on purpose:
     *   * the NOTIFICATION is raised by `CheckInManager` (which owns the
     *     safety-alerts channel and the DND bypass);
     *   * the ROW is inserted here, so `CheckInBubble` (`ChatScreen.kt`) and the
     *     `🛡️ Emergency Alert` conversation-row preview
     *     (`ConversationsScreen.formatPreviewText`) — both of which have always
     *     rendered from the DB and were therefore unreachable — light up.
     * A notification with no row behind it would be a banner the user cannot open;
     * a row with no notification is one they never learn about.
     *
     * The `update` type is inserted like the rest: iOS shows no BANNER for it
     * (`OSHI/NotificationManager.swift:300-306`) but its own sender sends it with
     * `skipLocalMessage: true`, meaning the position pings are suppressed at the
     * SOURCE, not at the sink. Android's outbound `update` is likewise not sent
     * (see `CheckInManager.dispatch`), so nothing floods this path today; dropping
     * an inbound one from an iPhone would instead lose the only trace of a live
     * journey.
     *
     * Insert is idempotent on `messageId` — the same guard `handleLocationMessage`
     * uses — because a mesh message can legitimately reach two funnels.
     */
    private suspend fun handleCheckInMessage(
        messageId: String,
        senderPublicKey: String,
        decrypted: String,
        deliveryMethod: DeliveryMethod
    ) {
        try {
            val context = com.oshi.messenger.OSHIApplication.instance
            val checkInManager = com.oshi.messenger.service.CheckInManager.getInstance(context)
            checkInManager.processIncomingCheckInMessage(senderPublicKey, decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to notify for check-in message: ${e.message}")
        }
        try {
            if (messageDao.getMessageById(messageId) != null) return
            // __CHECKIN_UPDATE_MERGE_2026_09_27__ An iPhone sends an `update` every 5
            // minutes during a trip and REPLACES its previous one in place
            // (`MessageManager.swift:7359-7386`); Android stacked a bubble per update.
            // Same rule here: rewrite the session's latest row, fall back to a new row.
            val checkIn = com.oshi.messenger.data.model.CheckInMessage.fromMessageString(decrypted)
            if (checkIn != null &&
                checkIn.type == com.oshi.messenger.data.model.CheckInMessageType.update &&
                checkIn.sessionId.isNotBlank()
            ) {
                val prefix = decrypted.substringBefore('{')
                val existing = messageDao.findCheckInBySessionId(
                    canonicalIdentity(senderPublicKey), prefix, checkIn.sessionId
                )
                if (existing != null) {
                    messageDao.updateContent(existing.id, decrypted, System.currentTimeMillis())
                    Log.d(TAG, "\uD83D\uDEE1\uFE0F Check-In update merged into ${existing.id.take(8)}")
                    return
                }
            }
            messageDao.insert(
                Message(
                    id = messageId,
                    senderPublicKey = canonicalIdentity(senderPublicKey),   // 🔑 stored spelling
                    recipientPublicKey = myPublicKey,
                    // RAW: `CheckInBubble` re-parses the payload with
                    // `CheckInMessage.fromMessageString`, exactly as the location
                    // bubble does. A sanitised string has no payload left.
                    content = decrypted,
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    deliveryMethod = deliveryMethod
                )
            )
            noteBlockedByPeer(senderPublicKey, null)
            ensureContactExists(senderPublicKey)
            Log.d(TAG, "\uD83D\uDEE1\uFE0F Check-In from ${senderPublicKey.take(8)}... landed in the thread")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to persist check-in message: ${e.message}")
        }
    }

    // ============================================================================
    // 🎨 WALLPAPER SYNC (iOS-compatible)
    // ============================================================================

    // Wallpaper storage (conversationId -> wallpaper data)
    private val _wallpapers = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val wallpapers: StateFlow<Map<String, ByteArray>> = _wallpapers.asStateFlow()

    // Wallpaper change notifications
    private val _wallpaperChanges = MutableSharedFlow<WallpaperChange>()
    val wallpaperChanges: SharedFlow<WallpaperChange> = _wallpaperChanges.asSharedFlow()

    data class WallpaperChange(
        val conversationId: String,
        val senderPublicKey: String,
        val senderName: String?,
        val wallpaperData: ByteArray
    )

    /**
     * 🎨 Handle incoming wallpaper update from contact
     */
    private suspend fun handleWallpaperUpdate(senderPublicKey: String, decrypted: String) {
        try {
            val jsonString = decrypted.removePrefix("🎨WALLPAPER_UPDATE🎨")
            val json = org.json.JSONObject(jsonString)

            // 🔧 FIX: Always use senderPublicKey as our conversationId
            val conversationId = senderPublicKey
            val wallpaperBase64 = json.optString("wallpaperData", null)
            val senderName = json.optString("senderName", null)
            val customImageBase64 = json.optString("customImageBase64", null)

            // If custom image data is included (from iOS), use that as wallpaper data
            val wallpaperData = if (!customImageBase64.isNullOrEmpty() && customImageBase64 != "null") {
                // Custom image — store raw image bytes (JPEG) for direct display
                android.util.Base64.decode(customImageBase64, android.util.Base64.NO_WRAP)
            } else {
                wallpaperBase64?.takeIf { it.isNotEmpty() && it != "null" }?.let {
                    android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                }
            }

            if (wallpaperData != null) {
                // Update wallpaper map
                val currentWallpapers = _wallpapers.value.toMutableMap()
                currentWallpapers[conversationId] = wallpaperData
                _wallpapers.value = currentWallpapers

                // Emit change notification
                _wallpaperChanges.emit(
                    WallpaperChange(
                        conversationId = conversationId,
                        senderPublicKey = senderPublicKey,
                        senderName = senderName,
                        wallpaperData = wallpaperData
                    )
                )

                Log.d(TAG, "🎨 Updated wallpaper for conversation ${conversationId.take(8)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle wallpaper update: ${e.message}")
        }
    }

    /**
     * 🎨 Send wallpaper update to a conversation participant
     */
    suspend fun sendWallpaperUpdate(
        recipientPublicKey: String,
        conversationId: String,
        wallpaperData: ByteArray,
        senderName: String?,
        customImageBase64: String? = null
    ) {
        try {
            val wallpaperBase64 = android.util.Base64.encodeToString(wallpaperData, android.util.Base64.NO_WRAP)

            val imageField = if (customImageBase64 != null) {
                ""","customImageBase64":"$customImageBase64""""
            } else ""

            val payload = """{"type":"wallpaper_update","conversationId":"$conversationId","wallpaperData":"$wallpaperBase64","timestamp":${System.currentTimeMillis() / 1000.0},"senderName":"${senderName ?: ""}","version":1$imageField}"""

            val content = "🎨WALLPAPER_UPDATE🎨$payload"
            val encrypted = encryptWithDoubleRatchet(content, recipientPublicKey)

            vpsClient.sendMessage(
                recipientPublicKey, encrypted, UUID.randomUUID().toString(),
                suppressPush = isSilentControlContent(content)   // 🔕 protocol traffic
            )
            Log.d(TAG, "🎨 Wallpaper update sent to ${recipientPublicKey.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send wallpaper update: ${e.message}")
        }
    }

    /**
     * 🎨 Get wallpaper for a conversation
     */
    fun getWallpaper(conversationId: String): ByteArray? {
        return _wallpapers.value[conversationId]
    }

    /**
     * 🎨 Set wallpaper locally (and optionally broadcast)
     */
    suspend fun setWallpaper(
        conversationId: String,
        wallpaperData: ByteArray,
        recipientPublicKey: String,
        senderName: String?,
        broadcast: Boolean = true,
        syncWallpaperJson: ByteArray? = null,
        customImageBase64: String? = null
    ) {
        // Update local storage
        val currentWallpapers = _wallpapers.value.toMutableMap()
        currentWallpapers[conversationId] = wallpaperData
        _wallpapers.value = currentWallpapers

        // Broadcast to participant if requested
        if (broadcast) {
            // For sync, send the JSON format (not raw image bytes)
            val dataToSend = syncWallpaperJson ?: wallpaperData
            sendWallpaperUpdate(recipientPublicKey, conversationId, dataToSend, senderName, customImageBase64)
        }

        Log.d(TAG, "🎨 Wallpaper set for conversation ${conversationId.take(8)}...")
    }

    // ============================================================================
    // CONTACTS
    // ============================================================================

    suspend fun addContact(publicKey: String, alias: String) {
        // 🪦 Auto-stale on alias collision: if any OTHER existing contact
        // already uses this alias (case-insensitive, trimmed), the user is
        // re-adding the same person under a NEW public key — they rotated.
        // Mark every prior key for that alias as stale so the OLD keys stop
        // ringing the phone and stop delivering group messages addressed
        // to the new identity.
        val normalizedNew = alias.trim().lowercase()
        if (normalizedNew.isNotEmpty()) {
            try {
                val existing = contactDao.getAllContactsList()
                for (c in existing) {
                    if (c.publicKey == publicKey) continue
                    if (c.alias.trim().lowercase() == normalizedNew) {
                        Log.w(TAG, "🪦 Alias collision on '$alias' — marking previous key stale: ${c.publicKey.take(12)}…")
                        com.oshi.messenger.service.StaleIdentityStore.get(context).markStale(
                            publicKey = c.publicKey,
                            displayName = c.alias,
                            reason = com.oshi.messenger.service.StaleIdentityStore.REASON_QR_REPLACEMENT,
                            replacedBy = publicKey
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stale-on-add scan failed: ${e.message}")
            }
        }

        val contact = Contact(
            publicKey = publicKey,
            alias = alias
        )
        contactDao.insert(contact)
        // __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18) listed straight
        // away, not after the first message.
        com.oshi.messenger.data.model.SavedContactThreads.get(context).set(publicKey, true)
        Log.d(TAG, "👤 Added contact: $alias")
        // __RATING_THREE_MOMENTS_2026_11_15__ "First contact added" is no longer a
        // rating moment: the three moments are the welcome reply, the first
        // delivery receipt / completed call, and ten messages sent.
    }

    /**
     * __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18) Save [publicKey] as a
     * contact if it is not one yet, on the repository's own scope: the caller is a
     * screen that navigates away in the same frame, and a ViewModel scope would be
     * cancelled with it before the insert ran.
     */
    fun saveContactIfMissing(publicKey: String) {
        if (publicKey.isBlank()) return
        scope.launch {
            runCatching {
                val canonical = canonicalIdentity(publicKey)
                val existing = contactDao.getContactByPublicKey(canonical)
                    ?: contactDao.getContactByPublicKey(publicKey)
                if (existing == null) {
                    addContact(canonical, canonical.take(8) + "...")
                } else {
                    com.oshi.messenger.data.model.SavedContactThreads.get(context)
                        .set(existing.publicKey, true)
                }
            }.onFailure { Log.w(TAG, "saveContactIfMissing failed: ${it.message}") }
        }
    }

    suspend fun updateContactAlias(publicKey: String, alias: String) {
        // 🪦 Same auto-stale check on rename — if a user renames an existing
        // contact to a name another contact already uses, that's effectively
        // claiming the renamed key replaces the previously-named one.
        val normalizedNew = alias.trim().lowercase()
        if (normalizedNew.isNotEmpty()) {
            try {
                val existing = contactDao.getAllContactsList()
                for (c in existing) {
                    if (c.publicKey == publicKey) continue
                    if (c.alias.trim().lowercase() == normalizedNew) {
                        com.oshi.messenger.service.StaleIdentityStore.get(context).markStale(
                            publicKey = c.publicKey,
                            displayName = c.alias,
                            reason = com.oshi.messenger.service.StaleIdentityStore.REASON_QR_REPLACEMENT,
                            replacedBy = publicKey
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18) Naming someone
        // IS saving them. A chat opened from a scanned QR code has no contact row
        // until the first message, and `UPDATE contacts` on a missing row changes
        // nothing — so the name the user typed was silently dropped and the
        // person stayed out of the list. Create the row in that case.
        val hasRow = contactDao.getContactByPublicKey(publicKey) != null
        if (!hasRow && alias.isNotBlank()) {
            contactDao.insert(Contact(publicKey = publicKey, alias = alias))
            com.oshi.messenger.data.model.SavedContactThreads.get(context).set(publicKey, true)
        } else {
            contactDao.updateAlias(publicKey, alias)
        }
        // Refresh group message sender names to reflect updated alias immediately
        scope.launch { groupManager.refreshAllSenderNames() }
    }

    /**
     * Public manual entry point — user tapped "Mark old identity" in the
     * contact detail sheet. Adds this contact's current public key to the
     * stale set so future inbound calls / messages signed by this key are
     * silently dropped. The contact row stays in DB (we don't auto-delete);
     * the user can clean it up themselves.
     */
    suspend fun markContactIdentityStale(publicKey: String) {
        val contact = contactDao.getContactByPublicKey(publicKey)
        com.oshi.messenger.service.StaleIdentityStore.get(context).markStale(
            publicKey = publicKey,
            displayName = contact?.alias,
            reason = com.oshi.messenger.service.StaleIdentityStore.REASON_MANUAL
        )
    }

    suspend fun blockContact(publicKey: String) {
        contactDao.setBlocked(publicKey, true)
        // __BLOCKED_NOTIF_2026_09_23__ the block must outlive the contact row.
        val name = try { contactDao.getContactByPublicKey(publicKey)?.alias } catch (_: Exception) { null }
        com.oshi.messenger.service.BlockedKeysStore.get(context).block(publicKey, name)
        blockedKeyCache.add(normalizeBase64Key(publicKey))
        Log.d(TAG, "🚫 Blocked ${publicKey.take(8)}...")
    }

    suspend fun unblockContact(publicKey: String) {
        contactDao.setBlocked(publicKey, false)
        // __BLOCK_SYNC_LWW_2026_09_24__ every spelling of the row too: a row still flagged under
        // another spelling was re-migrated into the store by refreshBlockedKeyCache (= re-blocked).
        clearBlockedRowsEverySpelling(publicKey)
        // Tombstone with the unblock time: devsync exports it as blocked:false + blockedAt.
        com.oshi.messenger.service.BlockedKeysStore.get(context).unblock(publicKey)
        blockedKeyCache.remove(normalizeBase64Key(publicKey))
        Log.d(TAG, "✅ Unblocked ${publicKey.take(8)}...")
    }

    /** Clears `isBlocked` on every contact row whose key is a spelling of [publicKey]. */
    suspend fun clearBlockedRowsEverySpelling(publicKey: String) {
        val n = normalizeBase64Key(publicKey)
        try {
            contactDao.getBlockedContacts().first()
                .filter { normalizeBase64Key(it.publicKey) == n }
                .forEach { contactDao.setBlocked(it.publicKey, false) }
        } catch (e: Exception) {
            Log.w(TAG, "unblock rows: ${e.message}")
        }
    }

    /** __BLOCKED_BY_PEER_2026_09_24__ an inbound 1:1 row was stored: does the peer still take our traffic? */
    private fun noteBlockedByPeer(sender: String, storedText: String?) {
        runCatching { com.oshi.messenger.service.BlockedByPeerStore.get(context).observeStoredInbound(sender, storedText) }
    }

    private fun isBlockedNotice(text: String?): Boolean =
        text != null && OSHIControlPayload.matchedPrefix(text) == OSHIControlPayload.BLOCKED_NOTICE

    /**
     * Is this contact blocked right now? Reads persisted state, not a screen flag.
     *
     * PUBLIC front door for every out-of-repository ingress (`FCMService`,
     * `EnhancedCallManager`, `IncomingCallService`, `ScheduledCallManager`). It goes
     * through the same NORMALIZED comparison the in-repository receive paths use, so a
     * push whose `senderPublicKey` is spelled base64url (or padded differently) than the
     * stored contact row still matches the block. iOS compares the raw key at
     * `VoiceCallManager.swift:6266` and only normalizes afterwards at `:6287` — that
     * asymmetry is an iOS bug, not a wire contract, so it is deliberately not copied.
     */
    suspend fun isContactBlocked(publicKey: String): Boolean = isBlockedSender(publicKey)

    /** Exact-key DB read — the cold-cache fallback behind [isBlockedSender]. */
    private suspend fun isBlockedInDb(publicKey: String): Boolean =
        com.oshi.messenger.service.BlockedKeysStore.get(context).isBlocked(publicKey) ||
            try { contactDao.getContactByPublicKey(publicKey)?.isBlocked == true } catch (_: Exception) { false }

    /**
     * Hot cache of blocked keys, kept in normalized form.
     *
     * Every inbound handler consults this BEFORE doing decryption work, so it has to be
     * cheap and synchronous; a DB round-trip per inbound message on the poll path is
     * not. Seeded in [refreshBlockedKeyCache] at start-up and kept in step by
     * [blockContact]/[unblockContact]. A cold cache falls back to the DB read in
     * [isBlockedSender], so a miss is never a silent delivery.
     */
    private val blockedKeyCache = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var blockedCacheSeeded = false

    fun refreshBlockedKeyCache() {
        scope.launch {
            try {
                val rows = contactDao.getBlockedContacts().first()
                // __BLOCKED_NOTIF_2026_09_23__ migrate: every legacy `isBlocked` row is
                // copied into the durable list once, so deleting the row later no
                // longer unblocks. The cache is the union of both.
                val store = com.oshi.messenger.service.BlockedKeysStore.get(context)
                // __BLOCK_SYNC_LWW_2026_09_24__ untimestamped (0): a legacy flag is not a block made
                // "now", and stamping it would let it beat an unblock made earlier on another device.
                rows.forEach { if (!store.isBlocked(it.publicKey)) store.blockAt(it.publicKey, it.alias, 0L) }
                val keys = (rows.map { it.publicKey } + store.entries.value.map { it.publicKey })
                    .map { normalizeBase64Key(it) }.toSet()
                synchronized(blockedKeyCache) {
                    blockedKeyCache.clear()
                    blockedKeyCache.addAll(keys)
                }
                blockedCacheSeeded = true
                if (keys.isNotEmpty()) Log.d(TAG, "🚫 ${keys.size} blocked contact(s) loaded")
            } catch (e: Exception) {
                Log.w(TAG, "Could not seed blocked cache: ${e.message}")
            }
        }
    }

    /**
     * 🚫 Block gate for the receive path.
     *
     * 🔧 P0 FIX (2026-08-13 chat-1to1 audit #3). Blocking used to be an in-memory
     * boolean in `ChatViewModel` that reset on every screen entry;
     * `MessageRepository.blockContact()` had ZERO callers, so nothing was ever
     * persisted, and no inbound handler checked `isBlocked` — a blocked contact still
     * delivered, still rang, still notified. iOS enforces the block at nine receive/ring
     * sites (`MessageManager.swift:5390,7858,7911`, `PushNotificationManager.swift:99,180`,
     * `VoiceCallManager.swift:5534,6266`, `VoIPPushManager.swift:1301`,
     * `ScheduledCallManager.swift:208`).
     *
     * Comparison is on the NORMALIZED key (base64 vs base64url, padding) — the same
     * equivalence `getConversations()` uses — so a block cannot be evaded by re-sending
     * under a differently-padded spelling of the same key.
     */
    private suspend fun isBlockedSender(publicKey: String): Boolean {
        if (publicKey.isEmpty()) return false
        val norm = normalizeBase64Key(publicKey)
        if (synchronized(blockedKeyCache) { blockedKeyCache.contains(norm) }) return true
        if (blockedCacheSeeded) return false
        // Cache not warm yet — pay for the read rather than deliver a blocked message.
        return isBlockedInDb(publicKey)
    }

    /**
     * __BLOCKED_NOTICE_2026_09_18__ Tell a blocked sender that nothing they send
     * here arrives any more.
     *
     * Blocking used to be entirely silent: the message left, the sender watched
     * two grey ticks that never turned, and they learned nothing. The owner chose
     * the opposite — say it — so this is the one place that says it.
     *
     * Three rules, and each of them is load-bearing:
     *
     *  1. **Never answer machinery.** [OSHIControlPayload.suppressesPush] is the
     *     test, reused rather than re-listed: traffic not worth waking a person
     *     for is not worth answering either. It covers every receipt and typing
     *     indicator, the periodic profile/wallpaper broadcasts — and the notice
     *     itself, which is what stops two people who have blocked each other from
     *     pinging one another forever.
     *
     *  2. **Once per peer per day.** Without a limit, every message a blocked
     *     spammer sends draws a reply, and those replies are a heartbeat: they
     *     tell the sender exactly when the person who blocked them has their
     *     phone on and online. One notice says the thing; the rest only leak.
     *
     *  3. **Sent around [sendTextMessage], not through it.** That gate now
     *     refuses every send to a blocked key — correctly — so the notice has to
     *     go out on the transports directly, and it is the sole intended
     *     exception. Push is suppressed: the person is holding their phone,
     *     looking at the thread they just sent into, so the row is enough, and
     *     an alert reading "you have been blocked" is a push nobody needs.
     */
    private suspend fun noticeBlockedSender(senderPublicKey: String, plaintext: String?) {
        if (!BLOCKED_NOTICE_ENABLED) return
        // A LoRa pseudo-identity (`lora!<nodehex>`) has no key to encrypt to and no
        // route back; an empty key has neither.
        if (senderPublicKey.isEmpty() || senderPublicKey.startsWith("lora!")) return
        if (senderPublicKey == myPublicKey) return
        if (plaintext != null && OSHIControlPayload.suppressesPush(plaintext)) return

        val prefs = context.getSharedPreferences("blocked_notice", Context.MODE_PRIVATE)
        val key = normalizeBase64Key(senderPublicKey)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(key, 0L)
        if (now - last < BLOCKED_NOTICE_INTERVAL_MS) return
        // Written BEFORE the send, so a transport that hangs or throws cannot
        // leave the door open for a second notice on the very next message.
        prefs.edit().putLong(key, now).apply()

        try {
            // Both sites name the payload rather than share a local: what a send
            // CARRIES is the thing every wiring test in this module reads off the
            // call site, and a variable hides it from them.
            val sentViaV2 = try {
                v2MessageRouter.sendText(
                    senderPublicKey,
                    OSHIControlPayload.BLOCKED_NOTICE.toByteArray(Charsets.UTF_8),
                    UUID.randomUUID().toString(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "🚫 V2 blocked-notice threw: ${e.message}")
                false
            }
            if (!sentViaV2) {
                val encrypted = encryptWithDoubleRatchet(
                    OSHIControlPayload.BLOCKED_NOTICE, senderPublicKey)
                vpsClient.sendMessage(
                    senderPublicKey, encrypted, UUID.randomUUID().toString(),
                    suppressPush = true,   // 🔕 see rule 3
                )
            }
            Log.d(TAG, "🚫 Told ${senderPublicKey.take(8)}… they are blocked")
        } catch (e: Exception) {
            // Best effort. A notice that cannot go out changes nothing about the
            // block itself, which has already dropped the message.
            Log.w(TAG, "🚫 Could not send blocked-notice: ${e.message}")
        }
    }

    /**
     * 🔑 The contact row's `publicKey` IS the parameter every thread query is run
     * with (`getMessagesForContact`, `getUnreadCountForContact`, `markAsRead` — all
     * strict SQL `=`), so it has to be spelled the same way the stored rows are.
     * Creating it raw while the messages are stamped canonical would just move the
     * empty-thread bug rather than fix it.
     *
     * A row that already exists in EITHER spelling is left alone: the one-shot
     * [canonicalizeIdentitiesOnce] pass converges the old ones, and inserting a
     * second row here would be the duplicate-conversation bug all over again.
     */
    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ Insert a message THIS account sent from another device as an
     * outgoing row. 1:1 only (Android sends no V2 group traffic, and a group copy needs the group
     * funnel — devsync converges those). Dedupe by msgId: devsync may bring the same row later.
     */
    private suspend fun ingestSentCopyFromOwnDevice(copy: com.oshi.messenger.network.v2.devicemailbox.SentCopy, envTs: Long) {
        // __GROUP_E2E_V2_2026_09_23__ a group message sent from another of my devices (spec §7.2).
        copy.groupId?.let { gid ->
            val ts = runCatching { java.time.Instant.parse(copy.sentAt).toEpochMilli() }.getOrNull() ?: envTs
            groupManager.ingestOwnGroupSentCopy(gid, copy.message, ts)
            return
        }
        val peer = copy.peer ?: return
        if (peer == myPublicKey || isBlockedSender(peer)) return
        if (messageDao.getMessageById(copy.msgId) != null) return
        if (com.oshi.messenger.data.model.OSHIControlPayload.isSilent(copy.message)) return
        val ts = runCatching { java.time.Instant.parse(copy.sentAt).toEpochMilli() }.getOrNull() ?: envTs
        ensureContactExists(peer)
        val row = Message(
            id = copy.msgId,
            senderPublicKey = myPublicKey,
            recipientPublicKey = peer,
            content = copy.message,
            timestamp = ts,
            isFromMe = true,
            isDelivered = true,
            isRead = false,
        )
        messageDao.insert(sanitizeMediaForInsert(row))
        notifyWebSession(row)
    }

    /**
     * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ Put a message on a LoRa channel.
     *
     * The radio is the ONLY route. A channel has no public key, no ratchet and no
     * relay mailbox, so there is nothing to fall back to and pretending otherwise
     * would leave a bubble on screen that could never be delivered. If no radio is
     * connected the send is refused, with the same message the LoRa screen uses.
     *
     * The stored row mirrors an inbound one so both sides of the room read the
     * same way: addressed to the channel, authored by us.
     *
     * ⚠️ Stamped [DeliveryMethod.LORA_PLAINTEXT], never `LORA`. This goes out on
     * a channel whose key is public, in the clear as far as anyone in radio range
     * is concerned. `LORA` means an OSHI envelope the ratchet authenticated, and
     * drawing this as though it were that would tell the user their message was
     * private when every radio nearby can read it.
     */
    private suspend fun sendToLoRaChannel(channelKey: String, content: String): Result<Message> {
        val radio = runCatching { MeshtasticManager.getInstance(context) }.getOrNull()
        if (radio == null || !radio.isReady) {
            return Result.failure(
                IllegalStateException(context.getString(R.string.lora_error_not_connected))
            )
        }
        // __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ WHICH channel.
        //
        // This used to be a bare `radio.sendText(content)`. `MeshPacket.channel`
        // (field 3) was never written by the packet builder at all, so every
        // room's text went out on slot 0: the primary, normally LongFast,
        // normally public. Typing in a private channel's room broadcast it on
        // the public one. Resolved BEFORE the row is inserted, so a refusal does
        // not leave a bubble behind claiming the room heard it.
        val slot = LoRaChannelThread.channelIndexFor(channelKey, radio.nodeChannels.value)
        if (slot == null) {
            Log.w(TAG, "⛔️ LoRa channel ${channelKey.take(20)}… is not on the connected radio")
            return Result.failure(
                IllegalStateException(context.getString(R.string.lora_error_channel_unknown))
            )
        }
        val message = Message(
            id = UUID.randomUUID().toString(),
            senderPublicKey = myPublicKey,
            recipientPublicKey = channelKey,
            content = content,
            timestamp = System.currentTimeMillis(),
            isFromMe = true,
            // No acknowledgement exists on a broadcast: nobody is addressed, so
            // nobody answers. Marking it delivered would be inventing a receipt.
            isDelivered = false,
            deliveryMethod = DeliveryMethod.LORA_PLAINTEXT,
        )
        messageDao.insert(message)
        ensureChannelContactExists(channelKey)
        return runCatching {
            radio.sendText(content, channel = slot)   // broadcast, on the room's own slot
            message
        }.onFailure { markSendFailed(message.id) }
    }

    /**
     * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ The contact row that makes a channel
     * appear in the conversation list.
     *
     * `getConversations` is built from CONTACTS, not from messages, so a room
     * with no contact row has messages and no way to reach them. Separate from
     * [ensureContactExists] because the default alias there is
     * `publicKey.take(8) + "..."`, which for a channel would read
     * "lorachan..." instead of its name.
     *
     * The alias is refreshed on every arrival, not only on insert: a channel
     * first heard as the unnamed primary becomes "LongFast", and a user who
     * renames their channel should see the room rename rather than keep a stale
     * label for ever.
     */
    private suspend fun ensureChannelContactExists(channelKey: String) {
        val name = LoRaChannelThread.displayName(channelKey)
        val existing = contactDao.getContactByPublicKey(channelKey)
        if (existing == null) {
            contactDao.insert(Contact(publicKey = channelKey, alias = name))
        } else if (existing.alias != name) {
            contactDao.update(existing.copy(alias = name))
        }
    }

    private suspend fun ensureContactExists(publicKey: String) {
        val canonical = canonicalIdentity(publicKey)
        if (contactDao.getContactByPublicKey(canonical) != null) return
        if (canonical != publicKey && contactDao.getContactByPublicKey(publicKey) != null) return
        contactDao.insert(
            Contact(
                publicKey = canonical,
                alias = canonical.take(8) + "..."
            )
        )
    }

    fun getContact(publicKey: String): Flow<Contact?> {
        return contactDao.getContactFlow(publicKey)
    }

    fun getAllContacts(): Flow<List<Contact>> {
        return contactDao.getAllContacts()
    }

    /** One-shot list of contact public keys for broadcasts (e.g. profile-links
     *  visibility). Skips Flow because callers want a snapshot, not a subscription. */
    suspend fun getAllContactsListSnapshot(): List<String> {
        return contactDao.getAllContactsList().map { it.publicKey }
    }

    // ============================================================================
    // READ STATUS & RECEIPTS
    // ============================================================================

    /**
     * Mark every incoming row of this thread read, dismiss its banner, and tell the
     * sender.
     *
     * 🔧 2026-08-24 — READ STATE. This used to be called from exactly one place,
     * `ChatViewModel.loadConversation`, i.e. only when the screen was ENTERED. A
     * message that arrived into an already-open chat therefore stayed pending until
     * the user left and came back. It now also runs on ARRIVAL, from the unread
     * watcher in `ChatViewModel.startReadWatch`, gated on
     * [com.oshi.messenger.service.ActiveConversation.isDirectActive] — the Android
     * equivalent of iOS's `if isVisible { markMessagesAsRead() }`
     * (`OSHI/ChatView.swift:863-866`).
     *
     * Two consequences of that new call frequency are handled here:
     *
     *  1. **The receipt is gated on real change.** `markAsRead` returns the number
     *     of rows that flipped (`AppDatabase.kt`), and nothing is sent when it is
     *     zero. iOS states the same rule in as many words
     *     (`OSHI/MessageManager.swift:7406`, "Only fires when something actually
     *     flipped to read, so opening an already-read chat sends nothing"). Without
     *     this, every re-emission of the thread's Room flow would put another
     *     `📖READ_RECEIPT📖` on the wire.
     *  2. **The banner is dismissed.** If a message slipped into the shade a moment
     *     before the screen resumed, reading it must clear it — the user should
     *     never have to swipe away a notification for a message they are looking
     *     at. iOS does this at `MessageManager.swift:7411`
     *     (`clearDeliveredNotifications(forConversation:)`).
     *
     * ⚠️ Nothing here touches `isFromMe = 1` rows. Those carry the PEER's read state
     * and are what `getUnreadSentMessages` (`isFromMe = 1 AND isRead = 0`) selects;
     * an earlier bug that had outgoing rows born `isRead = true` silently disabled
     * real read receipts, and the `AND isFromMe = 0` in the UPDATE is what keeps
     * this path from being a second instance of that shape.
     */
    suspend fun markConversationAsRead(publicKey: String) {
        val flipped = messageDao.markAsRead(publicKey)
        if (flipped <= 0) return

        // The message is on screen — its banner has no job left.
        dismissMessageNotification(publicKey)

        // Send read receipt to the sender so their badges update
        sendReadReceipt(publicKey)
    }

    /**
     * Cancel the shade entry for a 1:1 thread.
     *
     * Both posting sites derive the notification id from the sender key's
     * `hashCode()` — `FCMService.showNotification` from the PUSH spelling
     * (`extras["senderKey"]`) and [showMessageNotification] from the spelling the
     * transport handed us — and those two can differ by base64 padding for the same
     * person. Cancelling both hashes is cheaper than leaving a stale banner behind;
     * `cancel` on an id with nothing posted is a no-op.
     */
    fun dismissMessageNotification(publicKey: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // __ONE_BANNER_PER_MESSAGE_2026_09_23__ both posting sites now use this id.
            nm.cancel(com.oshi.messenger.service.MessageBanner.contactNotificationId(publicKey))
            nm.cancel(publicKey.hashCode())  // banners posted by builds before the shared id
            val canonical = canonicalIdentity(publicKey)
            if (canonical != publicKey) nm.cancel(canonical.hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "Could not dismiss notification for thread: ${e.message}")
        }
    }

    /**
     * 📬 Send delivery receipt back to sender when we receive their message
     * This updates the sender's badge from single checkmark → double checkmark
     */
    /**
     * __CONTROL_OVER_V2_2026_10_28__ Send a control payload (receipt, typing, …) over V2
     * when the peer is reachable there, and over the legacy lane otherwise.
     *
     * ## Why this exists
     *
     * Receipts and typing were emitted on the LEGACY lane ONLY — `encryptWithDoubleRatchet`
     * straight into `vpsClient.sendMessage`, with no `v2MessageRouter` branch, while the
     * TEXT they describe has had one for months. A peer that speaks V2 and not the legacy
     * lane therefore received the message and never a tick or a typing ping.
     *
     * That is exactly the desktop client, by design: it implements V2 and deliberately does
     * not implement the legacy IPFS path (OSHI-Desktop PARITY.md row 0.23, "ipfs was the old
     * road"). Its PARITY rows 0.18/0.19 recorded the result as "RECEIVE-FROM-ANDROID IS
     * STRUCTURALLY DEAD … the codecs here are still right and still byte-tested; there is
     * simply nothing to feed them from an Android peer." The missing half was here.
     *
     * ## The order is not negotiable
     *
     * V2 is tried FIRST and the legacy encrypt happens only if it declines, because
     * `encryptWithDoubleRatchet` ADVANCES THE LEGACY RATCHET. Encrypting first "just in
     * case" and then also sending over V2 spends a ratchet step on a message that never
     * goes out and leaves a permanent gap in the receiver's skipped-key store — the same
     * trap the LoRa branch of [sendMessage] documents and avoids by carrying its envelope
     * forward.
     *
     * ## No push, on either lane
     *
     * [pushAfterV2Send] is deliberately NOT called: a receipt that buzzes a phone is the
     * defect the typing path already fixed once. The legacy fallback keeps its existing
     * `suppressPush` computed from the one catalog, [isSilentControlContent].
     *
     * ## Zero regression when V2 is off
     *
     * `sendText` returns false when V2 is disabled, the peer has no published bundle, or
     * the relay is unreachable — and then this does exactly what it did before, byte for
     * byte, on the same lane.
     *
     * @return which lane carried it, for the log line.
     */
    private suspend fun sendControlOverV2OrLegacy(peerPublicKey: String, content: String): String =
        com.oshi.messenger.network.v2.ControlLane.preferV2(
            v2 = {
                v2MessageRouter.sendText(
                    peerPublicKey, content.toByteArray(Charsets.UTF_8), UUID.randomUUID().toString(),
                )
            },
            legacy = {
                // Reached ONLY when V2 declined — see ControlLane.preferV2 on why this
                // encrypt must not happen speculatively.
                val encrypted = encryptWithDoubleRatchet(content, peerPublicKey)
                vpsClient.sendMessage(
                    peerPublicKey, encrypted, UUID.randomUUID().toString(),
                    suppressPush = isSilentControlContent(content)   // 🔕 protocol traffic
                )
            },
            onV2Error = { Log.w(TAG, "V2 control send threw, using legacy lane: ${it.message}") },
        ).name.lowercase()

    private fun sendDeliveryReceipt(messageId: String, senderPublicKey: String) {
        scope.launch {
            try {
                // iOS expects the exact form "📬DELIVERY_RECEIPT📬<bare messageId>" and
                // uses the trimmed remainder AS the id (MessageManager.swift:1400,1407).
                // The old JSON body made iOS strip the prefix, get "{...}" as the id,
                // fail to match, and fall back to marking the OLDEST unacked message
                // delivered — the wrong bubble on rapid multi-send. Send the bare id.
                val content = "$DELIVERY_RECEIPT_PREFIX$messageId"
                // __CONTROL_OVER_V2_2026_10_28__ V2 FIRST, legacy only as the fallback.
                // See [sendControlOverV2OrLegacy] for why the order is not negotiable.
                val lane = sendControlOverV2OrLegacy(senderPublicKey, content)
                Log.d(TAG, "📬 Delivery receipt sent for $messageId to ${senderPublicKey.take(8)}... via $lane")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send delivery receipt: ${e.message}")
            }
        }
    }

    /**
     * 📖 Send read receipt to sender when we open their conversation
     * This updates the sender's badge from gray double checkmark → blue double checkmark
     */
    private fun sendReadReceipt(senderPublicKey: String) {
        scope.launch {
            try {
                // Respect the Read Receipts privacy setting (previously ignored).
                if (!isEnabled(readReceiptsKey)) return@launch
                val json = JSONObject().apply {
                    put("senderPublicKey", senderPublicKey)
                    put("timestamp", (System.currentTimeMillis() / 1000.0) - 978307200.0)
                }
                val content = "$READ_RECEIPT_PREFIX$json"
                // __CONTROL_OVER_V2_2026_10_28__ see [sendControlOverV2OrLegacy].
                val lane = sendControlOverV2OrLegacy(senderPublicKey, content)
                Log.d(TAG, "📖 Read receipt sent to ${senderPublicKey.take(8)}... via $lane")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send read receipt: ${e.message}")
            }
        }
    }

    /**
     * 📬 Handle incoming delivery receipt — mark our sent message as delivered
     */
    private suspend fun handleDeliveryReceipt(decrypted: String, senderPublicKey: String) {
        // __BLOCKED_BY_PEER_2026_09_24__ a device that still blocks us never acks.
        runCatching { com.oshi.messenger.service.BlockedByPeerStore.get(context).clear(senderPublicKey) }
        try {
            val payload = decrypted.removePrefix(DELIVERY_RECEIPT_PREFIX).trim()
            // iOS and current Android send the bare messageId; older Android sent
            // {"messageId":...}. Accept both so receipts interoperate in every mix.
            val messageId = if (payload.startsWith("{")) {
                try { JSONObject(payload).getString("messageId") } catch (_: Exception) { payload }
            } else payload
            if (messageId.isEmpty()) return

            val message = messageDao.getMessageById(messageId)
            if (message != null && message.isFromMe && !message.isDelivered) {
                updateMessage(message.copy(isDelivered = true))
                Log.d(TAG, "📬 Message $messageId marked as delivered (receipt from ${senderPublicKey.take(8)}...)")
            }
            // __RATING_THREE_MOMENTS_2026_11_15__ ⭐️ Moment 2: the peer ACKED one of
            // our messages. Deliberately OUTSIDE the `!isDelivered` test: every
            // relay / mesh send already stamps `isDelivered = true` when the
            // transport accepts it (see `sendTextMessage`), so gating on that flag
            // meant a real receipt almost never reached this trigger. Self-limits
            // in AppReviewManager; the welcome author's receipts are ignored there.
            if (message != null && message.isFromMe) {
                AppReviewManager.trackMessageDelivered(context, senderPublicKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle delivery receipt: ${e.message}")
        }
    }

    /**
     * Stamp an already-persisted outgoing message as **not sent**.
     *
     * 🔧 P0 FIX (device walkthrough #2, 2026-08-13). Every send path inserts the
     * local row before it tries a transport, so a throw used to leave a row that was
     * indistinguishable from a delivered message — the walkthrough sent to a
     * malformed key, logcat printed `❌ Send message failed: bad base-64`, and the
     * conversation was created anyway with the bubble stamped ✓✓ Read.
     *
     * Setting `isFailed` (and clearing `isDelivered` / `isRead`) is what makes
     * `ChatScreen`'s existing red "Not delivered" + retry row appear. It is the
     * Android equivalent of iOS's `DeliveryStatus.failed`, which
     * `DeliveryMethodBadge.swift:54,74` renders as an
     * `exclamationmark.triangle.fill` + `delivery_status_failed` ("Failed").
     *
     * Deliberately swallows its own errors: this runs from a `catch`, and a DB
     * failure here must not mask the original send failure the caller is reporting.
     */
    /**
     * __COVER_FAIL_CLOSED_2026_11_18__ Keep [row] on this device, labelled "Waiting
     * for disguised connection" by the bubble, and retry it over the disguised (V2)
     * path only — see [CoverOutbox]. Never touches the legacy relay.
     */
    private suspend fun parkForCover(row: Message, recipientPublicKey: String, pushContent: String?, attempt: suspend () -> Boolean) {
        updateMessage(row.copy(isDelivered = false, isFailed = false))
        Log.w(TAG, "🕵️ Disguised path did not deliver ${row.id.take(8)} — kept on device, legacy relay NOT used (cover mode)")
        CoverOutbox.park(
            context, scope, row.id, attempt,
            onDelivered = {
                messageDao.getMessageById(row.id)?.let {
                    updateMessage(it.copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true, isFailed = false))
                    pushAfterV2Send(recipientPublicKey, row.id, pushContent)
                }
            },
            onGaveUp = { markSendFailed(row.id) },
        )
    }

    /**
     * __COVER_RESUME_AFTER_RESTART_2026_11_18__ A message the previous process had
     * parked for the disguised path: a plain 1:1 text less than an hour old, with
     * cover still ON, is sent again exactly as the "Retry" button does (same id,
     * same date — `resendOf`), which parks it again if the path is still down; the
     * relay de-duplicates by message id if the old attempt had in fact arrived.
     * Anything else (media, older, cover since switched OFF) is shown "Not delivered".
     */
    private suspend fun resumeOrFailParked(messageId: String) {
        val row = try { messageDao.getMessageById(messageId) } catch (_: Exception) { null } ?: return
        val resume = row.isFromMe && row.mediaType == null && row.content.isNotBlank() &&
            CoverOutbox.resumeAfterRestart(isCovertChannelEnabled(), isText = true, ageMs = System.currentTimeMillis() - row.timestamp)
        if (!resume) { markSendFailed(messageId); return }
        Log.d(TAG, "🕵️ Resuming disguised send of ${messageId.take(8)} after a restart")
        try {
            deleteMessage(messageId)
            sendTextMessage(row.recipientPublicKey, row.content, resendOf = row)
        } catch (e: Exception) {
            Log.w(TAG, "Resume of ${messageId.take(8)} failed: ${e.message}")
            markSendFailed(messageId)
        }
    }

    private suspend fun markSendFailed(messageId: String) {
        try {
            val existing = messageDao.getMessageById(messageId) ?: return
            updateMessage(
                existing.copy(isFailed = true, isDelivered = false, isRead = false)
            )
            Log.w(TAG, "⚠️ Message $messageId marked FAILED (never left the device)")
            // __RATING_THREE_MOMENTS_2026_11_15__ friction: no rating ask for 10 min.
            AppReviewManager.trackFriction(context)
        } catch (e: Exception) {
            Log.e(TAG, "Could not mark $messageId failed: ${e.message}")
        }
    }

    /**
     * 📖 Handle incoming read receipt — mark our sent messages to that contact as read
     */
    /**
     * 📑 __GROUP_RECEIPTS_ONLY_ARRIVED_BY_RELAY_2026_11_03__ (Anatole, N8 again)
     *
     * Someone telling us what they did with a GROUP message WE sent. Silent: it
     * is state about our own message, never a message in its own right.
     *
     * This logic used to live INLINE in `handleVPSMessage` and nowhere else,
     * which is why "info de lecture" was reported as still broken after it
     * shipped. A 1:1 read receipt is consumed on five inbound paths — relay,
     * mesh, V2, cross-platform mesh, group JSON — and a GROUP receipt was
     * consumed on exactly one. Every receipt that arrived over the mesh or
     * through V2 reached no parser and was dropped, so the sheet stayed empty
     * for precisely the users whose groups do not travel by relay.
     *
     * A helper rather than four copies, so the next inbound path costs one call
     * and the omission that caused this cannot recur quietly.
     *
     * @return true when this WAS a group receipt and has been recorded, so the
     *         caller must stop treating it as a message.
     */
    private fun consumeGroupReceipt(decrypted: String, senderPublicKey: String): Boolean {
        val r = com.oshi.messenger.service.GroupReceipts.parse(decrypted) ?: return false
        com.oshi.messenger.service.GroupReceipts.record(
            context, r.groupId, r.msgId, senderPublicKey, r.kind, r.at
        )
        Log.d(TAG, "📑 group receipt ${r.kind.code} for ${r.msgId.take(8)}… from ${senderPublicKey.take(8)}…")
        return true
    }

    private suspend fun handleReadReceipt(decrypted: String, senderPublicKey: String) {
        runCatching { com.oshi.messenger.service.BlockedByPeerStore.get(context).clear(senderPublicKey) }   // __BLOCKED_BY_PEER_2026_09_24__
        try {
            // Mark all our messages to this sender as read + delivered
            val messages = messageDao.getUnreadSentMessages(senderPublicKey)
            messages.forEach { message ->
                updateMessage(message.copy(isDelivered = true, isRead = true))
            }
            Log.d(TAG, "📖 ${messages.size} messages marked as read (receipt from ${senderPublicKey.take(8)}...)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle read receipt: ${e.message}")
        }
    }

    /**
     * Check if content is a delivery or read receipt
     */
    private fun isReceiptMessage(content: String): Boolean {
        return content.startsWith(DELIVERY_RECEIPT_PREFIX) || content.startsWith(READ_RECEIPT_PREFIX)
    }

    /**
     * Handle iOS encrypted delivery receipt: {"encrypted": "base64..."}
     * iOS uses SHA256(sharedSecret) as AES key (NOT HKDF).
     * Format: nonce(12) || ciphertext || tag(16)
     * We try all known contacts to find the right shared secret.
     */
    private suspend fun handleiOSEncryptedReceipt(encryptedBase64: String) {
        try {
            val encryptedData = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
            if (encryptedData.size < 28) { // minimum: 12 nonce + 16 tag
                Log.w(TAG, "📬 iOS receipt too small (${encryptedData.size} bytes), skipping")
                return
            }

            val contacts = contactDao.getAllContactsList()
            for (contact in contacts) {
                try {
                    val sharedSecret = cryptoManager.deriveSharedSecret(contact.publicKey)
                    // iOS uses SHA256(sharedSecret) as key — NOT HKDF!
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val symmetricKey = md.digest(sharedSecret)
                    val decrypted = cryptoManager.decryptAESGCM(encryptedData, symmetricKey)
                    val receiptJson = JSONObject(String(decrypted, Charsets.UTF_8))

                    val messageId = receiptJson.optString("messageId", "")
                    // __BLOCKED_BY_PEER_2026_09_24__ decrypted under this contact's secret = they acked.
                    runCatching { com.oshi.messenger.service.BlockedByPeerStore.get(context).clear(contact.publicKey) }
                    if (messageId.isNotEmpty()) {
                        val message = messageDao.getMessageById(messageId)
                        if (message != null && message.isFromMe && !message.isDelivered) {
                            updateMessage(message.copy(isDelivered = true))
                            Log.d(TAG, "📬 iOS receipt: message $messageId marked as delivered")
                        } else {
                            Log.d(TAG, "📬 iOS receipt for $messageId (already delivered or not found)")
                        }
                        // __RATING_THREE_MOMENTS_2026_11_15__ Moment 2, same rule as
                        // `handleDeliveryReceipt`.
                        if (message != null && message.isFromMe) {
                            AppReviewManager.trackMessageDelivered(context, contact.publicKey)
                        }
                    }
                    return // Decrypted successfully
                } catch (_: Exception) {
                    // Wrong contact, try next
                }
            }
            Log.d(TAG, "📬 Could not decrypt iOS receipt with any known contact")
        } catch (e: Exception) {
            Log.e(TAG, "📬 Failed to process iOS receipt: ${e.message}")
        }
    }

    // ============================================================================
    // PINNED MESSAGES (iOS-compatible, one pin per conversation)
    // ============================================================================

    /**
     * Pin a message in a conversation (one pin per conversation, matches iOS behavior)
     */
    fun pinMessage(conversationKey: String, messageId: String) {
        val current = _pinnedMessages.value.toMutableMap()
        current[conversationKey] = messageId
        _pinnedMessages.value = current
        savePinnedMessages()
    }

    /**
     * Unpin the pinned message in a conversation
     */
    fun unpinMessage(conversationKey: String) {
        val current = _pinnedMessages.value.toMutableMap()
        current.remove(conversationKey)
        _pinnedMessages.value = current
        savePinnedMessages()
    }

    /**
     * Get the pinned message ID for a conversation (null if none)
     */
    fun getPinnedMessageId(conversationKey: String): String? = _pinnedMessages.value[conversationKey]

    /**
     * 📌 Toggle the pin on a 1:1 message and TELL THE PEER.
     *
     * The missing outbound half of RP-3. `pinMessage` / `unpinMessage` above are pure
     * local state; before this, nothing called them and nothing broadcast, so a pin was
     * invisible to the iPhone even if it had been settable.
     *
     * iOS broadcasts pin state as the same unified `🔧ACTION🔧` envelope it uses for
     * edit and unsend (`OSHI/MessageManager.swift:8089-8121`,
     * `actionType: "pinMessage" | "unpinMessage"`), and Android's
     * `MessageActionsManager.handleIncomingAction` already parses both — see the
     * `addPinCallback` registration in `init`.
     *
     * Unlike edit and delete-for-everyone there is **no ownership guard**: iOS lets
     * either party pin any message in the conversation (`ChatView.swift:4605-4623`
     * offers Pin on both own and received bubbles).
     *
     * @return the new pinned message id, or null if the message is now unpinned.
     */
    suspend fun togglePinMessage(conversationKey: String, messageId: String): String? {
        val alreadyPinned = _pinnedMessages.value[conversationKey]
            ?.equals(messageId, ignoreCase = true) == true
        if (alreadyPinned) {
            unpinMessage(conversationKey)
            sendActionPayload(
                conversationKey,
                buildActionPayload("unpinMessage", messageId, null),
                "unpinMessage"
            )
            return null
        }
        pinMessage(conversationKey, messageId)
        sendActionPayload(
            conversationKey,
            buildActionPayload("pinMessage", messageId, null),
            "pinMessage"
        )
        return messageId
    }

    /**
     * Persist pinned messages to SharedPreferences as JSON
     */
    private fun savePinnedMessages() {
        val prefs = context.getSharedPreferences("pinned_messages", Context.MODE_PRIVATE)
        val json = JSONObject()
        _pinnedMessages.value.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString("pinnedMessages", json.toString()).apply()
    }

    /**
     * Load pinned messages from SharedPreferences
     */
    private fun loadPinnedMessages() {
        val prefs = context.getSharedPreferences("pinned_messages", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("pinnedMessages", null) ?: return
        try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.getString(key) }
            _pinnedMessages.value = map
            Log.d(TAG, "Pinned messages loaded: ${map.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pinned messages: ${e.message}")
        }
    }

    // ============================================================================
    // DELETE
    // ============================================================================

    suspend fun deleteMessage(messageId: String) {
        messageDao.softDelete(messageId)
        // The row survives (soft delete) but its bytes must not: iOS's
        // `MediaAttachmentCache.remove()` (`MediaAttachmentCache.swift:67-69`) is called
        // on exactly this path.
        mediaStore.delete(messageId)
    }

    // =========================================================================
    // 🔧ACTION🔧 — the iOS unified message-action payload
    // =========================================================================

    /**
     * iOS `MessageActionPayload` — `OSHI/MessageManager.swift:8089-8121`.
     *
     * ```
     * 🔧ACTION🔧{"actionType":"deleteForEveryone"|"editMessage"|"pinMessage"|"unpinMessage",
     *            "targetMessageId":"<uuid>",
     *            "newContent":"…",        // OMITTED when nil — Swift's synthesized
     *                                     // Codable uses encodeIfPresent for Optionals
     *            "timestamp":<Double>}    // JSONEncoder default is .deferredToDate ⇒
     *                                     // seconds since the 2001-01-01 Apple epoch
     * ```
     *
     * Sending milliseconds-since-1970 here (what `MessageActionsManager.kt` does) does
     * not throw on iOS — it decodes to a year-55000 Date — but it lands in
     * `messages[index].editedAt` (`MessageManager.swift:7113`) and drives the "(edited)"
     * stamp, so it must be the Apple epoch. Android already uses this convention for
     * reply `originalTimestamp` (`MessageActionsManager.kt:109`) and read receipts.
     */
    private fun buildActionPayload(
        actionType: String,
        targetMessageId: String,
        newContent: String?
    ): String = Companion.buildActionPayload(actionType, targetMessageId, newContent)

    /**
     * Encrypt and send a 🔧ACTION🔧 payload to [recipientPublicKey].
     *
     * 🔧 P0 FIX (2026-08-13 chat-1to1 audit #1, #2). `deleteMessageForEveryone` and
     * `editMessage` were a local DAO write plus a `// TODO`. The sender's bubble said
     * "This message was deleted" / "(edited)"; the recipient — iPhone or Android — kept
     * the original forever. The user was shown an unsend that never happened.
     *
     * Note this does NOT call `MessageActionsManager.deleteMessageForEveryone`
     * (`MessageActionsManager.kt:442-481`) even though that emitter builds the right
     * JSON: it ships the payload through `vpsClient.sendMessage(recipient, content)` —
     * the String overload — i.e. as PLAINTEXT, with no Double-Ratchet layer. iOS would
     * fail to decrypt it and drop the message. The receipt path is the correct shape
     * (`sendDeliveryReceipt` above): encrypt, then send bytes.
     */
    private fun sendActionPayload(recipientPublicKey: String, content: String, label: String) {
        scope.launch {
            try {
                val encrypted = encryptWithDoubleRatchet(content, recipientPublicKey)
                val ok = vpsClient.sendMessage(recipientPublicKey, encrypted, UUID.randomUUID().toString())
                if (ok) Log.d(TAG, "🔧 $label sent to ${recipientPublicKey.take(8)}...")
                else Log.w(TAG, "🔧 $label send returned false for ${recipientPublicKey.take(8)}...")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send $label: ${e.message}")
            }
        }
    }

    /**
     * 📡 Fan an already-encrypted CONTROL payload (reaction, …) out over mesh **and** VPS,
     * the way an ordinary text message goes out — R-2 of the 2026-08-24 react-reply audit.
     *
     * `MessageReactionManager.sendReactionNotification` called `vpsClient.sendMessage`
     * directly, so a reaction to a peer reachable only over Nearby / BLE / the
     * cross-platform TCP link was **lost with no error**: the message it reacted to had
     * arrived over mesh, and the reaction went to a relay the peer was not reading.
     * iOS never had this hole — it routes every reaction through
     * `messageManager.sendMessage(content:recipientAddress:identityManager:meshManager:)`
     * (`OSHI/ChatView.swift:4525-4530`), which fans out to both.
     *
     * Deliberately NOT `findIosPeerOrAwait`: that one sleeps up to 800 ms waiting for a
     * handshake. A reaction is a fire-and-forget side-channel; it takes the link if the
     * link is already there and otherwise goes straight to the relay.
     *
     * The bytes are passed in already encrypted so the caller keeps ownership of the
     * envelope format. Reactions use `cryptoManager.encryptMessage` (the "legacy Android
     * format" iOS decodes at `MessageManager.swift:3990-3998`) — do not silently promote
     * them to Double Ratchet here, that would be a wire change.
     */
    /**
     * 📊 Send a ballot, and do NOT store it as a message.
     *
     * A ballot is silent in [com.oshi.messenger.data.model.OSHIControlPayload]:
     * it is not a line in the thread and it must not wake the peer. So it goes
     * out through [dispatchControlPayload] — the same fan-out reactions use,
     * mesh included — rather than through `sendTextMessage`, which would insert
     * a row and then leave the conversation list showing a blank last message.
     */
    suspend fun sendPollBallot(recipientPublicKey: String, content: String) {
        try {
            val encrypted = cryptoManager.encryptMessage(content, recipientPublicKey)
            dispatchControlPayload(recipientPublicKey, encrypted, "poll ballot", plaintext = content)
        } catch (e: Exception) {
            Log.e(TAG, "📊 ballot send failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 📊 An inbound ballot. The voter is [senderPublicKey], which came off a
     * decrypted envelope and is therefore authenticated — never a field in the
     * JSON.
     */
    private fun recordPollBallot(decrypted: String, senderPublicKey: String) {
        val vote = com.oshi.messenger.data.model.PollVotePayload.fromMessageString(decrypted)
        if (vote == null) {
            Log.w(TAG, "📊 unreadable ballot from ${senderPublicKey.take(8)}")
            return
        }
        com.oshi.messenger.service.PollStore.attach(context)
        val changed = com.oshi.messenger.service.PollStore.record(vote, senderPublicKey)
        Log.d(TAG, "📊 ballot ${if (changed) "recorded" else "ignored (duplicate or older)"}" +
            " for poll ${vote.pollId.take(8)} from ${senderPublicKey.take(8)}")
    }

    /**
     * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ A peer asking for this
     * conversation to leave the relay, or answering our own request.
     *
     * Returns true when the payload was an ANSWER, which the caller must not
     * store: an ack is marked RENDERED like the request it answers, so letting
     * it through would print the line a second time in the thread.
     *
     * The requester is the sender of the decrypted envelope, authenticated by
     * the ratchet — never a field in the JSON, for the same reason a poll has no
     * `voter` field.
     */
    private fun handleOfflineOnly(decrypted: String, senderPublicKey: String): Boolean {
        val req = com.oshi.messenger.data.model.OfflineOnlyRequest.fromMessageString(decrypted) ?: return false
        val store = com.oshi.messenger.service.OfflineOnlyStore
        store.attach(context)

        if (req.isAck) {
            if (store.answersCurrentRequest(req, senderPublicKey)) {
                store.noteConfirmation(senderPublicKey, senderPublicKey)
                Log.d(TAG, "🔒 offline-only confirmed by ${senderPublicKey.take(8)}")
            }
            return true
        }

        store.applyIncoming(req, senderPublicKey)
        // Answer once; an answer is never echoed — see OfflineOnlyRequest.isAck.
        scope.launch {
            runCatching { sendPollBallot(senderPublicKey, req.acknowledgement().toMessageString()) }
                .onFailure { Log.w(TAG, "🔒 could not answer the offline-only request: ${it.javaClass.simpleName}") }
        }
        return false
    }

    /**
     * 💓 __ALIVE_CHECK_IN_2026_11_04__ A peer's liveness event.
     *
     * Three jobs, and nothing else — notably NOT consuming the message, which is
     * why this returns Unit where [handleOfflineOnly] returns a Boolean. There is
     * no acknowledgement to swallow: a check-in is a statement, not a request, so
     * there is nothing for the other side to answer and no echo to break.
     *
     *  1. Apply it, with OUR receive instant bounding THEIR clock
     *     ([com.oshi.messenger.data.model.AliveCheckInPolicy.confirmedAt]) and the
     *     store dropping a duplicate or an out-of-order replay. Both matter here
     *     rather than theoretically: one send can arrive over the Android mesh,
     *     the cross-platform mesh AND the relay, and this function is called on
     *     all three lanes.
     *  2. Re-arm the deadline watcher, because the next thing that has to happen
     *     is a notification at an instant this event just moved.
     *  3. Clear a stale "no response" banner when the event ends a silence. Not
     *     REPLACE it with a recovery banner: the check-in is itself `.rendered`,
     *     so the ordinary message notification already says they confirmed, and a
     *     second banner saying the same thing is the notification spam the design
     *     brief rules out. What would be wrong is leaving "no response from X" in
     *     the shade after X has answered.
     *
     * The confirming party is the sender of the decrypted envelope, authenticated
     * by the ratchet — never a field in the JSON, for exactly the reason a poll
     * has no `voter` field. A check-in that named its own author on the wire would
     * let anyone confirm in anyone's name, which for this feature means switching
     * someone else's alarm off.
     */
    private fun handleAliveCheckIn(decrypted: String, senderPublicKey: String) {
        val event = com.oshi.messenger.data.model.AliveCheckInEvent
            .fromMessageString(decrypted) ?: return
        val store = com.oshi.messenger.service.AliveCheckInStore
        store.attach(context)
        val applied = store.applyIncoming(event, senderPublicKey)
        if (!applied.accepted) return
        com.oshi.messenger.service.AliveCheckInWatcher.reschedule(context)
        if (applied.isRecovery || event.kind == com.oshi.messenger.data.model.AliveCheckInEvent.Kind.DISABLED) {
            com.oshi.messenger.service.AliveCheckInWatcher.clearOverdueNotice(context, senderPublicKey)
        }
        Log.d(TAG, "💓 alive ${event.kind.wire} from ${senderPublicKey.take(8)}… (recovery=${applied.isRecovery})")
    }

    private suspend fun dispatchControlPayload(
        recipientPublicKey: String,
        encrypted: ByteArray,
        label: String,
        /**
         * __CONTROL_PAYLOADS_NEED_THE_V2_LANE_2026_10_29__ The same payload BEFORE
         * encryption, so the relay leg can offer it to V2 — which encrypts for itself.
         *
         * Null keeps the pre-fix behaviour exactly, for any caller that cannot supply it.
         */
        plaintext: String? = null,
    ) {
        var meshTried = false

        // Android ↔ Android mesh (Nearby Connections).
        try {
            val meshPeer = meshManager.connectedPeers.value.find { it.publicKey == recipientPublicKey }
            if (meshPeer != null) {
                meshManager.sendMessageToPublicKey(
                    recipientPublicKey,
                    MeshMessage(
                        id = UUID.randomUUID().toString(),
                        type = MessageType.TEXT_MESSAGE,
                        senderPublicKey = myPublicKey,
                        senderName = "",
                        payload = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
                        timestamp = System.currentTimeMillis()
                    )
                )
                meshTried = true
                Log.d(TAG, "📡 $label sent via Android mesh to ${recipientPublicKey.take(8)}...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "📡 $label Android-mesh send threw: ${e.message}")
        }

        // Cross-platform mesh (iPhone over TCP/BLE) — no awaiting handshake.
        try {
            val reachable = crossPlatformMesh.findConnectedPeer(recipientPublicKey) != null ||
                crossPlatformMesh.isPeerReachable(recipientPublicKey)
            val meshPayload = crossPlatformMeshControlPayload(encrypted)
            if (reachable && meshPayload != null) {
                crossPlatformMesh.sendMessage(
                    recipientPublicKey,
                    meshPayload,
                    "TEXT_MESSAGE"
                )
                meshTried = true
                Log.d(TAG, "📡 $label sent via cross-platform mesh to ${recipientPublicKey.take(8)}...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "📡 $label cross-platform mesh send threw: ${e.message}")
        }

        // VPS always runs too — the peer dedupes, and mesh is fire-and-forget with no ack.
        try {
            // __CONTROL_PAYLOADS_NEED_THE_V2_LANE_2026_10_29__ V2 FIRST, legacy only if it
            // declines — the branch every other control payload already had.
            //
            // MEASURED, not reasoned: a desktop client sent a poll to this app over the
            // production relay, the card drew correctly, "Alpha" was tapped, this app's own
            // card moved to `Alpha 100% · 1 vote` — and the ballot never arrived. Its three
            // legs are the two meshes and `vpsClient.sendMessage`, which resolves to
            // `sendMessageViaIPFS`. A peer that speaks V2 and not the legacy lane — the
            // desktop client, deliberately, PARITY row 0.23 "ipfs was the old road" — can
            // therefore never receive a ballot, and the same is true of REACTIONS, the
            // other caller of this function.
            //
            // This is the identical defect that row 0.18 records for delivery receipts, read
            // receipts and typing: *"each did `encryptWithDoubleRatchet` straight into
            // `VPSClient.sendMessage` with no `v2MessageRouter` branch at all"*. Those three
            // were fixed with `ControlLane.preferV2`; these two were never given it.
            //
            // `plaintext` is null only for a caller that has none, which keeps the old
            // behaviour byte for byte. Nothing speculative is encrypted here: the caller
            // already encrypted for the mesh legs above, so this adds no ratchet step.
            val lane = if (plaintext != null) {
                sendControlOverV2OrLegacy(recipientPublicKey, plaintext)
            } else {
                if (!vpsClient.sendMessage(recipientPublicKey, encrypted) && !meshTried) {
                    Log.w(TAG, "⚠️ $label had no transport: VPS refused and no mesh peer")
                }
                "legacy"
            }
            Log.d(TAG, "📡 $label relay leg used the $lane lane")
        } catch (e: Exception) {
            if (!meshTried) Log.e(TAG, "❌ $label failed on every transport: ${e.message}")
            else Log.w(TAG, "📡 $label VPS leg failed, mesh leg was tried: ${e.message}")
        }
    }

    /** The peer for a 1:1 message, whichever side we are on. */
    private fun peerKeyOf(message: Message): String =
        if (message.isFromMe) message.recipientPublicKey else message.senderPublicKey

    /**
     * Apply an edit the PEER sent us.
     *
     * Ownership guard, ported from iOS's C-MSG-4 fix
     * (`OSHI/MessageManager.swift:7110-7114`): "a sender may only edit a message they
     * themselves sent". On our side that message is one where `isFromMe == false` —
     * without the check, a crafted 🔧ACTION🔧 could rewrite OUR OWN sent messages in our
     * own database.
     */
    private suspend fun applyRemoteEdit(messageId: String, newContent: String) {
        try {
            val row = messageDao.getMessageById(messageId) ?: return
            if (row.isFromMe) {
                Log.w(TAG, "⛔️ Rejected remote edit of a message I sent: ${messageId.take(8)}...")
                return
            }
            messageDao.editMessage(messageId, newContent, System.currentTimeMillis())
            Log.d(TAG, "✏️ Applied remote edit to ${messageId.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to apply remote edit: ${e.message}")
        }
    }

    /** Apply a delete-for-everyone the PEER sent us. Same ownership guard as above. */
    private suspend fun applyRemoteDeleteForEveryone(messageId: String) {
        try {
            val row = messageDao.getMessageById(messageId) ?: return
            if (row.isFromMe) {
                Log.w(TAG, "⛔️ Rejected remote delete of a message I sent: ${messageId.take(8)}...")
                return
            }
            messageDao.deleteForEveryone(messageId)
            // iOS zeroes the attachment bytes on this path too (`MessageManager.swift:6992-6997`).
            mediaStore.delete(messageId)
            Log.d(TAG, "🗑️ Applied remote delete-for-everyone to ${messageId.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to apply remote delete: ${e.message}")
        }
    }

    /**
     * Delete a message for everyone: local tombstone + the 🔧ACTION🔧 the peer needs.
     * iOS: `MessageManager.swift:7044-7055` (`deleteMessageForEveryone`).
     */
    suspend fun deleteMessageForEveryone(messageId: String) {
        val message = messageDao.getMessageById(messageId)
        messageDao.deleteForEveryone(messageId)
        // The DAO nulls `mediaData` on the row; the file copy has to go too or the
        // hydrator would put the "deleted" media straight back (iOS zeroes the bytes at
        // `MessageManager.swift:6992-6997` for the same reason).
        mediaStore.delete(messageId)

        if (message == null) {
            Log.w(TAG, "🗑️ deleteForEveryone: no row for $messageId — nothing to notify")
            return
        }
        // iOS refuses to delete-for-everyone a message it did not send, and the
        // receiver enforces the same ownership rule (`MessageManager.swift:7093-7097`).
        if (!message.isFromMe) {
            Log.w(TAG, "🗑️ deleteForEveryone on a message I did not send — local only")
            return
        }
        sendActionPayload(
            peerKeyOf(message),
            buildActionPayload("deleteForEveryone", messageId, null),
            "deleteForEveryone"
        )
        // __V2_CAPTION_2026_09_27__ A captioned photo/video also left its caption as a
        // separate text for iPhones (captionFollowUpId); retract that bubble too, or the
        // caption outlives the photo on the peer. Harmless where it never arrived.
        val type = message.mediaType
        if (type != null && needsCaptionFollowUp(type, wireCaption(type, message.content))) {
            sendActionPayload(
                peerKeyOf(message),
                buildActionPayload("deleteForEveryone", captionFollowUpId(messageId), null),
                "deleteForEveryone"
            )
        }
    }

    /**
     * Edit a message: local update + the 🔧ACTION🔧 the peer needs.
     * iOS: `MessageManager.swift:7059-7069` (`editMessage`).
     */
    suspend fun editMessage(messageId: String, newContent: String) {
        val message = messageDao.getMessageById(messageId)
        messageDao.editMessage(messageId, newContent, System.currentTimeMillis())

        if (message == null) {
            Log.w(TAG, "✏️ editMessage: no row for $messageId — nothing to notify")
            return
        }
        // Ownership, same as iOS (`MessageManager.swift:7110-7114` on the receive side).
        if (!message.isFromMe) {
            Log.w(TAG, "✏️ edit on a message I did not send — local only")
            return
        }
        sendActionPayload(
            peerKeyOf(message),
            buildActionPayload("editMessage", messageId, newContent),
            "editMessage"
        )
    }

    /**
     * 🔒 Consume a view-once message — for real.
     *
     * 🔧 P0 PRIVACY FIX. `ChatScreen` shipped the receive-side UI but could only record
     * "opened" in a private SharedPreferences set (`oshi_view_once_opened`); the
     * encrypted bytes stayed in the `messages` row and in the file store, so anything
     * that read the row directly — a DB export, the web-session `request_media`
     * responder (`onRequestMedia`, `:813`), a future re-render that trusted `mediaData`
     * — still handed back a photo the user was promised had been destroyed.
     *
     * Order is deliberate and mirrors `MessageManager.markViewOnceAsViewed`
     * (`OSHI/MessageManager.swift:6978-7009`):
     *   1. drop the DECRYPTED in-memory copy first (`swift:6987`,
     *      `MediaAttachmentCache.shared.remove(messageId)`) — wiping the stored blob
     *      while a decrypted copy survives in RAM defeats view-once entirely;
     *   2. null the stored blob (`swift:6999-7000` + `saveMessages()` at `:7006`) —
     *      here that is the row's `mediaData`, atomically with the flag;
     *   3. delete the file-backed copy, which iOS does not have (its blob IS the
     *      persisted message), but which `hydrateMedia` would otherwise re-attach on
     *      the very next Flow emission.
     *
     * NO CONTROL MESSAGE IS SENT TO THE SENDER, deliberately. The brief asked for one,
     * but iOS emits nothing: `markViewOnceAsViewed` (`swift:6978-7009`) is pure local
     * state — no `sendMessage`, no mesh, no relay — its only caller is
     * `MediaMessageView.swift:1108`, and the control-payload registry
     * (`OSHI/OSHIControlPayload.swift:83-108`) has no view-once entry at all. The
     * sender-side "opened" label (`MediaMessageView.swift:151`) is driven by the
     * sender's OWN local flag. Inventing a prefix here would put a frame on the wire
     * that no iPhone can parse — it would fall through iOS's catalog and risk
     * rendering raw in the thread. The only thing that legitimately crosses the wire
     * near this feature is the ordinary read receipt (`swift:6940-6946`), which the
     * read path already sends.
     */
    suspend fun markViewOnceOpened(messageId: String) {
        if (messageId.isEmpty()) return
        try {
            // (1) decrypted copy in RAM — iOS swift:6982-6987
            mediaStore.evict(messageId)
            // (2) stored blob + flag, one statement — iOS swift:6997-7006
            messageDao.markViewOnceOpened(messageId, System.currentTimeMillis())
            // (3) file-backed copy (no iOS analogue; see kdoc)
            mediaStore.delete(messageId)
            Log.d(TAG, "👁️ View-once ${messageId.take(8)} consumed — media wiped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to consume view-once $messageId: ${e.message}")
        }
    }

    suspend fun deleteConversation(publicKey: String) {
        // Collect the ids BEFORE the rows go, or the attachment files become
        // unreachable orphans that only the periodic purge could ever find.
        val ids = try {
            messageDao.getMessagesForContact(publicKey).first().map { it.id }
        } catch (e: Exception) {
            emptyList()
        }
        messageDao.deleteAllForContact(publicKey)
        mediaStore.deleteAll(ids)
        // A deleted conversation leaves the list even though the contact row stays.
        com.oshi.messenger.data.model.SavedContactThreads.get(context).set(publicKey, false)
    }

    // ============================================================================
    // MANUAL POLL & SESSION RESET
    // ============================================================================

    /**
     * Trigger a one-time VPS message poll (for pull-to-refresh).
     * Fetches pending messages from the server and processes them.
     */
    suspend fun triggerMessagePoll() {
        try {
            val messages = vpsClient.fetchMessages()
            messages.forEach { msg ->
                decryptionMutex.withLock { handleVPSMessage(msg) }
            }
            Log.d(TAG, "Manual poll completed: ${messages.size} messages fetched")
        } catch (e: Exception) {
            Log.e(TAG, "Manual poll failed: ${e.message}")
        }
    }

    /**
     * Reset the Double Ratchet encryption session for a contact.
     * This clears the existing session state, forcing a new key exchange.
     */
    suspend fun resetEncryptionSession(publicKey: String) {
        try {
            doubleRatchet.resetSession(publicKey, myPublicKey, ByteArray(32))
            Log.d(TAG, "Encryption session reset for ${publicKey.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset encryption session: ${e.message}")
        }
    }
}
