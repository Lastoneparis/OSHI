package com.oshi.messenger

import com.oshi.messenger.network.location.LocationMessage
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.service.NotificationPresentation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `📍LOCATION📍` payload is cross-compatible with the shipping iOS app,
 * in both directions.
 *
 * iOS encodes `LocationMessage` with a plain `JSONEncoder`, so its `Date` fields are
 * `.deferredToDate` — a Double counted in seconds from 2001-01-01, not from 1970 and
 * not ISO-8601 (`OSHI/LocationSharingManager.swift:791,863`). Its decoder expects a
 * number (`:1011`). A field renamed, an optional made required or a date encoded in
 * the wrong epoch is silent: the bubble simply never appears on the other phone.
 *
 * The factory helpers take an `android.location.Location`, which cannot be built in a
 * JVM unit test, so the payloads here are constructed field by field — the serializer
 * under test is the same one.
 */
class LocationWireFormatTest {

    private val appleOffset = 978307200.0

    /** The ten keys iOS's `LocationMessage` declares, and no others. */
    private val iosFields = setOf(
        "latitude", "longitude", "timestamp", "isLive", "expiresAt",
        "accuracy", "address", "sessionId", "isUpdate", "isStopped"
    )

    private fun oneTimeLike() = LocationMessage(
        latitude = 48.85661,
        longitude = 2.35222,
        timestamp = LocationMessage.nowAsAppleTimestamp(),
        isLive = false,
        expiresAt = null,
        accuracy = 12.5,
        address = "Rue de Rivoli, Paris, France",
        sessionId = null,
        isUpdate = false,
        isStopped = false
    )

    private fun payloadJson(msg: LocationMessage): JSONObject =
        JSONObject(msg.toMessageString().removePrefix(LocationMessage.MESSAGE_PREFIX))

    // ---------------------------------------------------------------- prefix

    @Test
    fun `prefix is the bare pushpin with no variation selector`() {
        val prefix = LocationMessage.MESSAGE_PREFIX
        assertEquals("📍LOCATION📍", prefix)
        assertFalse(
            "U+FE0F in the wire prefix would break every iOS decoder",
            prefix.contains('\uFE0F')
        )
        // Two constants exist; a drift between them would split senders from receivers.
        assertEquals(prefix, LocationSharingManager.MESSAGE_PREFIX)
    }

    @Test
    fun `encoded message carries the prefix then bare JSON`() {
        val wire = oneTimeLike().toMessageString()
        assertTrue(wire.startsWith(LocationMessage.MESSAGE_PREFIX))
        assertTrue(wire.removePrefix(LocationMessage.MESSAGE_PREFIX).startsWith("{"))
    }

    // ------------------------------------------------------------ encode side

    @Test
    fun `one-time share emits exactly the ten iOS fields`() {
        val json = payloadJson(oneTimeLike())
        assertEquals(iosFields, json.keys().asSequence().toSet())
    }

    @Test
    fun `timestamp is Apple-epoch seconds, not Unix seconds or millis`() {
        val msg = oneTimeLike()
        val json = payloadJson(msg)
        // Apple-epoch seconds are ~7.8e8 today; Unix seconds ~1.7e9, Unix millis ~1.7e12.
        assertTrue("timestamp must be Apple-epoch seconds", json.getDouble("timestamp") < 1.0e9)
        assertEquals(
            System.currentTimeMillis() / 1000.0 - appleOffset,
            json.getDouble("timestamp"),
            5.0
        )
    }

    @Test
    fun `expiresAt is emitted as explicit null when absent`() {
        // iOS omits the key (`encodeIfPresent`) but its `decodeIfPresent(Date)`
        // tolerates an explicit null, so emitting one stays compatible.
        val json = payloadJson(oneTimeLike())
        assertTrue(json.has("expiresAt"))
        assertTrue(json.isNull("expiresAt"))
    }

    @Test
    fun `live share expires exactly duration seconds after its timestamp`() {
        val now = LocationMessage.nowAsAppleTimestamp()
        val msg = oneTimeLike().copy(isLive = true, expiresAt = now + 900.0, timestamp = now)
        val json = payloadJson(msg)
        assertTrue(json.getBoolean("isLive"))
        assertEquals(900.0, json.getDouble("expiresAt") - json.getDouble("timestamp"), 0.001)
    }

    @Test
    fun `update and stop flags are always emitted, never omitted as defaults`() {
        // iOS decodes them with `decodeIfPresent ?? false`, so omission would work —
        // but `encodeDefaults` keeps the payload self-describing for the silent-push
        // predicate below, which reads them off the wire.
        val json = payloadJson(oneTimeLike())
        assertTrue(json.has("isUpdate"))
        assertTrue(json.has("isStopped"))
        assertFalse(json.getBoolean("isUpdate"))
        assertFalse(json.getBoolean("isStopped"))
    }

