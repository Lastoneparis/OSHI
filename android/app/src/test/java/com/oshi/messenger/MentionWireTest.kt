package com.oshi.messenger

import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.components.insertMention
import com.oshi.messenger.ui.components.mentionCandidates
import com.oshi.messenger.ui.components.mentionRanges
import com.oshi.messenger.util.GroupMentionIndex
import com.oshi.messenger.util.MentionWire
import com.oshi.messenger.util.TickerLinks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __MENTIONS_2026_09_23__ `@name` person mentions — the SAME golden vector as OSHI-Desktop's
 * `MentionWireTest` (`docs/fixtures/mentions/mentions_v1.json`), so the two clients emit and
 * read identical bytes.
 */
class MentionWireTest {

    private val fx = JSONObject(locate().readText())
    private val mentions = (0 until fx.getJSONArray("mentions").length()).map {
        val o = fx.getJSONArray("mentions").getJSONObject(it)
        MentionWire.Mention(o.getString("publicKey"), o.getString("name"))
    }
    private val body = fx.getString("body")
    private val kAlice = mentions[0].publicKey
    private val kJl = mentions[1].publicKey

    @Test fun `render is byte-identical to the shared golden vector`() {
        assertEquals(fx.getString("rendered"), MentionWire.render(mentions))
        assertNull(MentionWire.render(emptyList()))
    }

    @Test fun `parse round-trips the golden value`() {
        assertEquals(mentions, MentionWire.parse(JSONArray(fx.getString("rendered"))))
    }

    /**
     * Android's emitter is `JSONObject.put`, so its KEY ORDER is the platform map's — not a
     * byte contract. What must match is the content: the desktop's golden GroupMessage and
     * Android's for the same inputs are the same JSON value, `mentions` included.
     */
    @Test fun `Android's wire JSON carries the same mentions as the desktop golden message`() {
        val android = JSONObject(
            GroupManager.wireGroupMessageJson(
                id = fx.getString("messageId"),
                wireGroupId = fx.getString("groupId"),
                senderPublicKey = fx.getString("senderPublicKey"),
                appleTimestamp = 780000000.0,
                encryptedContentB64 = Base64.getEncoder().encodeToString(body.toByteArray()),
                iosMediaType = null,
                mediaFileName = null,
                body = body,
                includeBodyCompat = true,
                mentionsRendered = MentionWire.render(mentions),
            )
        )
        val golden = JSONObject(fx.getString("groupMessageJson"))
        assertEquals(golden.keySet(), android.keySet())
        for (k in golden.keySet()) {
            if (k == "timestamp") assertEquals(golden.getDouble(k), android.getDouble(k), 0.0)
            else assertEquals("key $k", golden.get(k).toString(), android.get(k).toString())
        }
        assertEquals(mentions, MentionWire.parse(android.opt(MentionWire.FIELD)))
    }

    @Test fun `no mentions means no key at all`() {
        val json = GroupManager.wireGroupMessageJson(
            id = "ID", wireGroupId = "G", senderPublicKey = "S", appleTimestamp = 1.0,
            encryptedContentB64 = "", iosMediaType = null, mediaFileName = null, body = "hi",
            includeBodyCompat = true,
        )
        assertFalse(JSONObject(json).has(MentionWire.FIELD))
    }

    /** DEGRADE: iOS 1.0.43 decodes only its CodingKeys; what it shows is readable prose. */
    @Test fun `an iOS 1_0_43 decoder sees readable prose and nothing else`() {
        val full = JSONObject(fx.getString("groupMessageJson"))
        val keys = fx.getJSONArray("iosCodingKeys_1_0_43").let { a -> (0 until a.length()).map(a::getString).toSet() }
        val ios = JSONObject()
        for (k in full.keySet()) if (k in keys) ios.put(k, full.get(k))
        assertFalse(ios.has("mentions"))
        val shown = String(Base64.getDecoder().decode(ios.getString("encryptedContent")), Charsets.UTF_8)
        assertEquals(body, shown)
        assertFalse(shown.contains(kAlice))
    }

