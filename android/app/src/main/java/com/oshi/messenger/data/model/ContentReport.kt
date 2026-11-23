package com.oshi.messenger.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A report the user filed about a contact or a group, kept locally so it is
 * never lost, so delivery can be retried, and so Settings can show what was
 * reported. Port of iOS `ContentReport` (ContactActionsView.swift:62-108).
 */
data class ContentReport(
    val id: String,
    val subject: ReportSubject,
    /** base64 X25519 public key for a contact, group id for a group. */
    val subjectId: String,
    val subjectName: String?,
    val reason: ReportReason,
    /** Free text the reporter typed. Never harvested from the conversation. */
    val details: String,
    val createdAt: Long,
    val state: ReportDeliveryState,
    val deliveredAt: Long? = null,
    val lastError: String? = null
)

/** iOS `ContentReport.Subject` (ContactActionsView.swift:65-70). */
enum class ReportSubject(val wire: String) {
    CONTACT("contact"),
    GROUP("group");

    companion object {
        fun fromWire(value: String): ReportSubject =
            entries.firstOrNull { it.wire == value } ?: CONTACT
    }
}

/** iOS `BlockReason` (BlockedContactsManager.swift:45-63). */
enum class ReportReason(val wire: String) {
    SPAM("spam"),
    HARASSMENT("harassment"),
    INAPPROPRIATE("inappropriate"),
    OTHER("other");

    companion object {
        fun fromWire(value: String): ReportReason =
            entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

/** iOS `ContentReport.DeliveryState` (ContactActionsView.swift:72-79). */
enum class ReportDeliveryState(val wire: String) {
    /** Queued locally, not yet accepted by the server. */
    PENDING("pending"),

    /** Server returned 2xx. */
    SENT("sent"),

    /** Server returned a permanent 4xx — retrying would not help. */
    REJECTED("rejected");

    companion object {
        fun fromWire(value: String): ReportDeliveryState =
            entries.firstOrNull { it.wire == value } ?: PENDING
    }
}

/**
 * Encoders for the local store and for the `POST /v2/report` body.
 *
 * The wire payload is a verbatim port of iOS `ReportPayload`
 * (ContactActionsView.swift:112-124): flat, small, and auditable at a glance —
 * the reason, the reporter-typed text, the subject identifier, and nothing from
 * the conversation itself. Field names and the ISO-8601 `createdAt` encoding
 * match iOS byte for byte; the only intentional difference is
 * `platform = "android"`.
 *
 * Pure, so the shape is unit-testable without a device.
 */
object ReportWireFormat {

    /** iOS caps free text at 2000 characters (ContactActionsView.swift:262). */
    const val MAX_DETAIL_LENGTH = 2000

    fun trimDetails(raw: String): String = raw.trim().take(MAX_DETAIL_LENGTH)

    /** The exact bytes that leave the device, as JSON text. */
    fun payloadJson(
        report: ContentReport,
        reporterKey: String,
        clientVersion: String,
        platform: String = "android"
    ): String = JSONObject().apply {
        put("reportId", report.id)
        put("subjectType", report.subject.wire)
        put("subjectId", report.subjectId)
        put("reason", report.reason.wire)
        put("details", report.details)
        put("reporterKey", reporterKey)
        put("clientVersion", clientVersion)
        put("platform", platform)
        put("createdAt", iso8601(report.createdAt))
    }.toString()

    /** `[.withInternetDateTime]`, i.e. seconds precision and a `Z` suffix. */
    fun iso8601(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(epochMillis))
    }

    fun encodeAll(reports: List<ContentReport>): String {
        val array = JSONArray()
        for (r in reports) {
            array.put(JSONObject().apply {
                put("id", r.id)
                put("subject", r.subject.wire)
                put("subjectId", r.subjectId)
                r.subjectName?.let { put("subjectName", it) }
                put("reason", r.reason.wire)
                put("details", r.details)
                put("createdAt", r.createdAt)
                put("state", r.state.wire)
                r.deliveredAt?.let { put("deliveredAt", it) }
                r.lastError?.let { put("lastError", it) }
            })
        }
        return array.toString()
    }

    /**
     * Decodes the local store. A single malformed entry is dropped rather than
     * taking the whole list with it — one bad row must not erase the user's
     * report history the way one unknown enum case erased a stored widget list.
     */
    fun decodeAll(text: String?): List<ContentReport> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ContentReport>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val subjectId = o.optString("subjectId").takeIf { it.isNotEmpty() } ?: continue
            out.add(
                ContentReport(
                    id = id,
                    subject = ReportSubject.fromWire(o.optString("subject")),
                    subjectId = subjectId,
                    subjectName = o.optString("subjectName").takeIf { it.isNotEmpty() },
                    reason = ReportReason.fromWire(o.optString("reason")),
                    details = o.optString("details"),
                    createdAt = o.optLong("createdAt"),
                    state = ReportDeliveryState.fromWire(o.optString("state")),
                    deliveredAt = o.optLong("deliveredAt").takeIf { it > 0L },
                    lastError = o.optString("lastError").takeIf { it.isNotEmpty() }
                )
            )
        }
        return out
    }
}
