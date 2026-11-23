package com.oshi.messenger

import com.oshi.messenger.data.model.CheckInMessage
import com.oshi.messenger.data.model.CheckInMessageType
import com.oshi.messenger.data.model.EmergencyData
import com.oshi.messenger.data.model.TransportMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the check-in wire format is cross-compatible with the shipping iOS app.
 *
 * iOS CheckInMessage/EmergencyData encode their Date fields as Apple reference-date
 * seconds (seconds since 2001-01-01) via the default JSONEncoder. Android stores
 * Unix millis internally and must convert at the wire boundary; otherwise the
 * safety-critical arrival deadline that arms the emergency alert is decades off.
 */
class CheckInWireFormatTest {

    // 2024-06-01T00:00:00Z in Unix millis (a plausible ETA).
    private val etaUnixMillis = 1717200000000L
    private val nowUnixMillis = 1717196400000L // 1h earlier
    private val APPLE_OFFSET = 978307200.0

    private fun payloadJson(msg: CheckInMessage): JSONObject =
        JSONObject(msg.toMessageString().removePrefix(CheckInMessage.MESSAGE_PREFIX))

    @Test
    fun `wire emits Apple-epoch seconds, not Unix millis`() {
        val msg = CheckInMessage(
            type = CheckInMessageType.started,
            sessionId = "s1",
            destinationAddress = "Home",
            transportMode = TransportMode.walking,
            estimatedArrival = etaUnixMillis,
            timestamp = nowUnixMillis
        )
        val json = payloadJson(msg)
        // Apple-epoch seconds today are ~7.7e8; Unix millis are ~1.7e12. Emitting a
        // value below the 1e11 detector proves we converted to the iOS time base.
        assertTrue("estimatedArrival must be Apple-epoch seconds", json.getDouble("estimatedArrival") < 1e11)
        assertTrue("timestamp must be Apple-epoch seconds", json.getDouble("timestamp") < 1e11)
        assertEquals(etaUnixMillis / 1000.0 - APPLE_OFFSET, json.getDouble("estimatedArrival"), 0.001)
    }

    @Test
    fun `round-trips its own wire format losslessly`() {
        val original = CheckInMessage(
            type = CheckInMessageType.extended,
            sessionId = "s2",
            estimatedArrival = etaUnixMillis,
            timestamp = nowUnixMillis
        )
        val decoded = CheckInMessage.fromMessageString(original.toMessageString())
        assertNotNull(decoded)
        assertEquals(etaUnixMillis, decoded!!.estimatedArrival)
        assertEquals(nowUnixMillis, decoded.timestamp)
        assertEquals(CheckInMessageType.extended, decoded.type)
    }

    @Test
    fun `decodes an iOS-style Apple-epoch payload into Unix millis`() {
        // Emulate exactly what an iPhone puts on the wire: Apple-epoch seconds.
        val appleSeconds = etaUnixMillis / 1000.0 - APPLE_OFFSET
        val iosPayload = JSONObject().apply {
            put("type", "started")
            put("sessionId", "ios1")
            put("estimatedArrival", appleSeconds)
            put("timestamp", nowUnixMillis / 1000.0 - APPLE_OFFSET)
        }
        val wire = CheckInMessage.MESSAGE_PREFIX + iosPayload.toString()
        val decoded = CheckInMessage.fromMessageString(wire)
        assertNotNull(decoded)
        // Within 1s: iOS carries sub-second precision we round to millis.
        assertEquals(etaUnixMillis.toDouble(), decoded!!.estimatedArrival!!.toDouble(), 1000.0)
    }

    @Test
    fun `tolerates a legacy Android payload that still carries Unix millis`() {
        // An old Android build (pre-fix) emits raw Unix millis. The tolerant decoder
        // must not mangle those into the far future.
        val legacyPayload = JSONObject().apply {
            put("type", "started")
            put("sessionId", "legacy1")
            put("estimatedArrival", etaUnixMillis)   // millis, > 1e11 threshold
            put("timestamp", nowUnixMillis)
        }
        val wire = CheckInMessage.MESSAGE_PREFIX + legacyPayload.toString()
        val decoded = CheckInMessage.fromMessageString(wire)
        assertNotNull(decoded)
        assertEquals(etaUnixMillis, decoded!!.estimatedArrival)
    }

    @Test
    fun `converts nested emergency timestamps across the wire`() {
        val emergency = EmergencyData(
            lastKnownLatitude = 46.2,
            lastKnownLongitude = 6.1,
            lastLocationTime = nowUnixMillis,
            batteryLevel = 0.42,
            isCharging = false,
            networkType = "Offline",
            deviceName = "Pixel",
            lastMovementTime = nowUnixMillis
        )
        val msg = CheckInMessage(
            type = CheckInMessageType.emergency,
            sessionId = "e1",
            emergencyData = emergency,
            timestamp = nowUnixMillis
        )
        val emo = payloadJson(msg).getJSONObject("emergencyData")
        assertTrue("nested lastLocationTime must be Apple seconds", emo.getDouble("lastLocationTime") < 1e11)

        val decoded = CheckInMessage.fromMessageString(msg.toMessageString())
        assertNotNull(decoded)
        assertEquals(nowUnixMillis, decoded!!.emergencyData!!.lastLocationTime)
        assertEquals(nowUnixMillis, decoded.emergencyData!!.lastMovementTime)
    }
}
