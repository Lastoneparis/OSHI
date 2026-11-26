package com.oshi.messenger.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * __PER_APP_LANGUAGE_2026_11_20__ The app's own language, independent of the phone's.
 *
 * Default is "follow the phone" (no override stored), so a fresh install opens in the
 * system language exactly as before.
 *
 * - Android 13+: the choice goes to the framework [LocaleManager]. The system stores it,
 *   recreates the activities, and shows the same choice in Settings › Apps › OSHI ›
 *   Language (fed by res/xml/locales_config.xml, android:localeConfig).
 * - Android 8–12: no framework support and the app is not an AppCompatActivity (so
 *   AppCompatDelegate.setApplicationLocales would be a no-op here). The tag is kept in a
 *   private preference and applied in attachBaseContext of the Application and of
 *   MainActivity via [wrap]; the picker then recreates the activity.
 *
 * Tags are BCP-47. Hebrew is "he" (Android maps it onto the legacy values-iw folder);
 * Chinese uses zh-CN / zh-TW so they hit values-zh-rCN / values-zh-rTW directly.
 */
object AppLanguage {

    /** Language tag → its own name (autonym). Order = order in the picker. Base English first. */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "en" to "English",
        "ar" to "العربية",
        "ca" to "Català",
        "cs" to "Čeština",
        "da" to "Dansk",
        "de" to "Deutsch",
        "el" to "Ελληνικά",
        "es" to "Español",
        "fa" to "فارسی",
        "fi" to "Suomi",
        "fr" to "Français",
        "he" to "עברית",
        "hi" to "हिन्दी",
        "hu" to "Magyar",
        "id" to "Bahasa Indonesia",
        "it" to "Italiano",
        "ja" to "日本語",
        "ko" to "한국어",
        "ms" to "Bahasa Melayu",
        "nb" to "Norsk bokmål",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "pt-BR" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "ro" to "Română",
        "ru" to "Русский",
        "sk" to "Slovenčina",
        "sv" to "Svenska",
        "th" to "ไทย",
        "tr" to "Türkçe",
        "uk" to "Українська",
        "vi" to "Tiếng Việt",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
    )

    private const val PREFS = "oshi_app_language"
    private const val KEY_TAG = "tag"

    /** The chosen tag, or null when the app follows the phone. */
    fun current(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (list != null && !list.isEmpty) return normalize(list[0].toLanguageTag())
            return null
        }
        return storedTag(context)
    }

    /** Display name of the current choice, or null for "system default". */
    fun currentName(context: Context): String? =
        current(context)?.let { tag -> SUPPORTED.firstOrNull { it.first == tag }?.second }

    /**
     * Apply [tag] (null = follow the phone). On Android 13+ the framework recreates the
     * activity itself; below that the caller's activity is recreated here.
     */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        prefs(activity).edit().apply {
            if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag)
        }.commit()
        activity.recreate()
    }

    /**
     * Android 8–12 only: returns a context whose resources use the stored language.
     * Identity on 13+ (the framework already did it) and when nothing is stored.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = storedTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * Android 13+: a choice stored by this app while the phone ran Android 12 or lower is
     * handed to the framework once after an OS upgrade, so the user does not lose it.
     */
    fun migrateToFramework(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val tag = storedTag(context) ?: return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        if (lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(tag)
        prefs(context).edit().remove(KEY_TAG).apply()
    }

    private fun storedTag(context: Context): String? =
        prefs(context).getString(KEY_TAG, null)?.takeIf { t -> SUPPORTED.any { it.first == t } }

    private fun prefs(context: Context) =
        context.applicationContext?.let { it.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Map what the framework reports back onto our tags ("iw" → "he", "zh-Hans-CN" → "zh-CN"…). */
    internal fun normalize(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        val lang = when (l.language) { "iw" -> "he"; "in" -> "id"; "nn", "no" -> "nb"; else -> l.language }
        return when (lang) {
            "pt" -> if (l.country == "PT") "pt-PT" else "pt-BR"
            "zh" -> if (l.country == "TW" || l.country == "HK" || l.country == "MO" || l.script == "Hant") "zh-TW" else "zh-CN"
            else -> lang
        }
    }
}
