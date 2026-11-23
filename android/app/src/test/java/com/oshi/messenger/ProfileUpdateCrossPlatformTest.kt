package com.oshi.messenger

import com.oshi.messenger.data.model.PeerNickname
import com.oshi.messenger.data.model.ProfileUpdateWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * __SHARED_NICKNAME_2026_09_22__ The `📸PROFILE_UPDATE📸` payload as iOS, Android and
 * Desktop actually write it, read here with the Android reader and with a replay of the
 * iOS synthesized-`Codable` rules (REQUIRED `type`/`lastSeen`/`version`). Same fixtures as
 * `OSHI-Desktop/src/test/kotlin/com/oshi/desktop/msg/ProfileUpdateCrossPlatformTest.kt`.
 */
class ProfileUpdateCrossPlatformTest {

    companion object {
        const val P = "📸PROFILE_UPDATE📸"
        const val IOS = P + """{"ratchetV3":true,"lastSeen":1790000000.25,"appPlatform":"ios","displayName":"Zoé 🌸","groupEnvelopeV3":true,"type":"profile_update","version":1,"appVersion":"1.0.47","profileImageData":"iVBORw0KGgo\/8AAA=="}"""
        const val IOS_HOSTILE = P + """{"type":"profile_update","lastSeen":1790000000,"version":1,"displayName":"‮Hugo\nX‎"}"""
        const val IOS_OLD = P + """{"type":"profile_update","lastSeen":1790000000,"version":1,"groupEnvelopeV3":true,"ratchetV3":true}"""
        /** Exactly what `OSHI-Desktop` `ProfileUpdateWire.encode("Hugo desk")` emits (JSON-equal). */
        const val DESKTOP = P + """{"type":"profile_update","lastSeen":0,"version":1,"displayName":"Hugo desk"}"""
        const val DESKTOP_CLEAR = P + """{"type":"profile_update","lastSeen":0,"version":1,"displayName":""}"""
    }

    /** What iOS `processIncomingProfileUpdate` stores, or throws where `JSONDecoder` throws. */
    private object IosRules {
        class DecodingError(msg: String) : Exception(msg)

        fun decode(text: String): PeerNickname.Read {
            val o = JSONObject(text.removePrefix(P))
            req(o, "type") { it is String }
            req(o, "lastSeen") { it is Number }
            req(o, "version") { it is Number && it.toDouble() == Math.floor(it.toDouble()) }
            for (k in listOf("profileImageData", "pqPublicKey", "appVersion", "appPlatform", "displayName")) opt(o, k) { it is String }
            for (k in listOf("groupEnvelopeV3", "ratchetV3")) opt(o, k) { it is Boolean }
            if (!o.has("displayName")) return PeerNickname.Read.Absent
            val raw = if (o.isNull("displayName")) null else o.getString("displayName")
            return sanitize(raw)?.let { PeerNickname.Read.Set(it) } ?: PeerNickname.Read.Clear
        }

        private fun req(o: JSONObject, k: String, ok: (Any) -> Boolean) {
            if (!o.has(k) || o.isNull(k)) throw DecodingError("keyNotFound $k")
            if (!ok(o.get(k))) throw DecodingError("typeMismatch $k")
        }
        private fun opt(o: JSONObject, k: String, ok: (Any) -> Boolean) {
            if (o.has(k) && !o.isNull(k) && !ok(o.get(k))) throw DecodingError("typeMismatch $k")
        }

        /** `SharedNickname.sanitize` (OSHI/ContactPresenceManager.swift). */
        fun sanitize(raw: String?): String? {
            if (raw == null) return null
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                val drop = Character.getType(cp) == Character.CONTROL.toInt() ||
                    cp == 0x2028 || cp == 0x2029 || cp == 0x200E || cp == 0x200F ||
                    cp in 0x202A..0x202E || cp in 0x2066..0x2069
                if (!drop) sb.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            var s = sb.toString().trim()
            if (s.codePointCount(0, s.length) > 48) s = s.substring(0, s.offsetByCodePoints(0, 48)).trim()
            return s.ifEmpty { null }
        }
    }

    /** Android's own reader: what `handleProfileUpdate` hands to `PeerNicknameStore.record`. */
    private fun androidRead(text: String) = PeerNickname.read(JSONObject(text.removePrefix(P)))

