package com.oshi.messenger.ui

import com.oshi.messenger.ui.screens.GroupMemberListPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MEMBER_LIST_WOULD_NOT_UNROLL_2026_11_04__ and
 * __GROUP_INFO_WAS_A_LONG_PRESS_ONLY_2026_11_04__ — two reports from Anatole,
 * one sentence apart, and the same fault underneath both: a control that is
 * not one.
 *
 *  * The group info sheet drew five members and then the line
 *    "+4 autres membres". That line was a bare `Text`. It sits where a control
 *    sits, it names a quantity you would want to see, and nothing was behind
 *    it — so the rest of the roster was unreachable from the sheet whose whole
 *    subject is the membership.
 *  * Reaching that sheet at all meant LONG-PRESSING the group in the list.
 *    Nothing on screen said so, so renaming a group, changing its photo and
 *    managing its members were all behind a gesture a user has to be told
 *    about. His words: "we have to press and hold the group to edit, instead
 *    of tapping it and having the button".
 *
 * The first two tests pin the arithmetic — the one thing about the repair that
 * can go silently wrong is the count on the expander disagreeing with the rows
 * drawn. The rest read the SOURCE, because what went wrong both times was a
 * missing modifier, and no amount of state testing can see one of those.
 */
class GroupMemberListExpandTest {

    // ── The arithmetic ──

    @Test
    fun `collapsed shows five and the expander names exactly what is left`() {
        val members = (1..9).map { "KEY$it" }
        val collapsed = GroupMemberListPolicy.visible(members, expanded = false)
        assertEquals(5, collapsed.size)
        // 9 members, 5 shown: the line must say 4. Anatole's screenshot says
        // "+4 autres membres" over a nine-member group, which is the number
        // that was right all along — it was the tap that was missing.
        assertEquals(4, GroupMemberListPolicy.hiddenCount(members))
        assertEquals(members.size, collapsed.size + GroupMemberListPolicy.hiddenCount(members))
        assertEquals(members, GroupMemberListPolicy.visible(members, expanded = true))
    }

    @Test
    fun `a group that fits shows no expander, including at exactly the limit`() {
        for (n in 0..5) {
            val members = (1 until 1 + n).map { "KEY$it" }
            assertEquals(
                "a group of $n needs no expander",
                0,
                GroupMemberListPolicy.hiddenCount(members)
            )
            // And everything is already on screen, so expanding changes nothing.
            assertEquals(
                GroupMemberListPolicy.visible(members, expanded = false),
                GroupMemberListPolicy.visible(members, expanded = true)
            )
        }
        // Six is the first group that hides anyone, and it hides exactly one.
        assertEquals(1, GroupMemberListPolicy.hiddenCount((1..6).map { "KEY$it" }))
    }

    // ── The source, for the two missing modifiers ──

    private fun source(path: String): String {
        val f = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
            .firstOrNull { it.isFile }
        assertNotNull("not found on either test working directory: $path", f)
        return f!!.readText()
    }

    /**
     * [src] with comments removed. Needed because every assertion below would
     * otherwise be satisfied by the comment explaining the fix — this codebase
     * deliberately quotes the code it replaced. Same approach as
     * [GroupChatSheetReachabilityTest].
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i += 2
                }
                src.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) { out.append(src[i]); i++ }
                    out.append("\"\"\""); i += 3
                }
                src[i] == '"' -> {
                    out.append(src[i]); i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i]); i++ }
                        out.append(src[i]); i++
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private val groupsScreen: String by lazy {
        stripComments(source("com/oshi/messenger/ui/screens/GroupsScreen.kt"))
    }

    @Test
    fun `the more-members line is inside something that can be tapped`() {
        // The exact failure: `groups_more_members` rendered with no `clickable`
        // anywhere near it. Rather than guess a radius, this asserts the two
        // halves that make it a control — the toggle exists, and the label is
        // read from the same state the toggle writes.
        assertTrue(
            "the member list must have an expansion state",
            groupsScreen.contains("showAllMembers")
        )
        assertTrue(
            "nothing toggles showAllMembers — the expander is decoration again",
            Regex("""clickable\s*\{\s*showAllMembers\s*=\s*!showAllMembers\s*\}""")
                .containsMatchIn(groupsScreen)
        )
        assertTrue(
            "the rows drawn must come from the policy, not from a second take(5)",
            groupsScreen.contains("GroupMemberListPolicy.visible(")
        )
        assertTrue(
            "the count on the line must come from the policy too, or the two drift",
            groupsScreen.contains("GroupMemberListPolicy.hiddenCount(")
        )
    }

    @Test
    fun `both states of the expander have words`() {
        // A chevron that flips with no label change is the same control saying
        // nothing about what it will do — and a screen reader gets neither.
        assertTrue(groupsScreen.contains("R.string.groups_more_members"))
        assertTrue(
            "no label for the collapsed direction",
            groupsScreen.contains("R.string.groups_show_fewer_members")
        )
    }

    @Test
    fun `the group row offers a visible way into the info sheet`() {
        val row = groupsScreen
            .substringAfter("private fun ModernGroupRowItem(")
            .substringBefore("\nprivate fun ")
        assertTrue(
            "the row has no tappable control bound to onInfoClick — info is a hidden gesture again",
            Regex("""IconButton\(\s*onClick\s*=\s*onInfoClick""").containsMatchIn(row)
        )
        // The long-press stays. It is muscle memory for anyone who learned it,
        // and swapping one for the other trades this complaint for its mirror.
        assertTrue(
            "the long-press was removed rather than supplemented",
            row.contains("onLongClick = onInfoClick")
        )
    }

    @Test
    fun `the info control cannot grow the row that F-12 shrank`() {
        val row = groupsScreen
            .substringAfter("private fun ModernGroupRowItem(")
            .substringBefore("\nprivate fun ")
        val button = row.substringAfter("IconButton(\n            onClick = onInfoClick")
        // F-12 removed the old info button to bring the row height down, and the
        // avatar next to it is 54 dp. A capped control cannot be what drives the
        // height; an uncapped `IconButton` defaults to 48 dp and one day will not
        // be the tallest thing here.
        assertTrue(
            "the info button must carry an explicit size cap",
            Regex("""modifier\s*=\s*Modifier\.size\(\s*3\d\.dp\s*\)""").containsMatchIn(button)
        )
    }
}
