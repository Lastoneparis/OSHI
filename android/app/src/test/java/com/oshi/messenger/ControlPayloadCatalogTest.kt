package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.OSHIControlPayload.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the Kotlin catalog is equivalent to `OSHI/OSHIControlPayload.swift`,
 * the single source of truth on the shipping iOS app.
 *
 * The reference table below is transcribed from Swift:82-111 — 29 entries, three
 * kinds, in that order. If iOS adds a marker and Android does not, `catalogMatchesIOS`
 * fails; that is the whole point of the file.
 */
class ControlPayloadCatalogTest {

    /** Swift:82-109, verbatim. */
    private val iosCatalog: List<Pair<String, Kind>> = listOf(
        "📞CALL_SIGNAL📞" to Kind.SILENT,              // 📞CALL_SIGNAL📞
        "📬DELIVERY_RECEIPT📬" to Kind.SILENT,         // 📬DELIVERY_RECEIPT📬
        "📖READ_RECEIPT📖" to Kind.SILENT,             // 📖READ_RECEIPT📖
        "⌨️TYPING⌨️" to Kind.SILENT,                   // ⌨️TYPING⌨️
        "✍️TYPING✍️" to Kind.SILENT,                   // ✍️TYPING✍️
        "🔗LINKS_VISIBILITY🔗" to Kind.SILENT,         // 🔗LINKS_VISIBILITY🔗
        "📸PROFILE_REQUEST📸" to Kind.SILENT,          // 📸PROFILE_REQUEST📸
        "🔧ACTION🔧" to Kind.SILENT,                   // 🔧ACTION🔧
        "📢GROUP_UPDATE📢" to Kind.SILENT,             // 📢GROUP_UPDATE📢
        // __POLLS_2026_10_23__ Swift:92. A ballot is SILENT: no bubble, and in a
        // group of twenty it must not be twenty banners.
        "📊POLL_VOTE📊" to Kind.SILENT,                // 📊POLL_VOTE📊
        "__SESSION_RESET__" to Kind.SILENT,
        // __GROUP_RECEIPTS_2026_11_02__ (N8) per-message, per-member "received"
        // and "read" for a GROUP message. SILENT: it is state about the
        // sender's own message, and a group of twenty must not produce twenty
        // bubbles — the same reasoning as the poll ballot above.
        "📑GROUP_RECEIPT📑" to Kind.SILENT,

        "📱CALL_SUMMARY📱" to Kind.RENDERED,           // 📱CALL_SUMMARY📱
        "📱MISSED_CALL📱" to Kind.RENDERED,            // 📱MISSED_CALL📱
        "📍LOCATION📍" to Kind.RENDERED,               // 📍LOCATION📍
        "📅SCHEDULED_CALL📅" to Kind.RENDERED,         // 📅SCHEDULED_CALL📅
        "🛡️CHECK_IN🛡️" to Kind.RENDERED,   // 🛡️CHECK_IN🛡️
        "🛡️CHECKIN🛡️" to Kind.RENDERED,    // 🛡️CHECKIN🛡️
        // __ALIVE_CHECK_IN_2026_11_04__ The recurring liveness confirmation, a
        // SEPARATE sentinel from the two journey check-ins above: that feature is
        // an ETA with a location, this one is a heartbeat with neither, and
        // sharing a prefix would route these events through CheckInMessage's
        // seven-type renderer. RENDERED, because a confirmation the reader cannot
        // find afterwards is one they must take on trust from a coloured dot.
        "💓ALIVE💓" to Kind.RENDERED,                 // 💓ALIVE💓
        "📸PROFILE_UPDATE📸" to Kind.RENDERED,         // 📸PROFILE_UPDATE📸
        "🎨WALLPAPER_UPDATE🎨" to Kind.RENDERED,       // 🎨WALLPAPER_UPDATE🎨
        "🔥REACTION🔥" to Kind.RENDERED,               // 🔥REACTION🔥
        "👍 REACTION👍" to Kind.RENDERED,              // 👍 REACTION👍
        "🔑KEY_ROTATION🔑" to Kind.RENDERED,           // 🔑KEY_ROTATION🔑
        // __BLOCKED_NOTICE_2026_09_18__ Swift:105. RENDERED, pas silencieux: tout
        // l'intérêt de l'avis est qu'il s'affiche chez la personne bloquée.
        "🚫BLOCKED🚫" to Kind.RENDERED,                // 🚫BLOCKED🚫
        // __POLLS_2026_10_23__ Swift:107. The poll itself RENDERS, and it is
        // worth waking someone for.
        "📊POLL📊" to Kind.RENDERED,                   // 📊POLL📊
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Swift:111. RENDERED: a change
        // to how a conversation is routed is something a reader should SEE.
        "🔒OFFLINE_ONLY🔒" to Kind.RENDERED,           // 🔒OFFLINE_ONLY🔒
        // __ADV_CHAT_PRIVACY_2026_11_26__ Advanced chat privacy. Catalog key carries
        // U+FE0F so its derived bare twin matches the wire spelling `🛡ADV_PRIVACY🛡`.
        "\uD83D\uDEE1\uFE0FADV_PRIVACY\uD83D\uDEE1\uFE0F" to Kind.RENDERED,
        "MEDIA|||" to Kind.RENDERED,

        "💬REPLY💬" to Kind.ENVELOPE,                  // 💬REPLY💬
        "➡️FORWARDED➡️" to Kind.ENVELOPE               // ➡️FORWARDED➡️
    )

