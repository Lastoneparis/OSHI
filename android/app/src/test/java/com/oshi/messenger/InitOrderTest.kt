package com.oshi.messenger

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * __INIT_ORDER_2026_09_28__ Kotlin runs property initializers and `init` blocks in SOURCE order.
 * A property declared below an `init` block that reads it is still unset while `init` runs: a
 * `by lazy` delegate is null (NullPointerException in the constructor), a plain `val` holds its
 * default (0, null, false).
 *
 * 1.6.32/73 shipped two of these from the 25/09 at-rest work:
 *  - `CheckInManager.sessionStore` — the manager is built by `CheckInOffRamp`, hosted at the ROOT
 *    of MainActivity, so the app closed ~3 s after launch on every device that reached the main
 *    screen (Vitals: `CheckInManager.getSessionStore` NPE, and a user report on a Vivo X300);
 *  - `AIChatViewModel.store` — opening the AI assistant crashed (Vitals: obfuscated `<init>` NPE).
 *
 * The compiler does not warn. This test does.
 */
class InitOrderTest {

    private fun moduleDir(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
        error("unreachable")
    }

    private val mainDir by lazy { moduleDir("src/main/java/com/oshi/messenger") }

    private fun source(relative: String) = File(mainDir, relative).readText()

    /** Line index of `    <mods> val|var <name>` that is a stored property (not a getter). */
    private fun storedDeclLine(lines: List<String>, name: String): Int? {
        val re = Regex("""^    (?:(?:private|internal|protected|public|override|@Volatile)\s+)*(?:val|var)\s+$name\b(.*)$""")
        lines.forEachIndexed { i, l ->
            val m = re.find(l) ?: return@forEachIndexed
            val rest = m.groupValues[1]
            // `val x: T get() = …` / `val x: T` followed by a getter line is not stored.
            val nextIsGetter = lines.getOrNull(i + 1)?.trimStart()?.startsWith("get()") == true
            if (rest.contains("get()") || (nextIsGetter && !rest.contains("=") && !rest.contains(" by "))) return null
            return i
        }
        return null
    }

    private fun initLines(lines: List<String>): List<Int> =
        lines.indices.filter { lines[it].matches(Regex("""^    init\s*\{.*""")) }

    private fun assertDeclaredBeforeInit(relative: String, property: String) {
        val lines = source(relative).lines()
        val decl = storedDeclLine(lines, property) ?: run { fail("$property not found in $relative"); return }
        val firstInit = initLines(lines).first()
        assertTrue(
            "$relative: `$property` (line ${decl + 1}) must be declared before `init` (line ${firstInit + 1}) — init reads it",
            decl < firstInit,
        )
    }

    @Test fun checkInSessionStoreIsDeclaredBeforeInit() =
        assertDeclaredBeforeInit("service/CheckInManager.kt", "sessionStore")

    @Test fun aiChatStoreIsDeclaredBeforeInit() =
        assertDeclaredBeforeInit("ui/viewmodel/AIChatViewModel.kt", "store")

    @Test fun groupPersistCapIsNotAFieldReadBeforeItsInitializer() {
        val lines = source("service/GroupManager.kt").lines()
        val decl = storedDeclLine(lines, "MAX_MESSAGES_PER_GROUP_PERSIST")
        val firstInit = initLines(lines).first()
        assertTrue(
            "GroupManager.MAX_MESSAGES_PER_GROUP_PERSIST is a stored field declared after init (would be 0 there)",
            decl == null || decl < firstInit,
        )
    }

    /**
     * Read-before-declared cases that are safe, each with its reason. Anything new must be fixed or
     * justified here.
     */
    private val allowed = setOf(
        // Read only inside `viewModelScope.launch { dataStore.data.collect { … } }`: the collect
        // suspends before its first emission, so the body runs after the constructor returned.
        "ui/viewmodel/MainViewModel.kt#identityBackupPrefs",
    )

    /** Index of the line closing the block opened on [open] (brace counting, strings ignored). */
    private fun blockEnd(lines: List<String>, open: Int): Int {
        var depth = 0
        for (i in open until lines.size) {
            val code = lines[i].replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "\"\"").substringBefore("//")
            depth += code.count { it == '{' } - code.count { it == '}' }
            if (depth <= 0 && i > open || (depth == 0 && code.contains('{'))) return i
        }
        return lines.size - 1
    }

    /**
     * Every class body in the app: a stored property declared after an `init` block (in the same
     * class) must not be named in that block, nor in a same-class function the block calls.
     */
    @Test fun noInitBlockReadsAPropertyDeclaredBelowIt() {
        val offenders = mutableListOf<String>()
        val defaultInit = Regex("""=\s*(false|0|0L|0f|0\.0|null|"")\s*$""")
        mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(mainDir).path
            val lines = f.readText().lines()
            for (initAt in initLines(lines)) {
                val classEnd = (initAt until lines.size).firstOrNull { lines[it].startsWith("}") } ?: lines.size
                val bodyEnd = blockEnd(lines, initAt)
                val body = lines.subList(initAt, bodyEnd + 1).joinToString("\n")
                val classStart = (initAt downTo 0).firstOrNull { lines[it].matches(Regex("""^\S.*\b(class|object)\b.*""")) } ?: 0
                val called = Regex("""(?<![.\w])([a-z]\w*)\(""").findAll(body).map { it.groupValues[1] }.toSet()
                val reach = StringBuilder(body)
                for (fn in called) {
                    val at = (classStart until classEnd).firstOrNull {
                        lines[it].matches(Regex("""^    (?:private |internal |protected |override |suspend )*fun $fn\(.*"""))
                    } ?: continue
                    reach.append('\n').append(lines.subList(at, blockEnd(lines, at) + 1).joinToString("\n"))
                }
                // string literals do not read properties ("dsk seed must be 32 bytes")
                val reached = reach.toString().replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), "\"\"")
                val declRe = Regex("""^    (?:(?:private|internal|protected|public|override)\s+)*(?:val|var)\s+(\w+)(.*)$""")
                for (i in (bodyEnd + 1) until classEnd) {
                    val m = declRe.find(lines[i]) ?: continue
                    val name = m.groupValues[1]
                    val rest = m.groupValues[2]
                    if (rest.contains("get()") || lines.getOrNull(i + 1)?.trimStart()?.startsWith("get()") == true) continue
                    if (!rest.contains("=") && !rest.contains(" by ")) continue
                    if (defaultInit.containsMatchIn(rest)) continue      // default value == initializer: harmless
                    if ("$rel#$name" in allowed) continue
                    if (Regex("""\b$name\b""").containsMatchIn(reached)) {
                        offenders += "$rel:${i + 1} `$name` declared after init@${initAt + 1}"
                    }
                }
            }
        }
        assertTrue("properties read by init before their declaration:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
