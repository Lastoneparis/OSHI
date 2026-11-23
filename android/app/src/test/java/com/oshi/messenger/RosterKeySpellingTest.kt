package com.oshi.messenger

import com.oshi.messenger.service.GroupUpdateAuthorizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __ROSTER_COMPARED_RAW_KEYS_2026_11_07__ — candidate cause of Anatole's N9,
 * "iOS users disappear from the member list from time to time and reappear".
 *
 * `addMember` and `removeMember` compared roster entries with a RAW `!=`, while
 * every authorisation check in the same file compares through
 * `canonicalIdentity`. `normalizeKey` maps `-`→`+`, `_`→`/` and drops `=`, so
 * one identity spelled base64url and base64 is ONE person to the authorizer and
 * TWO different strings to the roster.
 *
 * A cross-SPELLING bug is in practice a cross-PLATFORM one — iOS sends standard
 * base64, Android canonicalises — which is why the symptom named iOS users.
 *
 * NOT claimed as proven: N9 was reported before 1.6.80 shipped the 04/11
 * eviction-tombstone fix, so the symptom may already be gone. This is a real
 * inconsistency on its own merits and is fixed on those.
 */
class RosterKeySpellingTest {

    private val base64 = "abc+def/ghi="
    private val base64url = "abc-def_ghi"

    @Test
    fun `the two spellings are one identity`() {
        assertEquals(
            GroupUpdateAuthorizer.canonicalIdentity(base64),
            GroupUpdateAuthorizer.canonicalIdentity(base64url),
        )
    }

    /** The raw comparison the roster used to make — kept to show what it costs. */
    @Test
    fun `a raw comparison does not see them as the same person`() {
        assertFalse(
            "if this ever becomes true the bug cannot happen and this file can go",
            base64 == base64url,
        )
    }

    /** Removal must match whichever spelling the roster happens to hold. */
    @Test
    fun `removal finds the member under either spelling`() {
        val roster = listOf("zzz", base64)
        val target = GroupUpdateAuthorizer.canonicalIdentity(base64url)
        val after = roster.filter { GroupUpdateAuthorizer.canonicalIdentity(it) != target }
        assertEquals(listOf("zzz"), after)
    }

    /** Adding must not create a second copy of someone already present. */
    @Test
    fun `adding the other spelling does not duplicate a member`() {
        val roster = listOf("zzz", base64)
        val adding = GroupUpdateAuthorizer.canonicalIdentity(base64url)
        val already = roster.any { GroupUpdateAuthorizer.canonicalIdentity(it) == adding }
        assertTrue("the member is already there under the other spelling", already)
        val after = if (already) roster else roster + base64url
        assertEquals("the roster grew — that is the count moving on its own", 2, after.size)
    }

    /** The wiring: neither operation may go back to a raw comparison. */
    @Test
    fun `the roster operations compare canonically`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/service/GroupManager.kt")
        val remove = SourceText.window(src, "val removing = GroupUpdateAuthorizer.canonicalIdentity", 420)
        assertTrue("removal no longer canonicalises", remove.contains("canonicalIdentity(it) != removing"))
        val add = SourceText.window(src, "val adding = GroupUpdateAuthorizer.canonicalIdentity", 420)
        assertTrue("add no longer checks for an existing copy", add.contains("alreadyThere"))
    }

    // ---------------- the iOS half, found by auditing N9 across platforms

    private fun swift(name: String): String {
        val f = listOf(java.io.File("../OSHI/$name"), java.io.File("../../OSHI/$name"))
            .firstOrNull { it.exists() }
        assertTrue("OSHI/$name not found", f != null)
        return SourceText.stripComments(f!!.readText())
    }

    /**
     * iOS had the same defect, and sharper: `removeMember` compared RAW keys
     * while the eviction tombstone FOUR LINES BELOW compared canonically. A
     * member whose key arrived in the other spelling was recorded as evicted
     * and left sitting in the roster.
     */
    @Test
    fun `the swift roster removal compares canonically`() {
        val src = swift("GroupMessaging.swift")
        val at = src.indexOf("func removeMember(from groupId: UUID")
        assertTrue("removeMember is gone from GroupMessaging.swift", at >= 0)
        val body = src.substring(at, minOf(at + 900, src.length))
        assertFalse(
            "removeMember is back to a raw == — it will tombstone a member and leave them " +
                "in the roster when the spelling differs",
            body.contains("members.removeAll { \$0.publicKey == publicKey }"),
        )
        assertTrue(
            "removeMember no longer canonicalises",
            body.contains("canonicalIdentity(\$0.publicKey) == removingCanon"),
        )
    }

    /** And the duplicate guard, which could admit a second copy of one person. */
    @Test
    fun `the swift roster add checks for an existing copy canonically`() {
        val src = swift("GroupMessaging.swift")
        val at = src.indexOf("func addMember(to groupId: UUID")
        assertTrue("addMember is gone", at >= 0)
        val body = src.substring(at, minOf(at + 700, src.length))
        assertTrue("addMember no longer canonicalises", body.contains("addingCanon"))
    }

    /**
     * N2's symptom by a different route: iOS HAS each member's alias on the
     * group, but looked it up with a raw ==, so a spelling mismatch fell
     * through to the formatted address — "it shows their key".
     */
    @Test
    fun `the swift sender name lookup compares canonically`() {
        val src = swift("GroupViews.swift")
        val at = src.indexOf("private func senderDisplayName(")
        assertTrue("senderDisplayName is gone", at >= 0)
        val body = src.substring(at, minOf(at + 600, src.length))
        assertFalse(
            "the alias lookup is raw again — a spelling mismatch renders the address",
            body.contains("members.first(where: { \$0.publicKey == key })"),
        )
        assertTrue("the alias lookup no longer canonicalises", body.contains("wanted"))
    }
}
