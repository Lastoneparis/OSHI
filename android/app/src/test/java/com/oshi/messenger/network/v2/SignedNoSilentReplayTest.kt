package com.oshi.messenger.network.v2

import com.oshi.messenger.SourceText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * __SIGNED_NO_SILENT_REPLAY_2026_11_18__ A signed /v2 request is never re-sent by
 * the HTTP stack behind the app's back (same signature + timestamp ⇒ "replayed" on
 * the relay); retries happen in the app, which signs each attempt afresh.
 */
class SignedNoSilentReplayTest {

    @Test
    fun theSharedSettingsTurnOffTransportRetriesAndRedirects() {
        val c = OkHttpClient.Builder().noSilentReplay().build()
        assertFalse(c.retryOnConnectionFailure)
        assertFalse(c.followRedirects)
        assertFalse(c.followSslRedirects)
    }

    @Test
    fun aDroppedConnectionIsNotReplayedByTheHttpStack() {
        val server = MockWebServer()
        // Request read, then the socket closes with no response: the classic
        // "middlebox dropped the reply" case that made OkHttp re-send.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        try {
            val client = OkHttpClient.Builder().noSilentReplay().readTimeout(5, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(server.url("/v2/messages"))
                .header("x-oshi-timestamp", "1700000000000").header("x-oshi-signature", "sig")
                .post("{\"x\":1}".toRequestBody("application/json".toMediaType())).build()
            try {
                client.newCall(req).execute().close()
                fail("the dropped request must surface as an error, not a silent retry")
            } catch (_: IOException) { }
            assertEquals("exactly one copy of the signed request reached the server", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun everySignedV2ClientUsesTheSharedSettings() {
        for (f in listOf("V2MessagesClient", "V2BlobClient", "V2AccountClient", "V2DeviceClient", "V2KeysClient", "V2Config")) {
            val src = SourceText.stripComments(SourceText.read("src/main/java/com/oshi/messenger/network/v2/$f.kt"))
            assertTrue("$f builds its OkHttpClient with noSilentReplay()", src.contains(".noSilentReplay()"))
        }
    }

    @Test
    fun theMessageSendSignsEveryAttemptAfresh() {
        // App-level retry loop: V2Signer.sign is called INSIDE the attempt loop, so
        // each attempt carries a new timestamp (never the first attempt's headers).
        val src = SourceText.stripComments(SourceText.read("src/main/java/com/oshi/messenger/network/v2/V2MessagesClient.kt"))
        val send = src.substring(src.indexOf("suspend fun send("))
        // __GROUP_FANOUT_2026_11_24__ the loop is RateLimitBackoff.retrying; `call` is one attempt.
        val loop = send.indexOf("RateLimitBackoff.retrying(")
        val call = send.indexOf("call = {", loop)
        val sign = send.indexOf("signer.sign(\"POST\"")
        val status = send.indexOf("status = {", loop)
        assertTrue("send() retries in a loop", loop >= 0 && call > loop)
        assertTrue("…and signs inside each attempt", sign > call && sign < status)
    }
}
