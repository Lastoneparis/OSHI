package com.oshi.messenger.ui

import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.ui.viewmodel.CreateGroupRequest
import com.oshi.messenger.ui.viewmodel.CreateGroupState
import com.oshi.messenger.ui.viewmodel.RecipientKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_CREATE_SCROLL_2026_10_12__ The create-group sheet, after a Play review
 * (5★, 1.6.35, HUAWEI nova 5T) reported « le bug qui bloque la création de groupes
 * privés ».
 *
 * The sheet body was a plain `Column` inside a `ModalBottomSheet`, and a
 * ModalBottomSheet does not scroll its slot. Its content is fixed-height and comes
 * to roughly 814 dp — header 48, name label + field 81, three `GroupTypeOption`s
 * ~226, sixteen avatars in three 44 dp rows 148, plus the member section's own
 * label, contacts button and field — while a 360 × 780 dp phone leaves the sheet
 * about 740 dp. The member field and the added-members list under it were therefore
 * unreachable, and no gesture could bring them up: dragging the sheet dismisses it.
 *
 * That blocks **private** groups specifically, which is the asymmetry the review
 * describes. `GroupsViewModel.canCreateGroup` exempts PUBLIC from needing a member
 * and the sheet hides the member section for it, so a public group created fine
 * while admin-only and collaborative could never acquire their first member and
 * "Create" stayed disabled for ever.
 *
 * The same shape was found and fixed in `GroupInfoSheet` — in this same file, on
 * 2026-08-13 — and this sheet was missed. These assertions read the source because
 * what went wrong is a missing modifier, which no amount of state testing can see.
 */
class GroupCreateSheetReachabilityTest {

    private fun source(path: String): String {
        val f = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
            .firstOrNull { it.isFile }
        assertNotNull("not found on either test working directory: $path", f)
        return f!!.readText()
    }

