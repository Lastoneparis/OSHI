package com.oshi.messenger.data.model

import com.google.gson.Gson
import com.oshi.messenger.R
import java.util.*

/**
 * Check-In / Accompagnement Safety Feature
 * Similar to Apple's Check-In feature - notifies contact when you arrive or don't respond
 */

// Transport mode for ETA calculation
enum class TransportMode {
    walking,
    driving,
    transit;

    val icon: String
        get() = when (this) {
            walking -> "figure.walk"
            driving -> "car.fill"
            transit -> "tram.fill"
        }

    val emoji: String
        get() = when (this) {
            walking -> "\uD83D\uDEB6"  // Walking emoji
            driving -> "\uD83D\uDE97"  // Car emoji
            transit -> "\uD83D\uDE87"  // Metro emoji
        }

    /**
     * 🔧 FIX (2026-08-24 three-gaps): was three English literals, printed on the
     * check-in bubble in all 34 locales. iOS resolves the same three through
     * `NSLocalizedString` (`OSHI/CheckInMessage.swift:36-42`).
     *
     * A resource ID rather than a `String`, because this enum has no `Context` and
     * an enum property that captured one would leak an Activity. The caller —
     * which is always a composable or a service that has one — resolves it.
     */
    val localizedNameRes: Int
        get() = when (this) {
            walking -> R.string.map_nav_mode_walking
            driving -> R.string.map_nav_mode_driving
            transit -> R.string.checkin_mode_transit
        }
}

// Check-In session status
enum class CheckInStatus {
    active,      // Check-in is ongoing
    arrived,     // User arrived safely
    expired,     // ETA passed, waiting for response
    cancelled,   // User cancelled check-in
    emergency    // Emergency data was sent
}

// Check-In message type
enum class CheckInMessageType {
    started,     // Initial check-in message
    update,      // Location update during trip
    arrived,     // Safely arrived
    extended,    // User extended time
    cancelled,   // User cancelled
    warning,     // Warning before emergency
    emergency;   // Timeout with emergency data

    /**
     * The localized name of this type.
     *
     * 🔧 FIX (2026-08-24 three-gaps): this lived on `CheckInMessage.displayTitle`
     * as seven English literals, and that property is what the conversation row
     * (`ConversationsScreen.formatPreviewText`) and the in-thread `CheckInBubble`
     * print — so a French user's list said "Emergency Alert". iOS localizes the
     * same set (`OSHI/NotificationManager.swift:519-535`).
     *
     * On the TYPE rather than on the message, so a caller that only has a type
     * (the conversation-row label table) need not fabricate a whole message.
     * A resource ID rather than a `String`, so this file keeps no `Context` and
     * `CheckInWireFormatTest` still builds these on the JVM.
     */
    val titleRes: Int
        get() = when (this) {
            started -> R.string.checkin_started
            update -> R.string.checkin_type_update
            arrived -> R.string.checkin_type_arrived
            extended -> R.string.checkin_type_extended
            cancelled -> R.string.checkin_cancelled
            warning -> R.string.checkin_type_warning
            emergency -> R.string.checkin_type_emergency
        }

    /**
     * Does a message of this type deserve the loudest notification the channel
     * allows when it arrives from a contact?
     *
     * iOS uses `UNNotificationSound.defaultCritical` for `.emergency`
     * (`OSHI/NotificationManager.swift:390-393`). `warning` joins it here: it is
     * the last signal before the SENDER's phone escalates on its own, and a missed
     * one costs the same fifteen minutes.
     */
    val isUrgentOnArrival: Boolean
        get() = this == emergency || this == warning
}

// Emergency data sent when user doesn't respond
data class EmergencyData(
    val lastKnownLatitude: Double,
    val lastKnownLongitude: Double,
    val lastLocationTime: Long,  // Unix timestamp in milliseconds
    val batteryLevel: Double,    // 0.0-1.0
    val isCharging: Boolean,
    val networkType: String,     // WiFi, Cellular, Offline
    val deviceName: String,
    val lastMovementTime: Long?  // Unix timestamp in milliseconds
) {
    val batteryPercentage: Int
        get() = (batteryLevel * 100).toInt()

    val formattedBattery: String
        get() {
            val percent = batteryPercentage
            val charging = if (isCharging) " \u26A1" else ""
            return "$percent%$charging"
        }
}

