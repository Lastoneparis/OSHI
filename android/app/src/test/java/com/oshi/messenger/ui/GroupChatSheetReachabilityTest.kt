package com.oshi.messenger.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_CHAT_INFO_SCROLL_2026_10_13__ A new report: « on ne peut pas naviguer
 * dans le menu » — the group's menu, opened from inside the chat.
 *
 * `GroupChatScreen.GroupChatInfoSheet` was a plain `Column` inside a
 * `ModalBottomSheet`, and a ModalBottomSheet does not scroll its slot. Its content
 * is fixed-height and reaches roughly 1230 dp for a ten-member group — avatar 96,
 * title 48, add-member 48, type row 44, gallery card 68, members card ~508, QR card
 * ~324, bottom padding 32 — while a 360 x 780 dp phone leaves the sheet about 740.
 * Everything from the last member rows down was unreachable: the media gallery on a
 * long roster, the QR invite, and "copy invite link". No gesture could bring it up,
 * because dragging the sheet dismisses it instead of panning the content. That is
 * exactly "the menu does not navigate".
 *
 * This is the THIRD time this one shape has shipped in this codebase:
 *
 *  - 2026-08-13  `GroupsScreen.GroupInfoSheet`      found and fixed
 *  - 2026-10-12  `GroupsScreen.CreateGroupSheet`    missed, then fixed after a
 *                Play review (« le bug qui bloque la création de groupes privés »),
 *                see [GroupCreateSheetReachabilityTest]
 *  - 2026-10-13  `GroupChatScreen.GroupChatInfoSheet` — this one, the in-chat copy
 *                of the very sheet fixed in August
 *
 * So these assertions do not stop at the sheet that was reported. The last test
 * states the invariant for every bottom sheet in the UI tree, which is the only
 * version of this that cannot be missed a fourth time.
 *
 * They read the source because what goes wrong is a missing modifier, which no
 * amount of state testing can see.
 */
class GroupChatSheetReachabilityTest {

    private fun sourceFile(path: String): File {
        val f = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
            .firstOrNull { it.isFile }
        assertNotNull("not found on either test working directory: $path", f)
        return f!!
    }

    private fun source(path: String) = sourceFile(path).readText()