    /**
     * [src] with comments removed, string and character literals left intact.
     *
     * Needed because every assertion below that says "this shape is GONE" would
     * otherwise be satisfied by the comment explaining that it is gone — this
     * codebase deliberately quotes the code it replaced, and four of these tests
     * passed against their own prose on the first run. Handles `//`, block
     * comments, `"…"` with escapes, and `"""…"""`, which is enough for Kotlin
     * UI sources and keeps strings like `"https://oshi-messenger.org/add?key="`
     * from being truncated at the `//`.
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val rest = src.length - i
            when {
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) src.length else end + 3
                    out.append(src, i, stop); i = stop
                }
                src[i] == '"' || src[i] == '\'' -> {
                    val quote = src[i]
                    out.append(src[i]); i++
                    while (i < src.length && src[i] != quote) {
                        if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i]); i++ }
                        out.append(src[i]); i++
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                rest >= 2 && src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i)
                    i = if (nl < 0) src.length else nl
                }
                rest >= 2 && src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private val groupsScreen by lazy {
        stripComments(source("com/oshi/messenger/ui/screens/GroupsScreen.kt"))
    }
    private val groupsViewModel by lazy {
        stripComments(source("com/oshi/messenger/ui/viewmodel/GroupsViewModel.kt"))
    }

    /** The body of a composable, from its signature to the next top-level `@Composable`. */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature disappeared from GroupsScreen", start > 0)
        val next = src.indexOf("\n@Composable", start + signature.length)
        return src.substring(start, if (next > 0) next else src.length)
    }

    // ===================================================== the reachability fix

    @Test
    fun `the create-group sheet body scrolls, so the member field can be reached`() {
        val body = bodyOf(groupsScreen, "private fun CreateGroupSheet(")
        assertTrue(
            "CreateGroupSheet's body must scroll — without it the member field is " +
                "below the fold and a private group can never get its first member",
            body.contains("verticalScroll(rememberScrollState())"),
        )
    }

    @Test
    fun `both sheets in this file scroll, not just the one fixed in August`() {
        // GroupInfoSheet got this on 2026-08-13; CreateGroupSheet was missed. If a
        // third sheet is added to this file, it is cheaper to read this failure than
        // to find it on a device.
        val sheets = Regex("""ModalBottomSheet\(""").findAll(groupsScreen).count()
        val scrolls = Regex("""verticalScroll\(rememberScrollState\(\)\)""")
            .findAll(groupsScreen).count()
        assertEquals("every ModalBottomSheet body in GroupsScreen must scroll", sheets, scrolls)
    }

    @Test
    fun `the nested contact picker stays height-bounded inside the scrolling parent`() {
        // A LazyColumn measured with an infinite maximum height throws. The parent
        // now scrolls, so this bound is what keeps the picker legal.
        val body = bodyOf(groupsScreen, "private fun CreateGroupSheet(")
        val picker = body.substring(body.indexOf("LazyColumn("))
        assertTrue(
            "the contact-picker LazyColumn needs an explicit max height",
            picker.take(80).contains("heightIn(max = 200.dp)"),
        )
    }

    @Test
    fun `the avatar grid is not a lazy container, which would also measure infinite`() {
        val body = bodyOf(groupsScreen, "private fun CreateGroupSheet(")
        assertFalse(
            "a LazyVerticalGrid here would crash now that the parent scrolls",
            body.contains("LazyVerticalGrid"),
        )
    }

    // ================================================ what made the block private

    @Test
    fun `only a public group is exempt from needing a member`() {
        // The asymmetry the reviewer saw. Stated here so that "private groups
        // cannot be created" can never again be a UI-only accident.
        val named = CreateGroupState(groupName = "Trip")
        for (type in GroupType.entries) {
            val request = CreateGroupRequest.of(named.copy(selectedGroupType = type))
            assertEquals(
                "isPublic must follow the selected type",
                type == GroupType.PUBLIC,
                request.isPublic,
            )
        }
    }

    // ============================================ the member key the field accepts

    @Test
    fun `addMember validates through RecipientKey instead of accepting any text`() {
        assertTrue(
            "addMember must go through the shared validator",
            groupsViewModel.contains("RecipientKey.validate(state.newMemberKey, myPublicKey)"),
        )
        assertFalse(
            "a bare blank check used to be the ONLY gate — a typed name became a member",
            groupsViewModel.contains("if (trimmedKey.isBlank()) return"),
        )
    }

    @Test
    fun `the two member-key errors are localized, not English literals`() {
        // Same defect GroupTypeLabels records for the type names: a 34-language app
        // cannot carry user-visible copy as Kotlin string literals.
        assertFalse(groupsViewModel.contains("\"Member already added\""))
        assertFalse(groupsViewModel.contains("\"Cannot add yourself\""))
        assertTrue(groupsViewModel.contains("R.string.error_already_added"))
        assertTrue(groupsViewModel.contains("R.string.deeplink_own_key_error"))
    }

    @Test
    fun `the scanned member key is canonicalised and no longer length-gated`() {
        val body = bodyOf(groupsScreen, "private fun CreateGroupSheet(")
        assertTrue(
            body.contains("RecipientKey.canonicalRecipientOrNull(key, myPublicKey)"),
        )
        assertFalse(
            "the 20-character rule is what RecipientKey replaced",
            body.contains("if (key.length >= 20)"),
        )
    }

    @Test
    fun `one key in its two spellings is one member, which is what dedupe needs`() {
        // The dedupe in addMember compares canonical forms. That is only an
        // improvement over the old raw-string compare if both spellings of a key
        // really do canonicalise to the same thing — a standard-base64 key and the
        // base64url form the VPS and iOS hand out.
        // 32 real bytes (sha256 of a fixed label) whose standard spelling happens
        // to contain both '+' and '/', so the two alphabets actually differ here.
        val mine = "A".repeat(43) + "="
        val body = "EnEVCWHy/T4YlegUjsm7Fq+l3b0OqJHOHJi5YKT9dK4"
        val urlForm = body.replace('+', '-').replace('/', '_')

        val a = RecipientKey.validate("$body=", mine)
        // base64url's ordinary spelling is unpadded, and RecipientKey accepts it.
        val b = RecipientKey.validate(urlForm, mine)
        assertTrue("standard spelling must validate: $a", a is RecipientKey.Result.Valid)
        assertTrue("base64url spelling must validate: $b", b is RecipientKey.Result.Valid)
        assertEquals(
            "the two spellings must collapse to one stored key",
            (a as RecipientKey.Result.Valid).canonicalKey,
            (b as RecipientKey.Result.Valid).canonicalKey,
        )
    }

    @Test
    fun `a typed name is refused where it used to become a member`() {
        val mine = "A".repeat(43) + "="
        val result = RecipientKey.validate("Anatole", mine)
        assertTrue(result is RecipientKey.Result.Invalid)
        assertEquals(
            RecipientKey.Problem.TOO_SHORT,
            (result as RecipientKey.Result.Invalid).problem,
        )
    }
}
