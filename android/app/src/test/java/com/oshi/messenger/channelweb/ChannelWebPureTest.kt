package com.oshi.messenger.channelweb

import com.oshi.messenger.data.model.GroupSettings
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.PollMessage
import com.oshi.messenger.network.v2.V2ChannelWebClient
import com.oshi.messenger.service.channelweb.ChannelWebContent
import com.oshi.messenger.service.channelweb.ChannelWebLinks
import com.oshi.messenger.service.channelweb.ChannelWebMatch
import com.oshi.messenger.service.channelweb.ChannelWebText
import com.oshi.messenger.ui.DeepLinkParser
import com.oshi.messenger.ui.OshiDeepLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __CHANNEL_WEB_2026_11_26__ links, embed code, deep links, formatting, matching, settings. */
class ChannelWebPureTest {

    // ---------------------------------------------------------------- links + embed (§8, §9.7)

    @Test fun linksAndEmbedSnippet() {
        assertEquals("https://oshi-messenger.com/c/swiss_quant", ChannelWebLinks.channelUrl("swiss_quant"))
        assertEquals("https://oshi-messenger.com/c/swiss_quant/7", ChannelWebLinks.postUrl("swiss_quant", 7))
        assertEquals("oshi://channel?h=swiss_quant&n=7", ChannelWebLinks.appLink("swiss_quant", 7))
        assertEquals(
            "<script async src=\"https://oshi-messenger.com/js/oshi-widget.js?1\" data-oshi-post=\"swiss_quant/7\" data-width=\"100%\"></script>",
            ChannelWebLinks.embedSnippet("swiss_quant", 7, dark = false),
        )
        assertEquals(
            "<script async src=\"https://oshi-messenger.com/js/oshi-widget.js?1\" data-oshi-post=\"swiss_quant/7\" data-width=\"100%\" data-dark=\"1\"></script>",
            ChannelWebLinks.embedSnippet("swiss_quant", 7, dark = true),
        )
    }

    @Test fun postNumbersAreStrict() {
        assertEquals(7, ChannelWebLinks.parseN("7"))
        assertEquals(1234567890, ChannelWebLinks.parseN("1234567890"))
        for (bad in listOf(null, "", "0", "07", "-1", "1e3", "abc", "9999999999", "12345678901")) assertNull(bad, ChannelWebLinks.parseN(bad))
    }

    // ---------------------------------------------------------------- deep links (§9.8)

    @Test fun deepLinks() {
        fun p(s: String) = DeepLinkParser.parse(s)
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", 7), p("https://oshi-messenger.com/c/swiss_quant/7"))
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", 7), p("https://oshi-messenger.com/c/Swiss_Quant/7/"))
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", null), p("https://oshi-messenger.com/c/swiss_quant"))
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", 7), p("https://www.oshi-messenger.com/c/swiss_quant/7?embed=1#x"))
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", 12), p("oshi://channel?h=swiss_quant&n=12"))
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", null), p("oshi://channel?h=swiss_quant"))
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", null), p("oshi://channel?h=swiss_quant&n=abc"))
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", 3), p("oshi://c/swiss_quant/3"))
        // The page's own "Open in OSHI" link is what the parser understands.
        assertEquals(OshiDeepLink.PublicChannel("swiss_quant", 7), p(ChannelWebLinks.appLink("swiss_quant", 7)))
        // Images are web resources: handed back to a browser, never "a post".
        val media = "https://oshi-messenger.com/c/swiss_quant/media/3f2a9c0d4e5b6a7182930a1b2c3d4e5f"
        assertEquals(OshiDeepLink.OpenInBrowser(media), p(media))
        assertTrue(p("oshi://channel?h=no") is OshiDeepLink.Unhandled)          // invalid handle (too short)
        assertTrue(p("oshi://channel") is OshiDeepLink.Unhandled)
        assertTrue(p("https://oshi-messenger.com/c/") is OshiDeepLink.Unhandled)
        assertTrue(p("https://evil.example/c/swiss_quant/7") is OshiDeepLink.NotOurs)
        // Existing routes unchanged.
        assertEquals(OshiDeepLink.PublicGroup("swiss_quant"), p("https://oshi-messenger.com/g/swiss_quant"))
    }

    // ---------------------------------------------------------------- formatting (§5)

    @Test fun appMarkupBecomesWebMarkup() {
        fun w(s: String) = ChannelWebText.toWeb(s)
        // composer markers: ** bold, __ underline (no web underline → plain, as on iOS)
        assertEquals("**bold** and under", w("**bold** and __under__"))
        // iOS single-char markup: *x* is BOLD in the app (italic on the web) → written as **x**
        assertEquals("**b** _i_ s c", w("*b* _i_ ~s~ `c`"))
        // whitespace stays outside the markers
        assertEquals("a **x** b", w("a **x** b"))
        // nested
        assertEquals("**both**", w("**__both__**"))
        assertEquals("**_both_**", w("**_both_**"))
        // literal stays literal
        assertEquals("snake_case_name stays, 2*3*4 stays", w("snake_case_name stays, 2*3*4 stays"))
        assertEquals("see https://x.org/a__b__c.", w("see https://x.org/a__b__c."))
        assertEquals("<b>\"hi\"</b> & co", w("<b>\"hi\"</b> & co"))
        assertEquals("line1\nline2", w("line1\nline2"))
        // bold mid-word survives (the web's ** needs no boundary); italic mid-word cannot open → plain
        assertEquals("x**y**z", w("x**y**z"))
        assertEquals("xyz", w("x__y__z"))
        // a bold link stays a bold link
        assertEquals("**https://x.org**", w("**https://x.org**"))
    }