    @Test
    fun catalogMatchesIOSExactly() {
        assertEquals(
            "catalog size diverged from OSHIControlPayload.swift:82-109",
            iosCatalog.size,
            OSHIControlPayload.catalog.size
        )
        iosCatalog.forEachIndexed { index, (prefix, kind) ->
            val entry = OSHIControlPayload.catalog[index]
            assertEquals("prefix #$index", prefix, entry.prefix)
            assertEquals("kind of $prefix", kind, entry.kind)
        }
    }

    @Test
    fun everyMarkerIsMatchedWithItsBodyAttached() {
        for ((prefix, _) in iosCatalog) {
            val wire = prefix + "{\"content\":\"x\"}"
            assertEquals("matchedPrefix($prefix)", prefix, OSHIControlPayload.matchedPrefix(wire))
            assertTrue("isControl($prefix)", OSHIControlPayload.isControl(wire))
            assertEquals(
                "stripPrefix($prefix)",
                "{\"content\":\"x\"}",
                OSHIControlPayload.stripPrefix(wire)
            )
        }
    }

    @Test
    fun kindsRouteToTheRightQuestion() {
        for ((prefix, kind) in iosCatalog) {
            val wire = prefix + "{}"
            assertEquals("isSilent($prefix)", kind == Kind.SILENT, OSHIControlPayload.isSilent(wire))
            assertEquals(
                "isEnvelope($prefix)",
                kind == Kind.ENVELOPE,
                OSHIControlPayload.isEnvelope(wire)
            )
        }
    }

    /**
     * U+FE0F-stripped twins. Swift:151-163 derives them; Kotlin's `startsWith`
     * has no canonical equivalence at all, so without them a peer emitting the
     * bare codepoint renders raw.
     */
    @Test
    fun variationSelectorStrippedSpellingsResolveToTheCanonicalPrefix() {
        val pairs = listOf(
            "⌨TYPING⌨" to OSHIControlPayload.TYPING_IOS,
            "✍TYPING✍" to OSHIControlPayload.TYPING_ANDROID,
            "🛡CHECK_IN🛡" to OSHIControlPayload.CHECK_IN,
            "🛡CHECKIN🛡" to OSHIControlPayload.CHECKIN,
            "➡FORWARDED➡" to OSHIControlPayload.FORWARDED
        )
        for ((bare, canonical) in pairs) {
            assertEquals(canonical, OSHIControlPayload.matchedPrefix(bare + "{}"))
            assertEquals("{}", OSHIControlPayload.stripPrefix(bare + "{}"))
        }
    }

    @Test
    fun prefixesAreOrderedLongestFirstSoNoneShadowsAnother() {
        val lengths = OSHIControlPayload.prefixes.map { it.length }
        assertEquals(lengths.sortedDescending(), lengths)
        assertEquals(iosCatalog.size, OSHIControlPayload.prefixes.size)
    }

