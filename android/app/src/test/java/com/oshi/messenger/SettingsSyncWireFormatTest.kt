package com.oshi.messenger

import com.oshi.messenger.service.MultiDeviceSyncManager
import com.oshi.messenger.ui.screens.normalizeScannedContactPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format guards for the server-mediated multi-device sync archive and for
 * the contact-QR payload. Both are cross-platform surfaces: the peer decoding
 * them may be an iPhone running `MultiDeviceSyncManager.swift` /
 * `QRScannerView.swift`.
 */
class SettingsSyncWireFormatTest {

    // -----------------------------------------------------------------------
    // ROUTE KEY ENCODING — iOS urlEncodedKey (swift:449-454)
    // -----------------------------------------------------------------------

    @Test
    fun `sync route key is base64url with padding stripped`() {
        assertEquals("ab-cd_ef", MultiDeviceSyncManager.urlEncodedKey("ab+cd/ef"))
        assertEquals("abcd", MultiDeviceSyncManager.urlEncodedKey("abcd=="))
        assertEquals(
            "MC4wLTEyMw",
            MultiDeviceSyncManager.urlEncodedKey("MC4wLTEyMw==")
        )
    }

    @Test
    fun `percent encoding would target a different storage slot`() {
        val raw = "a+b/c="
        val ios = MultiDeviceSyncManager.urlEncodedKey(raw)
        val percent = java.net.URLEncoder.encode(raw, "UTF-8")
        assertFalse("percent-encoding the key silently desyncs the devices", ios == percent)
    }

    // -----------------------------------------------------------------------
    // DATES — Swift JSONEncoder .deferredToDate = seconds since 2001-01-01
    // -----------------------------------------------------------------------

    @Test
    fun `apple reference time is offset from unix epoch by 978307200 seconds`() {
        // 2001-01-01T00:00:00Z is 0.0 in Apple's reference frame.
        assertEquals(0.0, MultiDeviceSyncManager.appleTimeFromEpochMillis(978_307_200_000L), 0.0001)
        // 1970-01-01 is negative there.
        assertEquals(-978_307_200.0, MultiDeviceSyncManager.appleTimeFromEpochMillis(0L), 0.0001)
    }

    @Test
    fun `apple time round trips through epoch millis`() {
        val ms = 1_786_961_472_345L
        val back = MultiDeviceSyncManager.epochMillisFromAppleTime(
            MultiDeviceSyncManager.appleTimeFromEpochMillis(ms)
        )
        assertTrue("drift ${Math.abs(back - ms)} ms", Math.abs(back - ms) <= 1)
    }

    @Test
    fun `epoch millis in the timestamp field would land in the far future on iOS`() {
        // The failure mode this guards: writing 1.7e12 where iOS expects ~7.9e8.
        val wrong = 1_700_000_000_000.0
        val years = (wrong - MultiDeviceSyncManager.appleTimeFromEpochMillis(1_700_000_000_000L)) /
            (365.0 * 24 * 3600)
        assertTrue("a raw-millis timestamp is ~$years years off", years > 20_000)
    }

    // -----------------------------------------------------------------------
    // UPLOAD ENVELOPE — iOS ["deviceId": …, "data": …] (swift:477-480)
    // -----------------------------------------------------------------------

    @Test
    fun `upload envelope has exactly deviceId and data`() {
        val j = MultiDeviceSyncManager.buildSyncUploadBody("dev-1", "Y2lwaGVy")
        assertEquals(2, j.length())
        assertEquals("dev-1", j.getString("deviceId"))
        assertEquals("Y2lwaGVy", j.getString("data"))
    }

    // -----------------------------------------------------------------------
    // SecureMessage — every non-optional iOS key must be present
    // -----------------------------------------------------------------------