    /**
     * The payload `MessageRepository.sendProfileUpdate` builds, key for key, minus the two
     * Context-bound stamps (post-quantum key, version) which are optional strings.
     */
    private fun androidPayload(nickname: String?, lastSeenShared: Boolean): String {
        val o = JSONObject().apply {
            put("type", "profile_update")
            put("profileImageData", JSONObject.NULL)
            put("lastSeen", ProfileUpdateWire.lastSeenValue(lastSeenShared, 1_790_000_000_000L))
            put("version", 1)
            put("groupEnvelopeV3", true)
            put("ratchetV3", true)
            PeerNickname.stamp(this, nickname)
        }
        return P + o.toString()
    }

    @Test
    fun `iOS payloads parse on Android`() {
        assertEquals(PeerNickname.Read.Set("Zoé 🌸"), androidRead(IOS))
        assertEquals(PeerNickname.Read.Set("HugoX"), androidRead(IOS_HOSTILE))
        assertEquals(PeerNickname.Read.Absent, androidRead(IOS_OLD))
    }

    @Test
    fun `Desktop payloads parse on Android, including an explicit clear`() {
        assertEquals(PeerNickname.Read.Set("Hugo desk"), androidRead(DESKTOP))
        assertEquals(PeerNickname.Read.Clear, androidRead(DESKTOP_CLEAR))
        // Desktop sends no avatar: `optString("profileImageData", null)` must stay null so
        // the stored picture is never overwritten.
        assertNull(JSONObject(DESKTOP.removePrefix(P)).optString("profileImageData", null))
    }

    @Test
    fun `Android output decodes under the iOS rules with Last Seen ON and OFF`() {
        for (shared in listOf(true, false)) {
            val out = androidPayload("Hugo", shared)
            assertEquals("lastSeenShared=$shared", PeerNickname.Read.Set("Hugo"), IosRules.decode(out))
        }
        val off = JSONObject(androidPayload("Hugo", false).removePrefix(P))
        assertEquals("not shared is 0, which the Android reader treats as absent", 0.0, off.getDouble("lastSeen"), 0.0)
        assertEquals(PeerNickname.Read.Absent, IosRules.decode(androidPayload(null, true)))
    }

    @Test
    fun `the pre-fix Android payload (no lastSeen) is what iOS rejected`() {
        val old = JSONObject(androidPayload("Hugo", true).removePrefix(P)).apply { remove("lastSeen") }
        try {
            IosRules.decode(P + old.toString())
            fail("iOS requires lastSeen")
        } catch (e: IosRules.DecodingError) {
            assertTrue(e.message!!.contains("lastSeen"))
        }
    }

    @Test
    fun `every fixture reads the same under the iOS rules and the Android reader`() {
        for (f in listOf(IOS, IOS_HOSTILE, IOS_OLD, DESKTOP, DESKTOP_CLEAR, androidPayload("Zoé 🌸", false))) {
            assertEquals(f, androidRead(f), IosRules.decode(f))
        }
    }

    @Test
    fun `what Android stamps is a fixed point of the iOS sanitizer`() {
        val sent = (IosRules.decode(androidPayload("  ‮" + "🌸".repeat(60) + "\n", true)) as PeerNickname.Read.Set).name
        assertEquals(sent, IosRules.sanitize(sent))
        assertEquals(48, sent.codePointCount(0, sent.length))
    }

    @Test
    fun `group sender names follow renames, key spellings and non-contacts`() {
        val key = "ab+/cdEFgh12ijKLmn34opQRst56uvWXyz78ABCDEFG="
        val url = "ab-_cdEFgh12ijKLmn34opQRst56uvWXyz78ABCDEFG"
        val stranger = "zz+/yyXXww11vvUUtt22ssRRqq33ppOOnn44mmLLkk0="
        val nicks = mapOf(
            PeerNickname.normalizeKey(key) to "Zoé",
            PeerNickname.normalizeKey(stranger) to "Member",
        )
        // Placeholder alias loses to the nickname; the base64url wire spelling still finds it.
        val map = PeerNickname.senderDisplayMap(listOf(key to key.take(8) + "..."), nicks)
        assertEquals("Zoé", PeerNickname.lookupSender(map, key))
        assertEquals("Zoé", PeerNickname.lookupSender(map, url))
        // A group member who is not a contact is named by their shared nickname.
        assertEquals("Member", PeerNickname.lookupSender(map, stranger))
        // A typed alias still wins.
        val aliased = PeerNickname.senderDisplayMap(listOf(key to "Mum"), nicks)
        assertEquals("Mum", PeerNickname.lookupSender(aliased, url))
        assertNull(PeerNickname.lookupSender(map, "unknown"))
        assertFalse(map.values.any { it.isBlank() })
    }
}
