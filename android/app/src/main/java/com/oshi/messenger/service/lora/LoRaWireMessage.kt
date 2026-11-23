package com.oshi.messenger.service.lora

import org.json.JSONObject

/**
 * The JSON that travels inside an "OM" envelope: iOS's `SecureMessage`, encoded
 * by the default `JSONEncoder()`.
 *
 * REFERENCE
 * ---------
 *  • `SecureMessage`        OSHI/MessageManager.swift:7983-8090 (fields, CodingKeys,
 *                           and which keys its decoder REQUIRES)
 *  • `EncryptedMessage`     OSHI/IdentityManager.swift:787-792
 *  • `DeliveryStatus`       OSHI/MessageManager.swift:8122-8134
 *  • `DeliveryMethod`       OSHI/MessageManager.swift:7966-7981
 *  • `meshWireSafe()`       OSHI/MeshNetworkManager.swift:1997-2019
 *  • put on the LoRa wire   OSHI/MeshtasticManager.swift:509
 *
 * TWO THINGS THAT SILENTLY BREAK INTEROP IF GOT WRONG
 * ---------------------------------------------------
 * 1. DATES. Swift's default `JSONEncoder` writes a `Date` as a DOUBLE of seconds
 *    since the APPLE reference date (2001-01-01), not Unix millis. Android holds
 *    Unix millis everywhere, so the conversion happens here, at the wire
 *    boundary, exactly as `CheckInMessage` already does for the mesh.
 * 2. THE PLAINTEXT STRIP. `SecureMessage` has no custom `encode(to:)` and
 *    `plaintextContent` is in its CodingKeys, so encoding the message whole puts
 *    the user's text on the air IN THE CLEAR beside the ciphertext — on the
 *    default LongFast channel, whose key is public (MeshtasticManager.swift:486-491).
 *    [meshWireSafe] is that strip. Nothing may reach [toWireJson] without it, and
 *    [toWireJson] applies it itself so the rule cannot be forgotten at a call site.
 *
 * iOS's decoder REQUIRES id, senderAddress, recipientAddress, encryptedContent,
 * timestamp, isRead and deliveryStatus (MessageManager.swift:8022-8028 — plain
 * `decode`, not `decodeIfPresent`). A message missing any of them is dropped
 * whole, so they are always emitted.
 */