    // ---------------------------------------------------------------- matching

    @Test
    fun aSentinelInTheMIDDLEOfProseIsNotAControlPayload() {
        val prose = "we should add a 📍LOCATION📍 marker"
        assertNull(OSHIControlPayload.matchedPrefix(prose))
        assertFalse(OSHIControlPayload.isControl(prose))
        assertNull(OSHIControlPayload.displaySummary(prose))
        assertEquals(prose, OSHIControlPayload.previewText(prose))
    }

    @Test
    fun plainProseComesBackUnchanged() {
        assertNull(OSHIControlPayload.displaySummary("hello there"))
        assertEquals("hello there", OSHIControlPayload.previewText("hello there"))
    }

    @Test
    fun bareGroupSyncJsonIsSilent() {
        for (type in listOf("member_sync", "sync_request", "group_update")) {
            val compact = "{\"type\":\"$type\",\"groupId\":\"g\"}"
            val spaced = "{\"type\": \"$type\",\"groupId\":\"g\"}"
            assertTrue(compact, OSHIControlPayload.isSilent(compact))
            assertTrue(spaced, OSHIControlPayload.isSilent(spaced))
            assertEquals("", OSHIControlPayload.previewText(compact))
        }
    }

    // ---------------------------------------------------------------- summaries

    @Test
    fun everyRenderedMarkerProducesAHumanLineNeverRawJson() {
        val rendered = iosCatalog.filter { it.second == Kind.RENDERED }
        for ((prefix, _) in rendered) {
            val summary = OSHIControlPayload.displaySummary(prefix + "{\"a\":1}")
            assertTrue("$prefix produced null", summary != null)
            assertFalse("$prefix leaked JSON: $summary", summary!!.contains("{"))
            assertTrue("$prefix produced an empty line", summary.isNotEmpty())
        }
    }

    @Test
    fun everySilentMarkerProducesTheEmptyString() {
        val silent = iosCatalog.filter { it.second == Kind.SILENT }
        for ((prefix, _) in silent) {
            assertEquals(prefix, "", OSHIControlPayload.displaySummary(prefix + "{\"a\":1}"))
        }
    }

    // ------------------------------------------------------------- call summary

    @Test
    fun callSummaryKeyBarDurationShape() {
        // "📱CALL_SUMMARY📱📞↗️call.outgoing|1:23" — the shape iOS emits.
        val wire = OSHIControlPayload.CALL_SUMMARY + "📞↗️call.outgoing|1:23"
        assertEquals(
            "📞↗️ Outgoing call • 1:23",
            OSHIControlPayload.previewText(wire)
        )
    }

    @Test
    fun callSummaryWithoutDuration() {
        val wire = OSHIControlPayload.MISSED_CALL + "❌call.missed"
        assertEquals("❌ Missed call", OSHIControlPayload.previewText(wire))
    }

    @Test
    fun callSummaryJsonShapeFromOtherClients() {
        val wire = OSHIControlPayload.CALL_SUMMARY + "{\"direction\":\"outgoing\",\"duration\":83}"
        assertEquals(
            "📞↗️ Outgoing call • 1:23",
            OSHIControlPayload.previewText(wire)
        )

        val incoming = OSHIControlPayload.CALL_SUMMARY + "{\"direction\":\"incoming\",\"duration\":0}"
        assertEquals("📞↙️ Incoming call", OSHIControlPayload.previewText(incoming))

        val missed = OSHIControlPayload.CALL_SUMMARY + "{\"missed\":true,\"duration\":0}"
        assertEquals("❌ Missed call", OSHIControlPayload.previewText(missed))
    }

    @Test
    fun callSummaryNeverFeedsGarbageToTheLocalizer() {
        // Already-localized text from an old build must not be echoed as a key.
        val wire = OSHIControlPayload.CALL_SUMMARY + "Appel sortant"
        assertEquals("📞 Call", OSHIControlPayload.previewText(wire))
    }

    @Test
    fun durationFormatting() {
        assertEquals("0:00", OSHIControlPayload.formatDuration(0.0))
        assertEquals("1:23", OSHIControlPayload.formatDuration(83.0))
        assertEquals("1:00:00", OSHIControlPayload.formatDuration(3600.0))
        assertEquals("0:00", OSHIControlPayload.formatDuration(-5.0))
        assertEquals("0:00", OSHIControlPayload.formatDuration(Double.NaN))
    }

