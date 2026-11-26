package com.oshi.messenger.ui.theme

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Log
import com.oshi.messenger.R

// ─────────────────────────────────────────────────────────────────────────
// __OLED_SPLASH_2026_11_16__ The cold start, before the app can read a setting.
//
// With OLED black on, every cold start still opened on the purple #140526
// system splash and then flashed the #121212 window background before the
// first Compose frame: both are drawn from the MANIFEST theme by the system,
// long before DataStore (asynchronous) has answered.
//
// Two levers, both needing the choice SYNCHRONOUSLY at process start — hence a
// tiny SharedPreferences mirror of the two appearance keys, written on every
// change and on every DataStore load (so an upgrade from a build without the
// mirror converges on its first launch):
//
// 1. The system splash (API 33+): `SplashScreen.setSplashScreenTheme`. The
//    system PERSISTS that theme per activity and uses it from the NEXT launch.
//    The splash is drawn in the PHONE's light/dark mode (the app's forced
//    "Dark" is a Compose-level choice the system knows nothing about), so:
//      Theme "Dark"   + OLED → a theme that is black in both phone modes;
//      Theme "System" + OLED → black only under values-night, purple otherwise;
//      anything else         → reset (ID_NULL) to the manifest's purple splash.
// 2. The window background (every API): set to black in `onCreate`, before the
//    first frame, whenever OLED black is in force. That covers the gap between
//    the splash and Compose's first draw.
//
// NOT covered, on purpose: API 26–32. API 31–32 have the system splash but no
// `setSplashScreenTheme`, and below 31 the starting window is the manifest
// theme's background (Theme.OSHI.Launcher: the logo on `launch_background`);
// the only way to change either at runtime is to swap launcher
// activity-aliases, which kills the task and can drop the home-screen
// shortcut. Those devices keep the brand-purple splash; the launch screen
// starts on the same purple and fades to black with its intro
// ([splashGroundIsBlack]).
// ─────────────────────────────────────────────────────────────────────────

/** Which system splash the next cold start should use. Pure, unit-tested. */
enum class SplashChoice { Default, OledNight, OledAlways }

object ThemeBoot {
    private const val FILE = "theme_boot"
    private const val KEY_THEME = "theme"
    private const val KEY_OLED = "oled_black"
    private const val TAG = "ThemeBoot"

    fun splashChoice(themePref: String?, oledBlack: Boolean): SplashChoice = when {
        !oledBlack -> SplashChoice.Default
        themePref == ThemeSelection.DARK -> SplashChoice.OledAlways
        themePref == ThemeSelection.LIGHT -> SplashChoice.Default
        else -> SplashChoice.OledNight
    }

    /** The style resource for [choice], or 0 (`Resources.ID_NULL`) for the manifest default. */
    fun splashThemeRes(choice: SplashChoice): Int = when (choice) {
        SplashChoice.Default -> 0
        SplashChoice.OledNight -> R.style.Theme_OSHI_SplashOledNight
        SplashChoice.OledAlways -> R.style.Theme_OSHI_SplashOledAlways
    }

    /**
     * __SPLASH_HANDOVER_2026_11_16__ The ground the starting surface showed on
     * this cold start, i.e. the ground LaunchScreen must paint under the logo
     * until the hand-over: black only where a black splash theme can exist
     * (API 33+, [splashThemeRes]); API 31–32's splash and API 26–30's starting
     * window (Theme.OSHI.Launcher) are always `launch_background`. Black ⇔
     * OLED in force: OledAlways is black in both phone modes, OledNight only in
     * night mode — exactly [windowIsOled]. Pure, unit-tested.
     */
    fun splashGroundIsBlack(sdkInt: Int, themePref: String?, oledBlack: Boolean, systemDark: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && windowIsOled(themePref, oledBlack, systemDark)

    /** Is OLED black in force for this window right now? */
    fun windowIsOled(themePref: String?, oledBlack: Boolean, systemDark: Boolean): Boolean =
        ThemeSelection.resolveOled(ThemeSelection.resolveDark(themePref, systemDark), oledBlack)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun readTheme(context: Context): String? = readTheme(prefs(context))
    fun readOled(context: Context): Boolean = readOled(prefs(context))
    fun write(context: Context, themePref: String, oledBlack: Boolean) = write(prefs(context), themePref, oledBlack)

    fun readTheme(p: SharedPreferences): String? = p.getString(KEY_THEME, null)
    fun readOled(p: SharedPreferences): Boolean = p.getBoolean(KEY_OLED, false)

    /**
     * Mirror the two preferences. `commit()`, not `apply()`: the value has to be
     * on disk if the process is killed right after the user flips the switch —
     * it is two keys, written only when they change. Returns whether it wrote.
     */
    fun write(p: SharedPreferences, themePref: String, oledBlack: Boolean): Boolean {
        if (p.getString(KEY_THEME, null) == themePref && p.contains(KEY_OLED) && p.getBoolean(KEY_OLED, false) == oledBlack) return false
        p.edit().putString(KEY_THEME, themePref).putBoolean(KEY_OLED, oledBlack).commit()
        return true
    }

    // __GLASS_ALL_THEMES_2026_11_21__ « Effet verre » mirrored for the first
    // frames (the light ground changes under glass): absent = never touched.
    private const val KEY_GLASS = "glass_effect"

    fun readGlass(context: Context): Boolean? = readGlass(prefs(context))
    fun writeGlass(context: Context, glass: Boolean?) = writeGlass(prefs(context), glass)

    fun readGlass(p: SharedPreferences): Boolean? = if (p.contains(KEY_GLASS)) p.getBoolean(KEY_GLASS, true) else null

    /** Same contract as [write]: commit, only on change; null removes the key. Returns whether it wrote. */
    fun writeGlass(p: SharedPreferences, glass: Boolean?): Boolean {
        if (readGlass(p) == glass) return false
        val e = p.edit()
        if (glass == null) e.remove(KEY_GLASS) else e.putBoolean(KEY_GLASS, glass)
        e.commit()
        return true
    }

    private fun systemDark(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Called from `MainActivity.onCreate` (from the mirror) and again whenever
     * the preferences change (from the live values): paints the window black
     * under OLED black and tells the system which splash to use next time.
     */
    fun apply(activity: Activity, themePref: String?, oledBlack: Boolean) {
        if (windowIsOled(themePref, oledBlack, systemDark(activity))) {
            activity.window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        } else {
            activity.window.setBackgroundDrawableResource(R.color.window_background_default)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                activity.splashScreen.setSplashScreenTheme(splashThemeRes(splashChoice(themePref, oledBlack)))
            }.onFailure { Log.w(TAG, "setSplashScreenTheme failed", it) }
        }
    }
}
