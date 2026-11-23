package com.oshi.messenger.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.ScheduledCall
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CalendarExportHelper - Compatible with iOS CalendarExportHelper.swift
 * Exports messages and scheduled calls to calendar or files
 */
@Singleton
class CalendarExportHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CalendarExportHelper"
    }

    // =========================================================================
    // CALENDAR EXPORT
    // =========================================================================

    /**
     * Export a scheduled call to device calendar
     */
    fun exportScheduledCallToCalendar(call: ScheduledCall): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, call.title.ifEmpty {
                    "${if (call.isVideoCall) "Video" else "Voice"} Call with ${call.peerName}"
                })
                putExtra(CalendarContract.Events.DESCRIPTION, buildCallDescription(call))
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, call.scheduledTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, call.scheduledTime + 30 * 60 * 1000)  // 30 min
                putExtra(CalendarContract.Events.EVENT_LOCATION, "OSHI Mesh Messenger")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Log.d(TAG, "Exported scheduled call to calendar: ${call.id}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export to calendar: ${e.message}")
            false
        }
    }

    /**
     * Export multiple events to calendar
     */
    fun exportScheduledCallsToCalendar(calls: List<ScheduledCall>): Int {
        var exported = 0
        calls.forEach { call ->
            if (exportScheduledCallToCalendar(call)) {
                exported++
            }
        }
        return exported
    }

    // __PLAINTEXT_LEFTOVERS_2026_09_24__ The plaintext .txt/.csv/.json chat writers (to
    // app-specific EXTERNAL storage, never deleted) and their share/list helpers were
    // removed: they had no caller. The supported export is the encrypted
    // `MessageExportManager` one; `purgeLegacyPlaintextExports` deletes old leftovers.

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun buildCallDescription(call: ScheduledCall): String {
        val sb = StringBuilder()
        sb.appendLine("OSHI Scheduled Call")
        sb.appendLine("Type: ${if (call.isVideoCall) "Video" else "Voice"} Call")
        sb.appendLine("With: ${call.peerName}")

        if (call.notes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Notes: ${call.notes}")
        }

        return sb.toString()
    }
}
