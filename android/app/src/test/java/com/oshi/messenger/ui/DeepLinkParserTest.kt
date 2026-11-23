package com.oshi.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [DeepLinkParser] — the Android port of iOS
 * `SharedContentHandler.handleURL` (OSHI/SharedContentHandler.swift:51-160).
 *
 * The parser deliberately avoids `android.net.Uri` so these run without a
 * device or Robolectric.
 *
 * iOS reference truth for the accepted host set:
 *   OSHI/OSHI.entitlements:13-17  → applinks:oshi-messenger.com + .org
 *   OSHI/SharedContentHandler.swift:64-65 → both + their www. forms
 */
class DeepLinkParserTest {

    private val KEY = "MCowBQYDK2VuAyEAabcdefghijklmnopqrstuvwxyz0123456789+/="

    // ────────────────────────────────────────────────────────────────────
    // HOSTS — the .org regression that dropped the app's OWN links
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `every host iOS accepts is accepted`() {
        for (host in listOf(
            "oshi-messenger.com", "www.oshi-messenger.com",
            "oshi-messenger.org", "www.oshi-messenger.org"
        )) {
            val link = DeepLinkParser.parse("https://$host/add?key=abcdefghijklmnopqrstuvwx")
            assertEquals(
                "host $host must be accepted",
                OshiDeepLink.AddContact("abcdefghijklmnopqrstuvwx"),
                link
            )
        }
    }

    /** The app generates .org links (NewMessageScreen.kt:814, QRCodeScreen.kt:188). */
    @Test
    fun `org group invite is not dropped`() {
        assertEquals(
            OshiDeepLink.GroupInvite,
            DeepLinkParser.parse("https://oshi-messenger.org/group?gid=550e8400-e29b-41d4-a716-446655440000")
        )
    }

    @Test
    fun `foreign hosts are rejected`() {
        for (url in listOf(
            "https://evil.com/add?key=$KEY",
            "https://oshi-messenger.com.evil.com/add?key=$KEY",
            "https://notoshi-messenger.org/add?key=$KEY",
            "https://oshi-messenger.net/add?key=$KEY"
        )) {
            assertEquals("must reject $url", OshiDeepLink.NotOurs, DeepLinkParser.parse(url))
        }
    }

    @Test
    fun `host match is case insensitive`() {
        assertEquals(
            OshiDeepLink.AddContact("abcdefghijklmnopqrstuvwx"),
            DeepLinkParser.parse("https://OSHI-Messenger.ORG/add?key=abcdefghijklmnopqrstuvwx")
        )
    }

    @Test
    fun `port and userinfo do not defeat the host check`() {
        assertTrue(DeepLinkParser.parse("https://oshi-messenger.org:443/add?key=x") is OshiDeepLink.AddContact)
        assertTrue(DeepLinkParser.parse("https://user@oshi-messenger.org/add?key=x") is OshiDeepLink.AddContact)
        assertEquals(
            OshiDeepLink.NotOurs,
            DeepLinkParser.parse("https://oshi-messenger.org@evil.com/add?key=x")
        )
    }

