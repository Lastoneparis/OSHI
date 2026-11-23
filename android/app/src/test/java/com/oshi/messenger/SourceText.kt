package com.oshi.messenger

import java.io.File
import org.junit.Assert.assertTrue

/**
 * Reading main-source text from a unit test, with comments removed.
 *
 * Extracted after `MailboxWayOutTest` and the iOS `MailCloseAffordanceTests`
 * both failed on their OWN prose: a guard that searches for `navigationIcon`
 * trips on the comment explaining why `navigationIcon` was missing. Every
 * source-scanning guard wants the same two things, so they share them.
 *
 * Source scanning is for Compose-structure facts that no JVM unit test can
 * reach — `testReleaseUnitTest` cannot host Compose at all (see
 * `compose-ui-tests-only-run-on-the-debug-variant`).
 */
object SourceText {

    fun read(relative: String): String {
        val f = File(relative)
        assertTrue("$relative not found at ${f.absolutePath}", f.exists())
        return stripComments(f.readText())
    }

    // __A_STRING_IS_NOT_A_COMMENT_2026_11_07__
    //
    // Drops `//` line comments and block comments, keeps `://` so URLs survive,
    // and does NOT look for comment markers inside string literals.
    //
    // THE BUG THIS CLOSES. `ChatScreen.kt:1143` calls
    // `documentPickerLauncher.launch(arrayOf("<star><slash><star>"))` — the MIME
    // wildcard. The old scanner was string-unaware, so the slash-star inside
    // that literal opened a block comment that ran until the next star-slash two
    // thousand lines later: 120 KB of real code, 2 225 lines of ChatScreen.kt,
    // silently blanked before any guard looked at them. Eight main-source files
    // carry that sequence inside a string, GroupChatScreen.kt and
    // SettingsScreen.kt among them.
    //
    // (Written with <star>/<slash> spelled out on purpose: the first draft of
    // this explanation used the real characters inside a KDoc block and closed
    // the comment early, which is the same bug one layer up.)
    //
    // WHY THIS IS WORSE THAN A FALSE FAILURE. A guard asserting a pattern is
    // PRESENT fails loudly and gets investigated — that is how this was found.
    // A guard asserting a pattern is ABSENT (`assertFalse(src.contains(bad))`)
    // passes VACUOUSLY over blanked code: it reports the defect is gone while
    // the defect sits right there. Half the guards in this directory are that
    // shape. Same family as `a-scanner-that-skips-what-it-cannot-read`.
    //
    // Newlines inside skipped regions are preserved so slices keep their shape.
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '

            // Raw string: """ … """ — no escapes inside, so the only exit is the
            // closing triple quote. Kept verbatim; it is code the guards may want.
            if (c == '"' && source.startsWith("\"\"\"", i)) {
                val end = source.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) source.length else end + 3
                out.append(source, i, stop)
                i = stop
                continue
            }
            // Ordinary string or char literal. A backslash escapes the next
            // character, so `"\""` does not end the string.
            if (c == '"' || c == '\'') {
                val quote = c
                out.append(c)
                i++
                while (i < source.length) {
                    val d = source[i]
                    out.append(d)
                    i++
                    if (d == '\\' && i < source.length) { out.append(source[i]); i++; continue }
                    if (d == quote) break
                    // An unterminated literal must not eat the rest of the file:
                    // stop at the newline, the way the compiler does.
                    if (d == '\n') break
                }
                continue
            }
            if (c == '/' && next == '*') {
                i += 2
                while (i < source.length && !(source[i] == '*' && i + 1 < source.length && source[i + 1] == '/')) {
                    if (source[i] == '\n') out.append('\n')
                    i++
                }
                i = minOf(i + 2, source.length)
                continue
            }
            if (c == '/' && next == '/' && (i == 0 || source[i - 1] != ':')) {
                while (i < source.length && source[i] != '\n') i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** The text between [start] and the first [end] after it. */
    fun between(source: String, start: String, end: String): String {
        val a = source.indexOf(start)
        assertTrue("could not find \"$start\"", a >= 0)
        val b = source.indexOf(end, a + start.length)
        assertTrue("could not find \"$end\" after \"$start\"", b >= 0)
        return source.substring(a + start.length, b)
    }

    /** [length] characters from the start of [needle]. For call sites whose own parens nest. */
    fun window(source: String, needle: String, length: Int = 400): String {
        val at = source.indexOf(needle)
        assertTrue("could not find \"$needle\"", at >= 0)
        return source.substring(at, minOf(at + length, source.length))
    }
}