    @Test fun cleanAndLimitMirrorTheServer() {
        assertEquals("a\nb", ChannelWebText.clean("  a\r\nb ‮ "))
        assertEquals("a\n\n\nb", ChannelWebText.clean("a\n\n\n\n\n\nb"))
        val long = "😀".repeat(5000)
        val cut = ChannelWebText.limit(long)
        assertEquals(ChannelWebText.MAX_CODE_POINTS, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith("…"))
        assertEquals("ok", ChannelWebText.limit("ok"))
    }

    // ---------------------------------------------------------------- what is published (§9.2)

    @Test fun contentPlan() {
        assertEquals(ChannelWebContent.Plan("**hi**", false), ChannelWebContent.plan("*hi*", null))
        assertNull(ChannelWebContent.plan("   ", null))
        assertEquals(ChannelWebContent.Plan("cap", true), ChannelWebContent.plan("cap", MediaType.IMAGE))
        assertEquals(ChannelWebContent.Plan("", true), ChannelWebContent.plan("", MediaType.IMAGE))
        // video / voice / file: the caption alone (iOS parity), nothing without one
        assertEquals(ChannelWebContent.Plan("watch", false), ChannelWebContent.plan("watch", MediaType.VIDEO))
        assertNull(ChannelWebContent.plan("", MediaType.AUDIO))
        assertNull(ChannelWebContent.plan("Alice", MediaType.CONTACT))
        assertNull(ChannelWebContent.plan("here", MediaType.LOCATION))
        val poll = PollMessage(question = "Q?", options = listOf("a", "b"), allowsMultipleAnswers = false, isAnonymous = false).toMessageString()
        assertNull(ChannelWebContent.plan(poll, null))
    }

    // ---------------------------------------------------------------- reader n lookup

    @Test fun readersMatchThePublicFeedByTime() {
        val web = listOf(
            V2ChannelWebClient.Companion.PublicPost(3, "third", 3_000_000L, 0),
            V2ChannelWebClient.Companion.PublicPost(2, "**second**", 2_000_000L, 0),
            V2ChannelWebClient.Companion.PublicPost(1, "", 1_000_000L, 1),
        )
        val local = listOf(
            ChannelWebMatch.Local("before-web", 500_000L, "not published"),
            ChannelWebMatch.Local("m1", 1_000_001L, ""),              // ±1 ms: rounding between platforms
            ChannelWebMatch.Local("noise", 2_000_000L - 900, "another"),
            ChannelWebMatch.Local("m2", 2_001_500L, "*second*"),      // 1.5 s off but same text
            ChannelWebMatch.Local("m3x", 2_999_000L, "other text"),   // 1 s off, different text: no
        )
        assertEquals(mapOf("m1" to 1, "m2" to 2), ChannelWebMatch.match(local, web))
    }

    // ---------------------------------------------------------------- settings travel E2E

    @Test fun webPreviewSettingRoundTripsAndNeedsAPublicChannel() {
        val on = GroupSettings(visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant",
            postPolicy = GroupSettings.PostPolicy.ADMINS, webPreview = true, webIndexing = true)
        assertTrue(on.webEligible); assertTrue(on.webActive)
        val back = GroupSettings.parse(JSONObject(on.toJson().toString()))
        assertEquals(on, back)
        // Every subkey is emitted (GROUP_INFO_SETTINGS_SPEC §2.13, iOS does the same).
        val off = on.copy(webPreview = false, webIndexing = false).toJson()
        assertEquals(false, off.get("webPreview")); assertEquals(false, off.get("webIndexing"))
        // Old builds' settings (no key) ⇒ off.
        assertFalse(GroupSettings.parse(JSONObject("""{"visibility":"public","handle":"swiss_quant","postPolicy":"admins"}""")).webPreview)
        // Indexing alone means nothing.
        assertFalse(GroupSettings.parse(JSONObject("""{"webIndexing":true}""")).webIndexing)
        // Not a channel / not public / dissolved ⇒ not eligible.
        assertFalse(on.copy(postPolicy = GroupSettings.PostPolicy.EVERYONE).webActive)
        assertFalse(on.copy(visibility = GroupSettings.Visibility.PRIVATE, handle = null).webActive)
        assertFalse(on.copy(dissolved = true).webActive)
    }
}