    /** iOS only handles the `https` scheme (SharedContentHandler.swift:62). */
    @Test
    fun `plain http is not ours`() {
        assertEquals(
            OshiDeepLink.NotOurs,
            DeepLinkParser.parse("http://oshi-messenger.org/add?key=$KEY")
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /add
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `add via custom scheme`() {
        assertEquals(
            OshiDeepLink.AddContact("abc123"),
            DeepLinkParser.parse("oshi://add?key=abc123")
        )
    }

    @Test
    fun `add accepts the short k alias like iOS`() {
        assertEquals(
            OshiDeepLink.AddContact("abc123"),
            DeepLinkParser.parse("https://oshi-messenger.org/add?k=abc123")
        )
    }

    /**
     * The real link the app shares is URL-encoded base64 — `+`, `/` and `=`
     * arrive as %2B / %2F / %3D and MUST round-trip exactly.
     */
    @Test
    fun `percent encoded base64 key round trips`() {
        val encoded = java.net.URLEncoder.encode(KEY, "UTF-8")
        assertEquals(
            OshiDeepLink.AddContact(KEY),
            DeepLinkParser.parse("https://oshi-messenger.org/add?key=$encoded")
        )
    }

    /**
     * A bare `+` in the query is a base64 plus, not a space — matching iOS
     * `URLComponents.queryItems`. `URLDecoder.decode` would have corrupted it.
     */
    @Test
    fun `literal plus stays a plus`() {
        assertEquals(
            OshiDeepLink.AddContact("ab+cd/ef="),
            DeepLinkParser.parse("oshi://add?key=ab+cd%2Fef%3D")
        )
    }

    @Test
    fun `add with extra params still finds the key`() {
        assertEquals(
            OshiDeepLink.AddContact("abc"),
            DeepLinkParser.parse("https://oshi-messenger.com/add?src=qr&key=abc&v=2")
        )
    }

    @Test
    fun `add without a key is unhandled not a crash`() {
        assertEquals(OshiDeepLink.Unhandled("add"), DeepLinkParser.parse("https://oshi-messenger.org/add"))
        assertEquals(OshiDeepLink.Unhandled("add"), DeepLinkParser.parse("https://oshi-messenger.org/add?key="))
        assertEquals(OshiDeepLink.Unhandled("add"), DeepLinkParser.parse("https://oshi-messenger.org/add?other=1"))
        assertEquals(OshiDeepLink.Unhandled("add"), DeepLinkParser.parse("oshi://add"))
    }

    @Test
    fun `fragment is stripped before parsing`() {
        assertEquals(
            OshiDeepLink.AddContact("abc"),
            DeepLinkParser.parse("https://oshi-messenger.org/add?key=abc#section")
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /chat
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `chat route carries the key`() {
        assertEquals(
            OshiDeepLink.OpenChat("abc123"),
            DeepLinkParser.parse("https://oshi-messenger.org/chat?key=abc123")
        )
        assertEquals(
            OshiDeepLink.OpenChat("abc123"),
            DeepLinkParser.parse("oshi://chat?k=abc123")
        )
    }

    @Test
    fun `chat without a key is unhandled`() {
        assertEquals(OshiDeepLink.Unhandled("chat"), DeepLinkParser.parse("oshi://chat"))
    }

    // ────────────────────────────────────────────────────────────────────
    // /group — all four shapes iOS supports
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `all four group invite shapes route to GroupInvite`() {
        val gid = "550e8400-e29b-41d4-a716-446655440000"
        for (url in listOf(
            "oshi://group/join?gid=$gid&name=Test&inviter=abc",
            "https://oshi-messenger.com/group?gid=$gid&name=Test&inviter=abc",
            "oshi://group/$gid",
            "https://oshi-messenger.org/group/$gid"
        )) {
            assertEquals("must route $url", OshiDeepLink.GroupInvite, DeepLinkParser.parse(url))
        }
    }

    /**
     * Validation of the gid lives in MainActivity.handleGroupInviteUri; the
     * parser's job is only to route it there — a bad gid must NOT be silently
     * dropped at the parse step, or the "invalid invite" dialog never shows.
     */
    @Test
    fun `group with a bad gid still routes to GroupInvite`() {
        assertEquals(
            OshiDeepLink.GroupInvite,
            DeepLinkParser.parse("https://oshi-messenger.org/group?gid=not-a-uuid")
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // share / unknown / malformed
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `share route is recognised`() {
        assertEquals(OshiDeepLink.Share, DeepLinkParser.parse("oshi://share"))
    }

    @Test
    fun `unknown routes report their route name`() {
        assertEquals(OshiDeepLink.Unhandled("bots"), DeepLinkParser.parse("https://oshi-messenger.org/bots"))
        assertEquals(OshiDeepLink.Unhandled("wat"), DeepLinkParser.parse("oshi://wat"))
    }

    @Test
    fun `malformed input never throws`() {
        for (url in listOf(
            null, "", "   ", "://", "oshi", "oshi:", "oshi:add?key=x",
            "https://", "https://oshi-messenger.org", "https://oshi-messenger.org/",
            "not a url at all", "%%%", "https://oshi-messenger.org/add?key=%zz",
            "javascript:alert(1)", "file:///etc/passwd"
        )) {
            // Must return, not throw.
            DeepLinkParser.parse(url)
        }
    }

    @Test
    fun `unparseable percent escape falls back to the raw value`() {
        // %zz is not a valid escape — iOS would reject the URL; we keep the raw
        // text rather than crashing, and the >=20-char guard in the dialog
        // rejects it downstream.
        val link = DeepLinkParser.parse("https://oshi-messenger.org/add?key=%zz")
        assertEquals(OshiDeepLink.AddContact("%zz"), link)
    }

    @Test
    fun `empty root path on our host is not ours`() {
        assertEquals(OshiDeepLink.NotOurs, DeepLinkParser.parse("https://oshi-messenger.org/"))
        assertEquals(OshiDeepLink.NotOurs, DeepLinkParser.parse("https://oshi-messenger.org"))
    }

    @Test
    fun `query only param name matching is exact`() {
        // "keys" must not satisfy a lookup for "key"
        assertEquals(
            OshiDeepLink.Unhandled("add"),
            DeepLinkParser.parse("https://oshi-messenger.org/add?keys=abc")
        )
    }
}