    /**
     * [src] with comments removed, string and character literals left intact.
     *
     * Needed because every assertion that says "this shape is GONE" would otherwise
     * be satisfied by the comment explaining that it is gone — this codebase
     * deliberately quotes the code it replaced. Copied from
     * [GroupCreateSheetReachabilityTest]; handles `//`, block comments, `"…"` with
     * escapes, and `"""…"""`.
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

    private val groupChatScreen by lazy {
        stripComments(source("com/oshi/messenger/ui/screens/GroupChatScreen.kt"))
    }
    private val chatScreen by lazy {
        stripComments(source("com/oshi/messenger/ui/screens/ChatScreen.kt"))
    }

    /** The body of a composable, from its signature to the next top-level declaration. */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature disappeared", start > 0)
        val next = listOf("\n@Composable", "\n@OptIn")
            .mapNotNull { m -> src.indexOf(m, start + signature.length).takeIf { it > 0 } }
            .minOrNull()
        return src.substring(start, next ?: src.length)
    }

    private val scrolls = "verticalScroll(rememberScrollState())"

    // ======================================================= the reported sheet

    @Test
    fun `the group's in-chat info menu scrolls, so the QR invite can be reached`() {
        val body = bodyOf(groupChatScreen, "private fun GroupChatInfoSheet(")
        assertTrue(
            "GroupChatInfoSheet must scroll — without it the member rows, the QR " +
                "invite and \"copy invite link\" sit below the fold and no gesture " +
                "reaches them: dragging the sheet dismisses it",
            body.contains(scrolls),
        )
    }

    @Test
    fun `the last row of that menu clears the gesture bar`() {
        // The copy-invite button is the last row; without this it sits under the
        // navigation bar even once the body scrolls.
        val body = bodyOf(groupChatScreen, "private fun GroupChatInfoSheet(")
        assertTrue(
            "GroupChatInfoSheet needs navigationBarsPadding, as GroupInfoSheet has",
            body.contains("navigationBarsPadding()"),
        )
    }

    @Test
    fun `no lazy container was left inside the now-scrolling body`() {
        // A lazy container measured with an infinite maximum height throws. The
        // members list is a `forEach`, and it must stay one.
        val body = bodyOf(groupChatScreen, "private fun GroupChatInfoSheet(")
        for (lazy in listOf("LazyColumn", "LazyVerticalGrid")) {
            assertFalse(
                "$lazy inside GroupChatInfoSheet crashes now that the parent scrolls",
                body.contains(lazy),
            )
        }
        // __GROUP_MEMBERS_STOPPED_AT_TEN_2026_11_04__ This used to pin
        // `memberPublicKeys.take(10).forEach`, which protected the right thing
        // (not lazy) and incidentally froze the wrong thing (the cap at ten).
        // The cap was a workaround for a sheet that could not scroll; the sheet
        // scrolls now, and a group of fifteen was showing "15" above ten rows.
        // The assertion keeps the part that matters.
        assertTrue(
            "the members list must stay a forEach, not become lazy",
            body.contains("group.memberPublicKeys.forEach"),
        )
        assertFalse(
            "the ten-member cap is gone; the header prints the real size and the " +
                "list must match it",
            body.contains("memberPublicKeys.take("),
        )
    }

    // ================================================ the rest of the same file

    @Test
    fun `every bottom sheet in the group chat screen scrolls, not just the reported one`() {
        val sheets = Regex("""ModalBottomSheet\(""").findAll(groupChatScreen).count()
        val scrolled = Regex(Regex.escape(scrolls)).findAll(groupChatScreen).count()
        // __GROUP_CHECK_IN_2026_11_16__ The Check-In sheet's body is `CheckInSheet`,
        // which scrolls ITSELF (CheckInScreen.kt) — wrapped in a second scroll it
        // would be measured at infinite height. Counted as scrolled, and held to it.
        val selfScrolling = Regex("""\bCheckInSheet\(""").findAll(groupChatScreen).count()
        if (selfScrolling > 0) {
            val checkIn = java.io.File(
                java.io.File(".").absoluteFile.let { d ->
                    generateSequence(d) { it.parentFile }.first { java.io.File(it, "src/main/java").isDirectory || java.io.File(it, "app/src/main/java").isDirectory }
                }.let { if (java.io.File(it, "src/main/java").isDirectory) it else java.io.File(it, "app") },
                "src/main/java/com/oshi/messenger/ui/screens/CheckInScreen.kt"
            ).readText()
            assertTrue("CheckInSheet no longer scrolls itself", checkIn.contains(".verticalScroll("))
        }
        assertEquals(
            "every ModalBottomSheet body in GroupChatScreen must scroll — the " +
                "attachment menu's seven rows and a long translation overflow too",
            sheets,
            scrolled + selfScrolling,
        )
    }

    @Test
    fun `the 1 to 1 twin of the translate sheet was fixed in the same pass`() {
        // ChatScreen.TranslateBottomSheet is the same function duplicated. Leaving
        // the twin broken is precisely how August's fix reached October.
        val body = bodyOf(chatScreen, "private fun TranslateBottomSheet(")
        assertTrue(
            "ChatScreen.TranslateBottomSheet must scroll like its group twin",
            body.contains(scrolls),
        )
    }

    // ===================================================== the real invariant

    /**
     * Files whose sheet is deliberately not scrollable, with the reason. A sheet
     * belongs here only if its content is genuinely bounded; "it fits on my phone"
     * is not a reason, because landscape and a 640 dp phone both exist.
     */
    private val shortSheets = mapOf(
        "VoiceMessagePreviewSheet.kt" to
            "header + waveform + one button row, ~300 dp, and it carries " +
                "navigationBarsPadding",
        // __OLED_GLASS_2026_11_16__ not a sheet: the drop-in wrapper that turns
        // every sheet into glass under OLED black. It forwards its caller's
        // `content` untouched, and every caller is checked here on its own.
        "GlassComponents.kt" to "the OshiModalBottomSheet wrapper; forwards its caller's body",
    )

    @Test
    fun `every bottom sheet in the UI tree either scrolls, delegates, or is listed as short`() {
        val root = listOf(
            File("src/main/java/com/oshi/messenger/ui"),
            File("app/src/main/java/com/oshi/messenger/ui"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("the ui source tree moved", root)

        val files = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to stripComments(it.readText()) }

        /** A body is reachable if it scrolls itself or hands its slot to a lazy list. */
        fun handlesItsOwnHeight(src: String) =
            src.contains("verticalScroll(") ||
                src.contains("LazyColumn(") ||
                src.contains("LazyVerticalGrid(")

        /**
         * The composables called directly inside each `ModalBottomSheet { … }`
         * trailing lambda. Found by matching parentheses rather than by regex,
         * because the argument list itself contains lambdas — `onDismissRequest =
         * { … }` — and a regex that stops at the first `{` reports every
         * delegating sheet as broken. MapScreen did exactly that on the first run.
         */
        fun delegatesTo(src: String): List<String> {
            val out = mutableListOf<String>()
            var at = src.indexOf("ModalBottomSheet(")
            while (at >= 0) {
                var i = at + "ModalBottomSheet".length
                var depth = 0
                while (i < src.length) {
                    when (src[i]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) break }
                    }
                    i++
                }
                // Past the argument list: expect the trailing lambda, then read the
                // first identifier it calls.
                val brace = src.indexOf('{', i)
                if (brace > 0) {
                    val head = src.substring(brace + 1).trimStart()
                    val name = head.takeWhile { it.isLetterOrDigit() || it == '_' }
                    if (name.isNotEmpty() && head.drop(name.length).startsWith("(")) {
                        out.add(name)
                    }
                }
                at = src.indexOf("ModalBottomSheet(", at + 1)
            }
            return out
        }

        val offenders = mutableListOf<String>()
        for ((name, src) in files) {
            if (!src.contains("ModalBottomSheet(")) continue
            if (name in shortSheets) continue
            if (handlesItsOwnHeight(src)) continue

            // Everything it delegates to must itself be reachable, and must be a
            // composable this tree actually defines — a name we cannot resolve is
            // an offender, not a pass.
            val targets = delegatesTo(src)
            val allResolved = targets.isNotEmpty() && targets.all { target ->
                files.values.any { it.contains("fun $target(") && handlesItsOwnHeight(it) }
            }
            if (!allResolved) offenders.add(name)
        }

        assertEquals(
            "a ModalBottomSheet whose body neither scrolls nor delegates will strand " +
                "its last rows on a short or landscape screen. Fix it, or add it to " +
                "`shortSheets` with the reason. Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )
    }
}
