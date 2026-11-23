package com.oshi.messenger.network.v2

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** __LEGACY_COMPAT_2026_09_23__ group transition window: flag, sunset, member partition. */
class GroupLegacyCompatTest {

    private val prefix = "📢GROUP_UPDATE📢"
    private val before = 1_790_000_000_000L          // 2026-09-21
    private val sunset = GroupLegacyCompat.SUNSET_MS

    @Before fun reset() = GroupLegacyCompat.resetForTest()
    @After fun tearDown() = GroupLegacyCompat.resetForTest()

    @Test fun sunsetIs20261014At0900Paris() {
        assertEquals(java.time.Instant.parse("2026-10-14T07:00:00Z").toEpochMilli(), sunset)
    }

    @Test fun isActiveOnlyForTrueBeforeSunset() {
        assertFalse(GroupLegacyCompat.isActive(null, before))
        assertFalse(GroupLegacyCompat.isActive(false, before))
        assertTrue(GroupLegacyCompat.isActive(true, before))
        assertTrue(GroupLegacyCompat.isActive(true, sunset - 1))
        assertFalse(GroupLegacyCompat.isActive(true, sunset))
        assertFalse(GroupLegacyCompat.isActive(true, sunset + 86_400_000L))
    }

    @Test fun parseFlagDefaultsToFalse() {
        assertFalse(GroupLegacyCompat.parseFlag(null))
        assertFalse(GroupLegacyCompat.parseFlag(JSONObject("""{"v2_enabled":true,"rollout_percent":100}""")))
        assertFalse(GroupLegacyCompat.parseFlag(JSONObject("""{"group_legacy_compat":false}""")))
        assertFalse(GroupLegacyCompat.parseFlag(JSONObject("""{"group_legacy_compat":"true"}""")))
        assertFalse(GroupLegacyCompat.parseFlag(JSONObject("""{"group_legacy_compat":1}""")))
        assertTrue(GroupLegacyCompat.parseFlag(JSONObject("""{"v2_enabled":true,"group_legacy_compat":true}""")))
    }

    @Test fun partitionUsesCapsBitThenProofElseLegacy() {
        val caps = mapOf("newIOS" to (1 or GroupLegacyCompat.CAP_GROUP_V2), "oldIOS" to 1, "oldAndroid" to 0)
        val proven = setOf("desktop")
        val all = listOf("newIOS", "oldIOS", "oldAndroid", "desktop", "unknown", "bot:abc", "")
        val legacy = GroupLegacyCompat.legacyRecipients(all, active = true, capsOf = { caps[it] }, proven = { it in proven })
        assertEquals(listOf("oldIOS", "oldAndroid", "unknown"), legacy)
    }

    @Test fun nothingLegacyWhenInactive() {
        val legacy = GroupLegacyCompat.legacyRecipients(listOf("a", "b"), active = false, capsOf = { null }, proven = { false })
        assertTrue(legacy.isEmpty())
    }

    @Test fun liveAccessorFollowsServerFlagAndSunset() {
        val members = listOf("AAAA+/==", "BBBB")
        GroupLegacyCompat.setServerFlag(false)
        assertFalse(GroupLegacyCompat.isActiveNow())
        assertTrue(GroupLegacyCompat.legacyRecipientsNow(members).isEmpty())

        GroupLegacyCompat.setServerFlag(true)
        val open = System.currentTimeMillis() < sunset
        assertEquals(open, GroupLegacyCompat.isActiveNow())
        if (open) {
            assertEquals(members, GroupLegacyCompat.legacyRecipientsNow(members))
            // caps bit learned from the bundle, spelling-insensitive (base64url vs base64)
            GroupLegacyCompat.notePeerCaps("AAAA-_", GroupLegacyCompat.CAP_GROUP_V2)
            GroupLegacyCompat.noteV2DefinitionFrom("BBBB")
            assertTrue(GroupLegacyCompat.legacyRecipientsNow(members).isEmpty())
        } else {
            assertTrue(GroupLegacyCompat.legacyRecipientsNow(members).isEmpty())
        }
    }

    @Test fun onlyAVersionedDefinitionIsProof() {
        val def = prefix + """{"id":"G","name":"n","adminPublicKey":"a","type":"admin_only","members":[{"publicKey":"a","isAdmin":true}],"stateVersion":3}"""
        assertTrue(GroupLegacyCompat.isV2DefinitionProof(def, prefix))
        // iOS ≤ 143 answered Android sync requests over v2 with this — no stateVersion ⇒ no proof
        val created = prefix + """{"type":"created","groupId":"G","name":"n","members":["a","b"]}"""
        assertFalse(GroupLegacyCompat.isV2DefinitionProof(created, prefix))
        val oldDef = prefix + """{"id":"G","name":"n","members":[{"publicKey":"a"}]}"""
        assertFalse(GroupLegacyCompat.isV2DefinitionProof(oldDef, prefix))
        assertFalse(GroupLegacyCompat.isV2DefinitionProof("""{"stateVersion":1,"members":[]}""", prefix))
        assertFalse(GroupLegacyCompat.isV2DefinitionProof(prefix + "not json", prefix))
    }

    @Test fun publishedCapsCarryGroupV2() {
        assertTrue(GroupLegacyCompat.hasGroupV2(GroupLegacyCompat.MY_CAPS))
        assertFalse(GroupLegacyCompat.hasGroupV2(0))
        assertFalse(GroupLegacyCompat.hasGroupV2(null))
    }

    // __LEAK_FIX_2026_09_25__ audit B: no readable legacy duplicate for a member v2 reached
    // on a build that reads v2 groups; v4 (no group key) when every remaining target reads it.
    @Test fun stillNeedLegacyDropsOnlyReachedMembersThatReadV2Groups() {
        val v3 = setOf("NEW_UNREACHED", "NEW_REACHED")
        val out = GroupLegacyCompat.stillNeedLegacy(
            candidates = listOf("OLD_REACHED", "NEW_REACHED", "NEW_UNREACHED", "OLD_UNREACHED"),
            v2Reached = listOf("OLD_REACHED", "NEW_REACHED"),
            readsV2Groups = { it in v3 },
        )
        // A pre-2026-09-04 build keeps its copy even when v2 "reached" it (it may not route v2 groups).
        assertEquals(listOf("OLD_REACHED", "NEW_UNREACHED", "OLD_UNREACHED"), out)
        assertEquals(emptyList<String>(), GroupLegacyCompat.stillNeedLegacy(emptyList(), listOf("X"), { true }))
    }

    @Test fun stillNeedLegacyMatchesBase64AndBase64UrlSpellings() {
        val std = "ab+c/def=="
        val url = "ab-c_def"
        assertEquals(emptyList<String>(),
            GroupLegacyCompat.stillNeedLegacy(listOf(std), listOf(url), { true }))
    }

    @Test fun legacyEnvelopeIsV4OnlyWhenEveryTargetReadsIt() {
        assertEquals(GroupLegacyCompat.LegacyEnvelope.V4_WRAPPED, GroupLegacyCompat.legacyEnvelopeFor(listOf("A", "B"), { true }))
        assertEquals(GroupLegacyCompat.LegacyEnvelope.GROUP_ID_KEY, GroupLegacyCompat.legacyEnvelopeFor(listOf("A", "B"), { it == "A" }))
        assertEquals(GroupLegacyCompat.LegacyEnvelope.GROUP_ID_KEY, GroupLegacyCompat.legacyEnvelopeFor(emptyList(), { true }))
    }
}