data class LoRaWireMessage(
    val id: String,
    val senderAddress: String,
    val recipientAddress: String,
    val encryptedContent: LoRaEncryptedContent,
    /** Unix millis. Converted to Apple reference seconds on the wire. */
    val timestamp: Long,
    val isRead: Boolean,
    val deliveryStatus: String = STATUS_SENT,
    val senderPublicKey: String = "",
    val recipientPublicKey: String = "",
    /** LOCAL ONLY — stripped by [meshWireSafe]. */
    val plaintextContent: String? = null,
    /** LOCAL ONLY — stripped by [meshWireSafe]. */
    val mediaAttachment: ByteArray? = null,
    /** LOCAL ONLY — stripped by [meshWireSafe]. */
    val originalMediaData: ByteArray? = null,
    /** LOCAL ONLY — stripped by [meshWireSafe]. */
    val editedContent: String? = null,
    /** LOCAL ONLY — stripped by [meshWireSafe]. See MessageManager.swift:7973-7979. */
    val deliveryMethod: String? = null,
    val mediaType: String? = null,
    val mediaFileName: String? = null,
    val isViewOnce: Boolean = false,
    val hasBeenViewed: Boolean = false,
    /** Unix millis, or null. */
    val editedAt: Long? = null,
    val isDeletedForEveryone: Boolean = false,
    val replyToId: String? = null,
) {

    /** True when this message carries media, which LoRa refuses. MeshtasticManager.swift:450-455 */
    val carriesMedia: Boolean get() = mediaAttachment != null || originalMediaData != null

    /**
     * A copy safe to transmit. Strips the local-only plaintext/media sidecar
     * AND `deliveryMethod`. MeshNetworkManager.swift:2005-2019.
     *
     * `deliveryMethod` goes because it is the one field carrying an enum raw
     * value across the wire: a case an older peer has never heard of makes its
     * decoder THROW, which drops the whole message, not just the field. Each
     * side stamps the transport it actually observed.
     */
    fun meshWireSafe(): LoRaWireMessage = copy(
        plaintextContent = null,
        mediaAttachment = null,
        originalMediaData = null,
        editedContent = null,
        deliveryMethod = null,
    )

    /** The bytes that go inside the "OM" frames. Applies [meshWireSafe] itself. */
    fun toWireJson(): ByteArray = meshWireSafe().toJsonObject().toString().toByteArray(Charsets.UTF_8)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        // Required by iOS's decoder — always present.
        put("id", id)
        put("senderAddress", senderAddress)
        put("recipientAddress", recipientAddress)
        put("encryptedContent", encryptedContent.toJsonObject())
        put("timestamp", appleSeconds(timestamp))
        put("isRead", isRead)
        put("deliveryStatus", deliveryStatus)
        // Non-optional on iOS, so always emitted even when empty/false.
        put("senderPublicKey", senderPublicKey)
        put("recipientPublicKey", recipientPublicKey)
        put("isViewOnce", isViewOnce)
        put("hasBeenViewed", hasBeenViewed)
        put("isDeletedForEveryone", isDeletedForEveryone)
        // Optionals: a Swift nil is an ABSENT KEY, never JSON null. Emitting
        // null here would still decode (decodeIfPresent tolerates it) but would
        // spend wire bytes out of a 2160-byte budget for nothing.
        plaintextContent?.let { put("plaintextContent", it) }
        editedContent?.let { put("editedContent", it) }
        deliveryMethod?.let { put("deliveryMethod", it) }
        mediaType?.let { put("mediaType", it) }
        mediaFileName?.let { put("mediaFileName", it) }
        editedAt?.let { put("editedAt", appleSeconds(it)) }
        replyToId?.let { put("replyToId", it) }
        mediaAttachment?.let { put("mediaAttachment", base64(it)) }
        originalMediaData?.let { put("originalMediaData", base64(it)) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoRaWireMessage) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** Seconds between the Unix epoch and Apple's reference date (2001-01-01). */
        const val APPLE_EPOCH_OFFSET_SECONDS = 978_307_200.0

        // DeliveryStatus raw values. MessageManager.swift:8122-8134.
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
        const val STATUS_FAILED = "failed"

        // DeliveryMethod raw values. MessageManager.swift:7966-7981. Local only.
        const val METHOD_MESH = "Direct"
        const val METHOD_IPFS = "Cloud"
        const val METHOD_LORA = "LoRa"
        const val METHOD_PENDING = "Sending..."

        fun appleSeconds(unixMillis: Long): Double =
            unixMillis / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS

        fun unixMillis(appleSeconds: Double): Long =
            Math.round((appleSeconds + APPLE_EPOCH_OFFSET_SECONDS) * 1000.0)

        /**
         * `java.util.Base64`, not `android.util.Base64`: Swift's `JSONEncoder`
         * emits padded standard base64 for a `Data`, and android.util's copy is
         * a throwing stub in JVM unit tests, which would make the one path that
         * must be provable un-testable. minSdk is 26, so it is available.
         */
        private fun base64(d: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(d)

        /**
         * Decode an envelope that came off the radio. Returns null rather than
         * throwing: a frame that does not parse is a frame, not a crash — iOS
         * logs and drops (MeshtasticManager.swift:1080-1083).
         */
        fun fromWireJson(bytes: ByteArray): LoRaWireMessage? = try {
            fromJsonObject(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }

        fun fromJsonObject(o: JSONObject): LoRaWireMessage? {
            // Mirror iOS: the seven required keys are required. A message
            // missing one is not a message.
            if (!o.has("id") || !o.has("senderAddress") || !o.has("recipientAddress") ||
                !o.has("encryptedContent") || !o.has("timestamp") ||
                !o.has("isRead") || !o.has("deliveryStatus")
            ) return null
            val enc = LoRaEncryptedContent.fromJsonObject(o.getJSONObject("encryptedContent"))
                ?: return null
            return LoRaWireMessage(
                id = o.getString("id"),
                senderAddress = o.getString("senderAddress"),
                recipientAddress = o.getString("recipientAddress"),
                encryptedContent = enc,
                timestamp = unixMillis(o.getDouble("timestamp")),
                isRead = o.getBoolean("isRead"),
                deliveryStatus = o.getString("deliveryStatus"),
                senderPublicKey = o.optString("senderPublicKey", ""),
                recipientPublicKey = o.optString("recipientPublicKey", ""),
                plaintextContent = o.optStringOrNull("plaintextContent"),
                editedContent = o.optStringOrNull("editedContent"),
                // An unrecognised transport label is worth nothing; the message
                // is worth everything. Carried through as a raw string so a new
                // case on the far side can never drop the message.
                // MessageManager.swift:8036-8046.
                deliveryMethod = o.optStringOrNull("deliveryMethod"),
                mediaType = o.optStringOrNull("mediaType"),
                mediaFileName = o.optStringOrNull("mediaFileName"),
                isViewOnce = o.optBoolean("isViewOnce", false),
                hasBeenViewed = o.optBoolean("hasBeenViewed", false),
                editedAt = if (o.has("editedAt") && !o.isNull("editedAt")) {
                    unixMillis(o.getDouble("editedAt"))
                } else null,
                isDeletedForEveryone = o.optBoolean("isDeletedForEveryone", false),
                replyToId = o.optStringOrNull("replyToId"),
            )
        }

        internal fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}

/** IdentityManager.swift:787-792. `timestamp` is an Apple-epoch Double on the wire. */
data class LoRaEncryptedContent(
    val ciphertext: String,
    val signature: String,
    val senderPublicKey: String,
    /** Unix millis. */
    val timestamp: Long,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("ciphertext", ciphertext)
        put("signature", signature)
        put("senderPublicKey", senderPublicKey)
        put("timestamp", LoRaWireMessage.appleSeconds(timestamp))
    }

    companion object {
        fun fromJsonObject(o: JSONObject): LoRaEncryptedContent? = try {
            LoRaEncryptedContent(
                ciphertext = o.getString("ciphertext"),
                signature = o.getString("signature"),
                senderPublicKey = o.optString("senderPublicKey", ""),
                timestamp = LoRaWireMessage.unixMillis(o.getDouble("timestamp")),
            )
        } catch (_: Exception) {
            null
        }
    }
}
