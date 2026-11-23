package com.oshi.messenger.service

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CalendarExportHelper - Export scheduled calls to device calendar
 * Matches iOS CalendarExportHelper.swift functionality.
 */
@Singleton
class CalendarExportHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CalendarExportHelper"
    }

    /**
     * Export a scheduled call event to the device calendar.
     * Returns the calendar event ID, or null if export failed.
     */
    fun exportScheduledCall(
        title: String,
        description: String,
        startTimeMillis: Long,
        durationMinutes: Int = 30,
        reminderMinutes: Int = 15
    ): Long? {
        if (!hasCalendarPermission()) {
            Log.w(TAG, "Calendar permission not granted")
            return null
        }

        try {
            val calendarId = getDefaultCalendarId() ?: run {
                Log.w(TAG, "No writable calendar found")
                return null
            }

            val endTimeMillis = startTimeMillis + (durationMinutes * 60 * 1000L)

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, endTimeMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()

            if (eventId != null && reminderMinutes > 0) {
                val reminderValues = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
            }

            Log.d(TAG, "Exported event to calendar: id=$eventId")
            return eventId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export to calendar: ${e.message}")
            return null
        }
    }

    /**
     * Delete a previously exported calendar event.
     */
    fun deleteCalendarEvent(eventId: Long): Boolean {
        if (!hasCalendarPermission()) return false
        return try {
            val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendPath(eventId.toString()).build()
            val deleted = context.contentResolver.delete(uri, null, null)
            deleted > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete calendar event: ${e.message}")
            false
        }
    }

    fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
                "${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val isPrimary = cursor.getInt(1)
                if (isPrimary == 1) return id
            }
            // Fallback to first writable calendar
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }
}