// Active Check-In session
data class CheckInSession(
    val id: String = UUID.randomUUID().toString(),
    val contactPublicKey: String,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
    val destinationAddress: String,
    val transportMode: TransportMode,
    val startTime: Long = System.currentTimeMillis(),
    var estimatedArrivalTime: Long,
    var extraTimeMinutes: Int = 0,
    var status: CheckInStatus = CheckInStatus.active,
    var lastUpdateTime: Long = System.currentTimeMillis(),
    /**
     * When the "Are you okay?" notification was **actually delivered** to the system
     * tray — never when we merely decided to warn. `CheckInEscalationPolicy` refuses
     * to escalate while this is null, so a phone that cannot show notifications can
     * no longer produce an emergency alert the user was never asked about.
     */
    var warningShownAt: Long? = null,
    /**
     * When the `warning` message was sent to the CONTACT. Separate from
     * [warningShownAt] on purpose: the contact must be told "hasn't arrived yet"
     * exactly once, even when the local notification fails and the warning is
     * retried every minute. Without this the retry loop would send the peer one
     * warning bubble per minute.
     */
    var warningSentToContactAt: Long? = null
) {
    val effectiveDeadline: Long
        get() = estimatedArrivalTime + (extraTimeMinutes * 60 * 1000L)

    /**
     * Whether this session has a real destination fix.
     *
     * Every Android session used to be armed at (0.0, 0.0) — Null Island, in the
     * Gulf of Guinea — because `CheckInSheet` never geocoded the typed address. The
     * arrival test in `CheckInManager.checkProgress` therefore measured the user's
     * distance to a point in the Atlantic and never fired, so **no** check-in ever
     * ended by arriving. The sheet now resolves coordinates, and this property is
     * what lets a session that still could not resolve them (offline-only mode, a
     * place the geocoder does not know) run honestly as a time-only check-in the
     * user closes themselves, instead of silently pretending to watch for arrival.
     */
    val hasDestinationFix: Boolean
        get() = destinationLatitude != 0.0 || destinationLongitude != 0.0

    val remainingTimeMs: Long
        get() = effectiveDeadline - System.currentTimeMillis()

    val isOverdue: Boolean
        get() = remainingTimeMs < 0

    val formattedRemainingTime: String
        get() {
            val remaining = maxOf(0, remainingTimeMs)
            val hours = (remaining / (1000 * 60 * 60)).toInt()
            val minutes = ((remaining / (1000 * 60)) % 60).toInt()

            return if (hours > 0) {
                "${hours}h ${minutes}m"
            } else {
                "$minutes min"
            }
        }

    /**
     * Push the deadline out and **re-arm the warning**.
     *
     * 🔴 The reset of [warningShownAt] / [warningSentToContactAt] and of [status] is
     * the whole point, not tidiness. Before it, extending an already-overdue session
     * left `warningShownAt` set: the moment the *new* deadline passed,
     * `checkDeadline` saw "warning already shown, and it was shown more than fifteen
     * minutes ago" and jumped **straight to the emergency alert** — no second
     * "Are you okay?", no chance to answer. So the user pressing "+15 min" was
     * arming a silent emergency for fifteen minutes later. Every extension starts a
     * fresh warning-then-escalate cycle.
     */
    fun extend(minutes: Int) {
        extraTimeMinutes += minutes
        warningShownAt = null
        warningSentToContactAt = null
        if (status == CheckInStatus.expired) status = CheckInStatus.active
        lastUpdateTime = System.currentTimeMillis()
    }

    fun markArrived() {
        status = CheckInStatus.arrived
        lastUpdateTime = System.currentTimeMillis()
    }

    fun markCancelled() {
        status = CheckInStatus.cancelled
        lastUpdateTime = System.currentTimeMillis()
    }

    fun markEmergency() {
        status = CheckInStatus.emergency
        lastUpdateTime = System.currentTimeMillis()
    }

    /**
     * The deadline passed and the contact has been told. Does **not** stamp
     * [warningShownAt]: that belongs to [markWarningDelivered], which only runs if
     * the notification actually reached the tray.
     */
    fun markExpired() {
        status = CheckInStatus.expired
        warningSentToContactAt = System.currentTimeMillis()
        lastUpdateTime = System.currentTimeMillis()
    }

    /** The "Are you okay?" notification reached the system tray. Starts the clock. */
    fun markWarningDelivered() {
        warningShownAt = System.currentTimeMillis()
        lastUpdateTime = System.currentTimeMillis()
    }

    /**
     * The wall clock moved backwards under us, so the elapsed time since the warning
     * cannot be trusted. Forget the stamp and warn again — see
     * [com.oshi.messenger.service.CheckInEscalationPolicy].
     */
    fun forgetWarningForClockChange() {
        warningShownAt = null
        warningSentToContactAt = null
        lastUpdateTime = System.currentTimeMillis()
    }
}

