package com.oshi.messenger

import com.oshi.messenger.util.InviteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __THE_INVITE_LINK_POINTED_AT_THE_UNVERIFIABLE_HOST_2026_11_07__
 *
 * Anatole N5: "the invite link does not work when it is sent in a conversation".
 *
 * [InviteLink.HOST] was `oshi-messenger.org`, and that host cannot be verified
 * by either platform. Measured on 2026-11-07:
 *
 *     https://oshi-messenger.org/.well-known/assetlinks.json              -> 301
 *     https://oshi-messenger.org/.well-known/apple-app-site-association   -> 301
 *     https://oshi-messenger.com/.well-known/assetlinks.json              -> 200
 *
 * Android App Links verification does not follow redirects, and neither does
 * Apple's CDN. A 301 is a verification failure, so the OS never binds `.org` to
 * the app and the link opens a browser. The GROUP invite already used `.com`,
 * which is why group links worked and contact links did not.
 *
 * Being listed in the manifest is necessary and NOT sufficient — `.org` is
 * listed there too. That distinction is the whole bug.
 */
class InviteLinkHostTest {

    @Test
    fun `the invite host is the one that actually verifies`() {
        assertEquals(
            "the invite host must serve its assetlinks.json directly; .org 301-redirects it",
            "oshi-messenger.com",
            InviteLink.HOST,
        )
    }

    @Test
    fun `the emitted link uses that host`() {
        val url = InviteLink.url("abc+def/ghi=")
        assertTrue("no link produced", url != null)
        assertTrue("the link is not on the verifying host: $url",
                   url!!.startsWith("https://oshi-messenger.com/add?key="))
    }

    /**
     * Both hosts must stay in the manifest. Receiving is deliberately wider
     * than sending: links already sent sit in people's chat histories.
     */
    @Test
    fun `the manifest still claims both hosts`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        for (host in listOf("oshi-messenger.com", "oshi-messenger.org")) {
            assertTrue(
                "$host is no longer claimed — invites already sent to it stop opening the app",
                manifest.contains("android:host=\"$host\"") &&
                    manifest.contains("android:pathPrefix=\"/add\""),
            )
        }
    }
}