    /** DEGRADE: Android 1.6.25's `handleIncomingGroupMessage` reads `content` with `optString`. */
    @Test fun `an Android 1_6_25 decoder reads the same content with or without the key`() {
        val with = JSONObject(fx.getString("groupMessageJson"))
        val without = JSONObject(fx.getString("groupMessageJson")).apply { remove("mentions") }
        assertEquals(without.optString("content"), with.optString("content"))
        assertEquals(body, with.optString("content"))
    }

    @Test fun `spans match the golden vector and respect word boundaries`() {
        val expected = fx.getJSONArray("spans").let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { Triple(it.getInt(0), it.getInt(1), it.getString(2)) } }
        }
        assertEquals(expected, MentionWire.spans(body, mentions).map { Triple(it.start, it.endExclusive, it.publicKey) })
    }

    @Test fun `receiver admits only visible tokens of current members, once per key`() {
        assertEquals(mentions, MentionWire.admit(body, mentions, { it == kAlice || it == kJl }))
        assertEquals(listOf(mentions[1]), MentionWire.admit(body, mentions, { it == kJl }))
        assertTrue(MentionWire.admit(body, listOf(MentionWire.Mention(kAlice, "Zed")), { true }).isEmpty())
        assertTrue(MentionWire.parse("""[{"publicKey":"K","name":"a@b"},{"publicKey":"","name":"x"},7]""").isEmpty())
    }

    @Test fun `a picked person wins over a ticker, and real tickers still chip`() {
        val text = "@MAX look at @AAPL"
        val tickers = TickerLinks.matches(text)
        assertEquals(listOf("MAX", "AAPL"), tickers.map { it.symbol })
        val exclude = mentionRanges(text, listOf(MentionWire.Mention("KMAX", "MAX")))
        assertEquals(listOf("AAPL"), tickers.filter { m -> exclude.none { r -> m.start <= r.last && r.first < m.end } }.map { it.symbol })
        assertTrue(mentionRanges(text, emptyList()).isEmpty())
    }

    @Test fun `a mention breaks through mute, never through the open thread`() {
        assertFalse(GroupManager.shouldRaiseGroupBanner(isMuted = true, onScreen = false))
        assertTrue(GroupManager.shouldRaiseGroupBanner(isMuted = true, onScreen = false, mentionsMe = true))
        assertFalse(GroupManager.shouldRaiseGroupBanner(isMuted = true, onScreen = true, mentionsMe = true))
        assertTrue(GroupManager.shouldRaiseGroupBanner(isMuted = false, onScreen = false))
    }

    @Test fun `composer query, candidates and insertion match the desktop`() {
        assertEquals(6 to "Al", MentionWire.activeQuery("hello @Al", 9))
        assertNull(MentionWire.activeQuery("mail bob@Al", 11))
        val members = listOf(MentionWire.Mention("K1", "Alice"), MentionWire.Mention("K2", "Malik"), MentionWire.Mention("K3", "Bob"))
        assertEquals(listOf("Alice", "Malik"), mentionCandidates(members, "al").map { it.name })
        assertEquals("hello @Alice ", insertMention("hello @Al", 6, members[0]))
        assertEquals(listOf(members[0]), MentionWire.stillPresent("hello @Alice ", listOf(members[0], members[2])))
    }

    @Test fun `the side index survives a restart, is case-insensitive on ids, and is capped`() {
        val idx = GroupMentionIndex(cap = 2)
        idx.put("aaaa-1", mentions)
        assertEquals(mentions, GroupMentionIndex.deserialize(idx.serialize()).get("AAAA-1"))
        idx.put("b", listOf(mentions[0])); idx.put("c", listOf(mentions[1]))
        assertEquals(2, idx.size())
        assertTrue(idx.get("aaaa-1").isEmpty())
        assertEquals(0, GroupMentionIndex.deserialize("{corrupt").size())
        assertEquals(0, GroupMentionIndex.deserialize(null).size())
    }

    private fun locate(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "docs/fixtures/mentions/mentions_v1.json")
            if (f.isFile) return f
            d = d.parentFile
        }
        error("docs/fixtures/mentions/mentions_v1.json not found above ${System.getProperty("user.dir")}")
    }
}