// Check-In message sent between devices
data class CheckInMessage(
    val type: CheckInMessageType,
    val sessionId: String,
    val destinationAddress: String? = null,
    val transportMode: TransportMode? = null,
    val estimatedArrival: Long? = null,  // Unix timestamp in milliseconds
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val emergencyData: EmergencyData? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val senderName: String? = null
) {
    companion object {
        const val MESSAGE_PREFIX = "\uD83D\uDEE1\uFE0FCHECK_IN\uD83D\uDEE1\uFE0F"  // Shield emoji prefix

        // Factory methods
        fun started(session: CheckInSession, senderName: String? = null): CheckInMessage {
            return CheckInMessage(
                type = CheckInMessageType.started,
                sessionId = session.id,
                destinationAddress = session.destinationAddress,
                transportMode = session.transportMode,
                estimatedArrival = session.estimatedArrivalTime,
                senderName = senderName
            )
        }

        fun update(sessionId: String, latitude: Double, longitude: Double): CheckInMessage {
            return CheckInMessage(
                type = CheckInMessageType.update,
                sessionId = sessionId,
                currentLatitude = latitude,
                currentLongitude = longitude
            )
        }

        fun arrived(sessionId: String, destinationAddress: String, senderName: String? = null): CheckInMessage {
            return CheckInMessage(
                type = CheckInMessageType.arrived,
                sessionId = sessionId,
                destinationAddress = destinationAddress,
                senderName = senderName
            )
        }

        fun extended(sessionId: String, newETA: Long): CheckInMessage {
            return CheckInMessage(
                type = CheckInMessageType.extended,
                sessionId = sessionId,
                estimatedArrival = newETA
            )
        }

        fun cancelled(sessionId: String): CheckInMessage {
            return CheckInMessage(
                type = CheckInMessageType.cancelled,
                sessionId = sessionId
            )
        }

        fun warning(sessionId: String): CheckInMessage {
            return CheckInMessage(
                type = CheckInMessageType.warning,
                sessionId = sessionId
            )
        }

        fun emergency(sessionId: String, emergencyData: EmergencyData, destinationAddress: String, senderName: String? = null): CheckInMessage {
            return CheckInMessage(
                type = CheckInMessageType.emergency,
                sessionId = sessionId,
                destinationAddress = destinationAddress,
                currentLatitude = emergencyData.lastKnownLatitude,
                currentLongitude = emergencyData.lastKnownLongitude,
                emergencyData = emergencyData,
                senderName = senderName
            )
        }

        // --- Cross-platform timestamp handling -------------------------------
        // iOS CheckInMessage/EmergencyData are Codable structs whose Date fields
        // (timestamp, estimatedArrival, lastLocationTime, lastMovementTime) are
        // encoded by the default JSONEncoder as Apple reference-date seconds
        // (secondsSince 2001-01-01) — NOT Unix millis. Android stores them as Long
        // Unix millis. Without conversion the safety-critical arrival deadline is
        // decades off in both directions (iOS ETA read as millis → 1970; Android ETA
        // read as Apple seconds → year ~55000), so the emergency alert never arms
        // correctly. We convert only at the wire boundary and leave the in-memory
        // representation as Unix millis everywhere else.
        private const val APPLE_EPOCH_OFFSET_SECONDS = 978307200.0
        // Apple-epoch seconds today ≈ 7.7e8; Unix millis today ≈ 1.7e12. 1e11 sits
        // cleanly between them, so on receive we can accept BOTH a compliant iOS/new
        // sender (Apple seconds) and a legacy Android sender (Unix millis).
        private const val MILLIS_DETECT_THRESHOLD = 1e11

        private fun unixMillisToAppleSeconds(ms: Long): Double =
            ms / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS

        private fun wireToUnixMillis(v: Double): Long =
            if (kotlin.math.abs(v) > MILLIS_DETECT_THRESHOLD) v.toLong()               // legacy Android: already millis
            else ((v + APPLE_EPOCH_OFFSET_SECONDS) * 1000.0).toLong()                  // iOS / new Android: Apple seconds

        private fun convertObjMillisToApple(obj: org.json.JSONObject, key: String) {
            if (obj.has(key) && !obj.isNull(key)) obj.put(key, unixMillisToAppleSeconds(obj.getLong(key)))
        }
        private fun convertObjWireToMillis(obj: org.json.JSONObject, key: String) {
            if (obj.has(key) && !obj.isNull(key)) obj.put(key, wireToUnixMillis(obj.getDouble(key)))
        }

        // Parsing
        fun fromMessageString(string: String): CheckInMessage? {
            if (!string.startsWith(MESSAGE_PREFIX)) return null
            val jsonString = string.removePrefix(MESSAGE_PREFIX)
            return try {
                val obj = org.json.JSONObject(jsonString)
                convertObjWireToMillis(obj, "timestamp")
                convertObjWireToMillis(obj, "estimatedArrival")
                if (obj.has("emergencyData") && !obj.isNull("emergencyData")) {
                    val emo = obj.getJSONObject("emergencyData")
                    convertObjWireToMillis(emo, "lastLocationTime")
                    convertObjWireToMillis(emo, "lastMovementTime")
                }
                Gson().fromJson(obj.toString(), CheckInMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun isCheckInMessage(string: String): Boolean {
            return string.startsWith(MESSAGE_PREFIX)
        }
    }

    // Serialization — emit Apple reference-date seconds so iOS renders the check-in
    // and arms its arrival deadline correctly.
    fun toMessageString(): String {
        val obj = org.json.JSONObject(Gson().toJson(this))
        convertObjMillisToApple(obj, "timestamp")
        convertObjMillisToApple(obj, "estimatedArrival")
        if (obj.has("emergencyData") && !obj.isNull("emergencyData")) {
            val emo = obj.getJSONObject("emergencyData")
            convertObjMillisToApple(emo, "lastLocationTime")
            convertObjMillisToApple(emo, "lastMovementTime")
        }
        return "$MESSAGE_PREFIX$obj"
    }

    // Display helpers

    /**
     * The localized name of this check-in's type.
     *
     * 🔧 FIX (2026-08-24 three-gaps): `displayTitle` was seven English literals and
     * it is what `ConversationsScreen.formatPreviewText` prints on the conversation
     * row and what `CheckInBubble` titles itself with — so a French user's list read
     * "Emergency Alert". iOS localizes the same set
     * (`OSHI/NotificationManager.swift:519-535`, `CheckInMessage.swift:36-42`).
     *
     * Resource ID, not `String`: this model is deliberately Android-Context-free so
     * `CheckInWireFormatTest` can build one on the JVM.
     */
    val displayTitleRes: Int get() = type.titleRes

    /**
     * The body of the notification raised when THIS check-in arrives from a
     * contact, or `null` for a type that must stay silent.
     *
     * `update` returns null deliberately: iOS drops the banner for a location
     * update (`OSHI/NotificationManager.swift:300-306`) precisely so the
     * five-minute position pings of a live journey do not buzz the phone all
     * evening. A null here is a decision, not a gap.
     *
     * Returned as (resource, formatArg) so the caller — which has the `Context` —
     * does the formatting, and so this stays unit-testable.
     */
    val incomingNotificationBody: Pair<Int, String?>?
        get() = when (type) {
            CheckInMessageType.update -> null
            CheckInMessageType.started ->
                R.string.checkin_incoming_started to (destinationAddress ?: "")
            CheckInMessageType.arrived -> R.string.checkin_incoming_arrived to null
            CheckInMessageType.extended -> R.string.checkin_incoming_extended to null
            CheckInMessageType.cancelled -> R.string.checkin_incoming_cancelled to null
            CheckInMessageType.warning -> R.string.checkin_incoming_warning to null
            CheckInMessageType.emergency -> R.string.checkin_incoming_emergency to null
        }

    val isUrgentOnArrival: Boolean get() = type.isUrgentOnArrival

    val displayIcon: String
        get() = when (type) {
            CheckInMessageType.started -> "shield_checkered"
            CheckInMessageType.update -> "location_on"
            CheckInMessageType.arrived -> "verified_user"
            CheckInMessageType.extended -> "schedule"
            CheckInMessageType.cancelled -> "cancel"
            CheckInMessageType.warning -> "warning"
            CheckInMessageType.emergency -> "sos"
        }
}
