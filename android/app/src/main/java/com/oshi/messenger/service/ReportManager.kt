package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.data.model.ContentReport
import com.oshi.messenger.data.model.ReportDeliveryState
import com.oshi.messenger.data.model.ReportReason
import com.oshi.messenger.data.model.ReportSubject
import com.oshi.messenger.data.model.ReportWireFormat
import com.oshi.messenger.network.v2.V2Signer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local record of everything the user reported, plus delivery to
 * `POST /v2/report`. Port of iOS `ReportManager` (ContactActionsView.swift:253+).
 *
 * 🔧 P1 FIX (F-10a) — Android shipped with no reporting subsystem at all:
 * `grep -rn "ReportedItems\|ReportManager\|settings_reports"` returned zero
 * hits, so a user who wanted to report a contact had nowhere to do it and no
 * way to see what they had reported. iOS puts the log at Settings → Privacy →
 * Reports (`SettingsView.swift:1010-1017` → `ReportedItemsView`), which is also
 * half of the "report and block" affordance pair a user-generated-content app
 * is graded on at store review.
 *
 * The report is recorded locally FIRST and only then sent, so an offline report
 * is never lost — same ordering as iOS (ContactActionsView.swift:299-301).
 */
@Singleton
class ReportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signer: V2Signer,
) {
    companion object {
        private const val TAG = "ReportManager"
        private const val PREFS_NAME = "content_reports"
        private const val KEY_REPORTS = "reports"

        /** iOS `ReportSubmitter.path` (ContactActionsView.swift:141). */
        private const val REPORT_PATH = "/v2/report"
    }

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        // __SERVER_CLOCK_EVERY_SIGNER_2026_11_23__ learn the offset; re-sign once on a clock 401/403.
        .addInterceptor(com.oshi.messenger.network.auth.ServerClock.interceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _reports = MutableStateFlow(ReportWireFormat.decodeAll(prefs.getString(KEY_REPORTS, null)))

    /** Newest first, like iOS `allReports` (ContactActionsView.swift:289-291). */
    val reports: StateFlow<List<ContentReport>> = _reports.asStateFlow()

    val pendingCount: Int get() = _reports.value.count { it.state == ReportDeliveryState.PENDING }

    /**
     * Same base64/base64url folding contract as the block list — the identical
     * key reaches us in several encodings depending on transport
     * (ContactActionsView.swift:271-278).
     */
    private fun normalizeKey(key: String): String =
        key.trim().replace('-', '+').replace('_', '/').replace("=", "")

    /** Group ids compared case-insensitively (iOS upper-cases, Android lower-cases). */
    fun hasReportedGroup(groupId: String): Boolean =
        _reports.value.any { it.subject == ReportSubject.GROUP && it.subjectId.equals(groupId, ignoreCase = true) }

    fun hasReportedContact(publicKey: String): Boolean {
        val target = normalizeKey(publicKey)
        return _reports.value.any {
            it.subject == ReportSubject.CONTACT && normalizeKey(it.subjectId) == target
        }
    }

    /**
     * Record a report and attempt delivery. Returns the stored record; its
     * `state` is `PENDING` until [retryPending] or this call's own submit
     * succeeds.
     */
    suspend fun fileReport(
        subject: ReportSubject,
        subjectId: String,
        subjectName: String?,
        reason: ReportReason,
        details: String
    ): ContentReport {
        val report = ContentReport(
            id = UUID.randomUUID().toString(),
            subject = subject,
            subjectId = subjectId,
            subjectName = subjectName,
            reason = reason,
            details = ReportWireFormat.trimDetails(details),
            createdAt = System.currentTimeMillis(),
            state = ReportDeliveryState.PENDING
        )
        persist(_reports.value + report)
        submit(report)
        return _reports.value.firstOrNull { it.id == report.id } ?: report
    }

    /** Re-attempt every report the server has not accepted yet. */
    suspend fun retryPending() {
        for (report in _reports.value.filter { it.state == ReportDeliveryState.PENDING }) {
            submit(report)
        }
    }

    /** Drop a report from the local log. The server copy is unaffected. */
    fun withdraw(reportId: String) {
        persist(_reports.value.filterNot { it.id == reportId })
    }

    private fun persist(list: List<ContentReport>) {
        val sorted = list.sortedByDescending { it.createdAt }
        _reports.value = sorted
        runCatching { prefs.edit().putString(KEY_REPORTS, ReportWireFormat.encodeAll(sorted)).apply() }
    }

    private fun update(reportId: String, transform: (ContentReport) -> ContentReport) {
        persist(_reports.value.map { if (it.id == reportId) transform(it) else it })
    }

    /**
     * Signed POST, identical scheme to every other OSHI endpoint: canonical
     * message `METHOD\nPATH\nSHA256(body)\nTIMESTAMP` plus `x-oshi-user`.
     * 4xx is permanent (`REJECTED`); anything else stays `PENDING` for a retry.
     */
    private suspend fun submit(report: ContentReport) = withContext(Dispatchers.IO) {
        val body = ReportWireFormat.payloadJson(
            report = report,
            reporterKey = runCatching { signer.userKey() }.getOrDefault(""),
            clientVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        ).toByteArray(Charsets.UTF_8)

        try {
            val headers = signer.sign(
                method = "POST",
                path = REPORT_PATH,
                bodyToHash = body,
                withUserHeader = true,
            )
            val builder = Request.Builder()
                .url("${BuildConfig.VPS_URL}$REPORT_PATH")
                .post(body.toRequestBody("application/json".toMediaType()))
            headers.forEach { (k, v) -> builder.header(k, v) }

            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        update(report.id) {
                            it.copy(
                                state = ReportDeliveryState.SENT,
                                deliveredAt = System.currentTimeMillis(),
                                lastError = null
                            )
                        }
                        Log.d(TAG, "Report ${report.id} accepted by server")
                    }
                    resp.code in 400..499 -> {
                        update(report.id) {
                            it.copy(state = ReportDeliveryState.REJECTED, lastError = "http-${resp.code}")
                        }
                        Log.w(TAG, "Report ${report.id} permanently rejected: HTTP ${resp.code}")
                    }
                    else -> {
                        update(report.id) { it.copy(lastError = "http-${resp.code}") }
                        Log.w(TAG, "Report ${report.id} not delivered, will retry: HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            // Stays PENDING — the record is on disk, so nothing is lost.
            update(report.id) { it.copy(lastError = e.message) }
            Log.w(TAG, "Report ${report.id} submit failed, will retry: ${e.message}")
        }
    }
}