    @Test
    fun `sync message carries every key the iOS decoder requires`() {
        val j = MultiDeviceSyncManager.messageToSyncJson(
            id = "m1",
            senderPublicKey = "SENDERKEY123456",
            recipientPublicKey = "RECIPKEY7890123",
            content = "hello",
            epochMillis = 1_700_000_000_000L,
            isRead = false,
            deliveryStatus = "delivered",
            mediaTypeRaw = null,
            mediaBase64 = null,
            editedAtMillis = null,
            editedContent = null,
            isDeletedForEveryone = false,
            replyToId = null
        )
        // MessageManager.swift:8018-8028 — these seven throw if absent.
        for (key in listOf(
            "id", "senderAddress", "recipientAddress", "encryptedContent",
            "timestamp", "isRead", "deliveryStatus"
        )) {
            assertTrue("missing required key $key", j.has(key))
        }
        // EncryptedMessage is itself non-optional and fully keyed (IdentityManager.swift:787-792)
        val enc = j.getJSONObject("encryptedContent")
        for (key in listOf("ciphertext", "signature", "senderPublicKey", "timestamp")) {
            assertTrue("missing encryptedContent.$key", enc.has(key))
        }
        // iOS renders plaintextContent, so that is where the text has to be.
        assertEquals("hello", j.getString("plaintextContent"))
        assertTrue(j.get("timestamp") is Number)
        // Addresses are the 8-char short form iOS builds (swift:1057-1058)
        assertEquals("SENDERKE", j.getString("senderAddress"))
        assertEquals("RECIPKEY", j.getString("recipientAddress"))
    }

    @Test
    fun `deliveryMethod is omitted rather than guessed`() {
        val j = MultiDeviceSyncManager.messageToSyncJson(
            "m", "a", "b", "x", 0L, false, "sent",
            null, null, null, null, false, null
        )
        assertFalse(
            "an iOS-unknown DeliveryMethod raw value risks discarding the message",
            j.has("deliveryMethod")
        )
    }

    @Test
    fun `location media type is dropped because iOS has no such case`() {
        // MediaManager.swift:37-43 has image/video/audio/document/contact only.
        assertEquals(null, MultiDeviceSyncManager.mediaTypeToIos("LOCATION"))
        assertEquals("document", MultiDeviceSyncManager.mediaTypeToIos("DOCUMENT"))
        assertEquals("image", MultiDeviceSyncManager.mediaTypeToIos("IMAGE"))
        assertEquals("DOCUMENT", MultiDeviceSyncManager.mediaTypeFromIos("document"))
        assertEquals(null, MultiDeviceSyncManager.mediaTypeFromIos("location"))
    }

    @Test
    fun `delivery status uses the iOS raw values`() {
        // MessageManager.swift:8122-8136
        assertEquals("failed", MultiDeviceSyncManager.deliveryStatusToIos(true, true, true, true))
        assertEquals("read", MultiDeviceSyncManager.deliveryStatusToIos(true, true, false, true))
        assertEquals("delivered", MultiDeviceSyncManager.deliveryStatusToIos(false, true, false, true))
        assertEquals("sent", MultiDeviceSyncManager.deliveryStatusToIos(false, false, false, true))
        for (s in listOf("failed", "read", "delivered", "sent", "pending")) {
            assertTrue(s in listOf("pending", "sent", "delivered", "read", "failed"))
        }
    }

    // -----------------------------------------------------------------------
    // CONTACT QR — iOS emits and expects the RAW key
    // -----------------------------------------------------------------------

    @Test
    fun `raw key scans through unchanged`() {
        val key = "MCowBQYDK2VuAyEAabcdefghijklmnopqrstuvwxyz0123456789AB="
        assertEquals(key, normalizeScannedContactPayload(key))
    }

    @Test
    fun `legacy oshi prefixed android codes still scan`() {
        assertEquals("ABCDEF", normalizeScannedContactPayload("oshi:ABCDEF"))
    }

    @Test
    fun `both link forms decode`() {
        assertEquals(
            "a+b/c=",
            normalizeScannedContactPayload("https://oshi-messenger.org/add?key=a%2Bb%2Fc%3D")
        )
        assertEquals(
            "a+b/c=",
            normalizeScannedContactPayload("oshi://add?key=a%2Bb%2Fc%3D")
        )
    }

    @Test
    fun `a scheme-style oshi url is not mistaken for the legacy prefix`() {
        // `oshi://group/…` must not become `//group/…`
        assertEquals("oshi://group/123", normalizeScannedContactPayload("oshi://group/123"))
    }
}