    // ----------------------------------------------------------------- location

    @Test
    fun locationSummaryPrefersLiveThenAddress() {
        assertEquals(
            "📍 Sharing live location",
            OSHIControlPayload.previewText(
                OSHIControlPayload.LOCATION + "{\"isLive\":true,\"address\":\"12 rue X, Paris\"}"
            )
        )
        assertEquals(
            "📍 12 rue X",
            OSHIControlPayload.previewText(
                OSHIControlPayload.LOCATION + "{\"isLive\":false,\"address\":\"12 rue X, Paris\"}"
            )
        )
        assertEquals(
            "📍 Location",
            OSHIControlPayload.previewText(OSHIControlPayload.LOCATION + "{\"isLive\":false}")
        )
    }

    // -------------------------------------------------------------------- media

    @Test
    fun mediaMarkerSummary() {
        assertEquals("📷 Photo", OSHIControlPayload.previewText("MEDIA|||image|||xxx"))
        assertEquals("🎥 Video", OSHIControlPayload.previewText("MEDIA|||video|||xxx"))
        assertEquals("🎤 Voice message", OSHIControlPayload.previewText("MEDIA|||audio|||xxx"))
        assertEquals("📄 Document", OSHIControlPayload.previewText("MEDIA|||document|||xxx"))
        assertEquals("📎 File", OSHIControlPayload.previewText("MEDIA|||other|||xxx"))
    }

    @Test
    fun bareMediaJsonIsSummarisedNotPrinted() {
        val wire = "{\"type\":\"media\",\"mediaType\":\"image\",\"caption\":\"beach\"}"
        assertEquals("📷 Photo beach", OSHIControlPayload.previewText(wire))

        val noCaption = "{\"type\":\"media\",\"mediaType\":\"video\"}"
        assertEquals("🎥 Video", OSHIControlPayload.previewText(noCaption))
    }

    // ---------------------------------------------------------------- envelopes

    /**
     * The bug this catalog was ported to kill: `formatPreviewText` had no reply
     * branch and the emoji precedes the `{`, so every JSON catch-all missed too.
     */
    @Test
    fun replyEnvelopeUnwrapsToTheUserText() {
        val wire = OSHIControlPayload.REPLY +
            "{\"content\":\"hi\",\"replyTo\":{\"originalMessageId\":\"m1\",\"originalText\":\"yo\"}}"
        assertEquals("hi", OSHIControlPayload.previewText(wire))
        assertTrue(OSHIControlPayload.isEnvelope(wire))
    }

    @Test
    fun forwardEnvelopeUnwrapsToTheUserText() {
        val wire = OSHIControlPayload.FORWARDED +
            "{\"originalSenderName\":\"Ada\",\"content\":\"see this\",\"isForwarded\":true,\"forwardCount\":1}"
        assertEquals("see this", OSHIControlPayload.previewText(wire))
    }

    @Test
    fun envelopeAroundAControlPayloadRecursesToItsSummary() {
        // Swift:223-229 — a reply to a call summary quotes the summary, not JSON
        // of JSON.
        val inner = OSHIControlPayload.CALL_SUMMARY + "📞↗️call.outgoing|1:23"
        val wire = OSHIControlPayload.REPLY + org.json.JSONObject()
            .put("content", inner)
            .put("replyTo", org.json.JSONObject().put("originalMessageId", "m1"))
            .toString()
        assertEquals(
            "📞↗️ Outgoing call • 1:23",
            OSHIControlPayload.previewText(wire)
        )
    }

    @Test
    fun unreadableEnvelopeShowsNothingRatherThanBrokenJson() {
        val wire = OSHIControlPayload.REPLY + "{not json"
        assertEquals("", OSHIControlPayload.previewText(wire))
    }

    @Test
    fun displayTextPreservingEnvelopesLeavesEnvelopesAloneButSummarisesTheRest() {
        val reply = OSHIControlPayload.REPLY + "{\"content\":\"hi\"}"
        assertEquals(reply, OSHIControlPayload.displayTextPreservingEnvelopes(reply))

        val call = OSHIControlPayload.CALL_SUMMARY + "❌call.missed"
        assertEquals(
            "❌ Missed call",
            OSHIControlPayload.displayTextPreservingEnvelopes(call)
        )
    }

