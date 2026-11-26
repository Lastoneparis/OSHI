package com.oshi.messenger

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * __ANDROID_I18N_COMPLETE_2026_11_20__ Every translatable string must exist in every one of
 * the 33 shipped locales (the same set as iOS OSHI/<lang>.lproj), in the app AND the wear
 * module.
 *
 * Measured on release/1.7.1 (b5bc9e4c): 165 keys from res/values had no entry in ANY
 * locale (78 in values-fr), so a French, Japanese or Arabic user saw those screens in
 * English. Android's silent fallback to values/ is exactly what lets that ship unseen;
 * this test is the noise it never made.
 *
 * Also guards: format placeholders identical to English (a dropped %1$s crashes or
 * shows a raw token), and res/xml/locales_config.xml == AppLanguage.SUPPORTED == the
 * folders (a language offered by the picker must actually be translated).
 *
 * Gradle re-runs this after a resource-only edit because src/main/res is a declared
 * input of the test task (see app/build.gradle.kts, "resourcesReadByAssetGuards").
 * Reproduce outside Gradle: python3 tools/i18n/missing_keys.py app/src/main/res
 */
class LocaleCompletenessTest {

    private val locales = listOf(
        "ar", "ca", "cs", "da", "de", "el", "es", "fa", "fi", "fr", "iw", "hi", "hu", "id",
        "it", "ja", "ko", "ms", "nb", "nl", "pl", "pt-rBR", "pt-rPT", "ro", "ru", "sk", "sv",
        "th", "tr", "uk", "vi", "zh-rCN", "zh-rTW",
    )

    private val placeholder = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[sdfxXc]""")

    private fun repoDir(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
        throw IllegalStateException()
    }

    private class Entry(val translatable: Boolean, val texts: List<String>)

    private fun load(folder: File): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        val files = folder.listFiles { f -> f.name.endsWith(".xml") }?.sortedBy { it.name } ?: return out
        val factory = DocumentBuilderFactory.newInstance()
        for (file in files) {
            val root = factory.newDocumentBuilder().parse(file).documentElement
            val children = root.childNodes
            for (i in 0 until children.length) {
                val el = children.item(i) as? Element ?: continue
                if (el.tagName !in setOf("string", "plurals", "string-array")) continue
                val texts = if (el.tagName == "string") listOf(el.textContent)
                else (0 until el.getElementsByTagName("item").length)
                    .map { el.getElementsByTagName("item").item(it).textContent }
                out[el.getAttribute("name")] =
                    Entry(el.getAttribute("translatable") != "false", texts)
            }
        }
        return out
    }

    private fun checkModule(res: File) {
        val base = load(File(res, "values")).filterValues { it.translatable }
        assertTrue("no strings found in $res", base.isNotEmpty())
        val problems = mutableListOf<String>()
        for (loc in locales) {
            val folder = File(res, "values-$loc")
            if (!folder.isDirectory) { problems += "values-$loc: folder missing"; continue }
            val have = load(folder)
            val missing = base.keys.filter { it !in have }
            if (missing.isNotEmpty()) {
                problems += "values-$loc: ${missing.size} missing: " +
                    missing.take(12).joinToString() + if (missing.size > 12) ", …" else ""
            }
            for ((key, entry) in have) {
                val en = base[key] ?: continue
                if (en.texts.size != 1 || entry.texts.size != 1) continue
                val want = placeholder.findAll(en.texts[0].replace("%%", "")).map { it.value }.sorted().toList()
                val got = placeholder.findAll(entry.texts[0].replace("%%", "")).map { it.value }.sorted().toList()
                if (want != got) problems += "values-$loc/$key: placeholders $got, English has $want"
            }
        }
        if (problems.isNotEmpty()) {
            fail("${res.path}: translations incomplete (${problems.size} problem(s)):\n" +
                problems.joinToString("\n"))
        }
    }

    @Test
    fun appStringsAreTranslatedInEveryLocale() = checkModule(repoDir("app/src/main/res"))

    @Test
    fun wearStringsAreTranslatedInEveryLocale() = checkModule(repoDir("wear/src/main/res"))

    @Test
    fun localeConfigMatchesPickerAndFolders() {
        val res = repoDir("app/src/main/res")
        val config = File(res, "xml/locales_config.xml").readText()
        val configTags = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()

        val src = File(repoDir("app/src/main/java"), "com/oshi/messenger/ui/locale/AppLanguage.kt").readText()
        val pickerTags = Regex("""^\s*"([A-Za-z-]+)" to """", RegexOption.MULTILINE)
            .findAll(src).map { it.groupValues[1] }.toList()

        assertEquals("locales_config.xml vs AppLanguage.SUPPORTED", configTags, pickerTags)
        assertEquals("one entry per locale folder + English", locales.size + 1, configTags.size)

        val folderFor = { tag: String ->
            when (tag) {
                "he" -> "iw"
                else -> tag.replace(Regex("""-([A-Z]{2})$"""), "-r$1")
            }
        }
        val expected = (locales + "en").sorted()
        assertEquals(expected, configTags.map { if (it == "en") "en" else folderFor(it) }.sorted())
    }
}