    // ------------------------------------------------------------ decode side

    @Test
    fun `round-trips its own wire format losslessly`() {
        val original = LocationMessage(
            latitude = -33.86785,
            longitude = 151.20732,
            timestamp = 776000000.0,
            isLive = true,
            expiresAt = 776003600.0,
            accuracy = 8.0,
            address = "Sydney, Australia",
            sessionId = "8f3c1d2e-0000-4444-8888-abcdefabcdef",
            isUpdate = true,
            isStopped = false
        )
        val decoded = LocationMessage.fromMessageString(original.toMessageString())
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodes an iOS payload that omits every optional field`() {
        // What a Swift encoder produces for a one-time share: no `expiresAt`,
        // no `accuracy`, no `address`, no `sessionId`, and `isUpdate`/`isStopped`
        // absent because iOS only reads them with decodeIfPresent.
        val wire = LocationMessage.MESSAGE_PREFIX +
            """{"latitude":48.85661,"longitude":2.35222,"timestamp":776000000.5,"isLive":false}"""
        val decoded = LocationMessage.fromMessageString(wire)
        assertNotNull(decoded)
        assertEquals(48.85661, decoded!!.latitude, 1e-9)
        assertEquals(2.35222, decoded.longitude, 1e-9)
        assertEquals(776000000.5, decoded.timestamp, 1e-9)
        assertFalse(decoded.isLive)
        assertNull(decoded.expiresAt)
        assertNull(decoded.accuracy)
        assertNull(decoded.address)
        assertNull(decoded.sessionId)
        assertFalse(decoded.isUpdate)
        assertFalse(decoded.isStopped)
    }

    @Test
    fun `decodes explicit nulls for the optional fields`() {
        val wire = LocationMessage.MESSAGE_PREFIX +
            """{"latitude":1.0,"longitude":2.0,"timestamp":776000000.0,"isLive":true,""" +
            """"expiresAt":null,"accuracy":null,"address":null,"sessionId":null}"""
        val decoded = LocationMessage.fromMessageString(wire)
        assertNotNull(decoded)
        assertNull(decoded!!.expiresAt)
        assertNull(decoded.sessionId)
    }

    @Test
    fun `unknown fields from a newer peer are ignored, not fatal`() {
        val wire = LocationMessage.MESSAGE_PREFIX +
            """{"latitude":1.0,"longitude":2.0,"timestamp":776000000.0,"isLive":false,"altitude":33.0}"""
        assertNotNull(LocationMessage.fromMessageString(wire))
    }

    @Test
    fun `content without the prefix is not a location message`() {
        assertNull(LocationMessage.fromMessageString("hello"))
        assertNull(LocationMessage.fromMessageString("""{"latitude":1.0}"""))
    }

    // ---------------------------------------------------------- epoch helpers

    @Test
    fun `Apple timestamp round-trips through Unix millis`() {
        val nowMillis = System.currentTimeMillis()
        val apple = LocationMessage.nowAsAppleTimestamp()
        val back = LocationMessage.appleTimestampToUnixMillis(apple)
        assertTrue("round-trip drifted by ${back - nowMillis} ms", Math.abs(back - nowMillis) < 5_000)
    }

    @Test
    fun `legacy Android payloads carrying Unix millis are still readable`() {
        // Old Android builds put `System.currentTimeMillis()` in `timestamp`. Values
        // above 1e10 cannot be Apple-epoch seconds (that would be the year 2318), so
        // they are read as Unix millis rather than decoded as a date in year 55800.
        val unixMillis = 1_717_200_000_000.0
        assertEquals(unixMillis.toLong(), LocationMessage.appleTimestampToUnixMillis(unixMillis))
    }

    // --------------------------------------------------- silent-push predicate

    @Test
    fun `only the periodic ping is silent - start and stop both notify`() {
        val base = oneTimeLike().copy(isLive = true, sessionId = "s1", expiresAt = 776003600.0)

        val initial = base.copy(isUpdate = false, isStopped = false)
        val update = base.copy(isUpdate = true, isStopped = false)
        val stopped = base.copy(isUpdate = true, isStopped = true)

        assertFalse("the initial share must notify once", initial.isSilentForPush)
        assertTrue("every ~10s ping must not raise a banner", update.isSilentForPush)
        // ⚠️ The one that is easy to get wrong: `LocationMessage.stopped` builds the
        // STOP signal with isUpdate = true as well (it rides the same in-place-update
        // path on the receiver), so a rule that silences on `isUpdate` alone silences
        // the END of a share too and the peer never learns the dot went dead.
        assertFalse("the stop signal must notify", stopped.isSilentForPush)

        assertFalse(LocationMessage.isSilentPushContent(initial.toMessageString()))
        assertTrue(LocationMessage.isSilentPushContent(update.toMessageString()))
        assertFalse(LocationMessage.isSilentPushContent(stopped.toMessageString()))
    }

    @Test
    fun `the real stopped payload notifies too`() {
        // Not the hand-built copy above but the factory the app actually sends.
        val stopped = LocationMessage(
            latitude = 48.85,
            longitude = 2.35,
            timestamp = 776003600.0,
            isLive = true,
            expiresAt = 776003600.0,
            sessionId = "s1",
            isUpdate = true,
            isStopped = true
        )
        assertFalse(stopped.isSilentForPush)
        assertFalse(LocationMessage.isSilentPushContent(stopped.toMessageString()))
    }

    @Test
    fun `a stopped share has no remaining time even when expiresAt is still ahead`() {
        // An iPhone's STOP keeps the share's ORIGINAL expiresAt, so the raw gap is
        // still positive; the countdown must not keep running off it.
        val farFuture = (System.currentTimeMillis() / 1000.0) - 978307200.0 + 3600.0
        val live = oneTimeLike().copy(isLive = true, sessionId = "s1", expiresAt = farFuture)

        assertNotNull("a running share still counts down", live.remainingTimeSeconds)
        assertNull(
            "a stopped share has no remaining time",
            live.copy(isUpdate = true, isStopped = true).remainingTimeSeconds
        )
        assertTrue(live.copy(isUpdate = true, isStopped = true).isExpired)
    }

    /**
     * The receiving half of the same rule.
     *
     * `📍LOCATION📍` is a RENDERED catalog entry — correctly, because the start, an
     * extension and the stop are all things to be told about. But the ~10 s position
     * pings share that sentinel and are told apart only inside the JSON, so a decider
     * that stops at the catalog turns a fifteen-minute live share into ninety
     * "📍 Sharing live location" banners. The send side suppresses them; this object
     * exists because a peer on an older build, or a relay that pushes on its own, can
     * still deliver one.
     */
    @Test
    fun `a live-location ping raises no banner, but the start and the stop do`() {
        val base = oneTimeLike().copy(isLive = true, sessionId = "s1", expiresAt = 776003600.0)

        val initial = base.copy(isUpdate = false, isStopped = false).toMessageString()
        val ping = base.copy(isUpdate = true, isStopped = false).toMessageString()
        val stopped = base.copy(isUpdate = true, isStopped = true).toMessageString()

        assertEquals(
            NotificationPresentation.Decision.Suppress,
            NotificationPresentation.decide(ping)
        )
        assertTrue(
            "the initial share must still raise one banner",
            NotificationPresentation.decide(initial) is NotificationPresentation.Decision.Show
        )
        assertTrue(
            "the end of a share must be announced too",
            NotificationPresentation.decide(stopped) is NotificationPresentation.Decision.Show
        )
    }

    @Test
    fun `the banner for a stopped share does not claim the share is live`() {
        val base = oneTimeLike().copy(isLive = true, sessionId = "s1", expiresAt = 776003600.0)
        val labels = com.oshi.messenger.data.model.OSHIControlPayload.Labels(
            locationLive = "L_LIVE",
            locationShared = "L_SHARED"
        )
        // `LocationMessage.stopped` carries no address, as the real factory does.
        val stopped = base.copy(isUpdate = true, isStopped = true, address = null).toMessageString()
        val live = base.copy(isUpdate = false, isStopped = false, address = null).toMessageString()

        assertEquals(
            NotificationPresentation.Decision.Show("📍 L_LIVE"),
            NotificationPresentation.decide(live, labels)
        )
        assertEquals(
            NotificationPresentation.Decision.Show("📍 L_SHARED"),
            NotificationPresentation.decide(stopped, labels)
        )
    }

    @Test
    fun `a one-time share notifies`() {
        assertFalse(oneTimeLike().isSilentForPush)
        assertFalse(LocationMessage.isSilentPushContent(oneTimeLike().toMessageString()))
    }

    @Test
    fun `non-location content is never treated as a silent location ping`() {
        assertFalse(LocationMessage.isSilentPushContent("Meet me at the station"))
        assertFalse(LocationMessage.isSilentPushContent("📬DELIVERY_RECEIPT📬{}"))
    }

    @Test
    fun `the silent predicate cannot be replaced by a prefix match`() {
        // Both an initial share and an update carry the same prefix; only the decoded
        // payload separates them. This is why MessageRepository needs the extra term
        // next to its static V2_SILENT_CONTROL_PREFIXES list.
        val base = oneTimeLike().copy(isLive = true, sessionId = "s1")
        val initial = base.toMessageString()
        val update = base.copy(isUpdate = true).toMessageString()
        assertTrue(initial.startsWith(LocationMessage.MESSAGE_PREFIX))
        assertTrue(update.startsWith(LocationMessage.MESSAGE_PREFIX))
        assertFalse(LocationMessage.isSilentPushContent(initial))
        assertTrue(LocationMessage.isSilentPushContent(update))
    }
}