    // -------------------------------------------------- the four fixed surfaces

    /**
     * The sanitise-then-detect regression, expressed as a test: the bubble type
     * must be decidable from the RAW payload. If a caller sanitises first, the
     * prefix is gone and `matchedPrefix` returns null — which is exactly what
     * made four renderers unreachable.
     */
    @Test
    fun bubbleTypeIsDecidableFromRawContentAndNotFromASummary() {
        val cases = listOf(
            OSHIControlPayload.CALL_SUMMARY to "📞↗️call.outgoing|1:23",
            OSHIControlPayload.MISSED_CALL to "❌call.missed",
            OSHIControlPayload.CHECK_IN to "{\"type\":\"arrived\",\"sessionId\":\"s\"}",
            OSHIControlPayload.CHECKIN to "{\"type\":\"arrived\",\"sessionId\":\"s\"}",
            OSHIControlPayload.SCHEDULED_CALL to "{\"title\":\"Sync\",\"status\":\"confirmed\"}",
            OSHIControlPayload.KEY_ROTATION to "{}"
        )
        for ((prefix, body) in cases) {
            val raw = prefix + body
            assertEquals("raw detection for $prefix", prefix, OSHIControlPayload.matchedPrefix(raw))
            // ...and the same test against the summary is null — the old bug.
            val summarised = OSHIControlPayload.previewText(raw)
            assertNull("summary must not re-match $prefix", OSHIControlPayload.matchedPrefix(summarised))
        }
    }

    /**
     * `isSilent`, NOT `isControl`, gates the bubble list (ChatView.swift:280-286):
     * dropping rendered sentinels would delete the user's call history.
     */
    @Test
    fun bubbleFilterKeepsRenderedSentinelsAndDropsProtocolOnes() {
        assertTrue(OSHIControlPayload.isSilent(OSHIControlPayload.ACTION + "{}"))
        assertTrue(OSHIControlPayload.isSilent(OSHIControlPayload.SESSION_RESET))
        assertTrue(OSHIControlPayload.isSilent(OSHIControlPayload.GROUP_UPDATE + "{}"))

        assertFalse(OSHIControlPayload.isSilent(OSHIControlPayload.CALL_SUMMARY + "x"))
        assertFalse(OSHIControlPayload.isSilent(OSHIControlPayload.CHECK_IN + "{}"))
        assertFalse(OSHIControlPayload.isSilent(OSHIControlPayload.SCHEDULED_CALL + "{}"))
        // iOS RENDERS key rotation as a system row; Android used to filter it out.
        assertFalse(OSHIControlPayload.isSilent(OSHIControlPayload.KEY_ROTATION + "{}"))
        assertFalse(OSHIControlPayload.isSilent(OSHIControlPayload.REPLY + "{\"content\":\"hi\"}"))
    }

    @Test
    fun labelsAreThreadedThroughEverySummary() {
        val fr = OSHIControlPayload.Labels(
            callMissed = "Appel manqué",
            checkIn = "Suivi",
            scheduledCall = "Appel programmé",
            keyRotation = "Rotation des clés",
            mediaImage = "Photo FR"
        )
        assertEquals(
            "❌ Appel manqué",
            OSHIControlPayload.previewText(OSHIControlPayload.MISSED_CALL + "❌call.missed", fr)
        )
        assertEquals(
            "🛡️ Suivi",
            OSHIControlPayload.previewText(OSHIControlPayload.CHECK_IN + "{}", fr)
        )
        assertEquals(
            "📅 Appel programmé",
            OSHIControlPayload.previewText(OSHIControlPayload.SCHEDULED_CALL + "{}", fr)
        )
        assertEquals(
            "🔑 Rotation des clés",
            OSHIControlPayload.previewText(OSHIControlPayload.KEY_ROTATION + "{}", fr)
        )
        assertEquals("📷 Photo FR", OSHIControlPayload.previewText("MEDIA|||image|||x", fr))
    }
}
