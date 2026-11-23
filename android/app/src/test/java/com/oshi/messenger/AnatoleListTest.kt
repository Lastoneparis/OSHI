package com.oshi.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bugs reported by Anatole on 2026-11-07, as source guards.
 *
 * Both of these are wiring facts — "this code consults that store" — which no
 * unit test can reach without standing up Hilt, Room and a Context. [SourceText]
 * strips comments first, so the prose explaining each fix cannot satisfy it.
 */
class AnatoleListTest {

    // ---------------- N2: mentions showed keys instead of names

    /**
     * `mentionCandidates` had two name sources: your contact alias, and the
     * senderName off a message that member had sent in this group and that we
     * still store. Anyone else rendered as `key.take(5)` — five base64
     * characters — which is "seulement quelques personnes ont leur nom et pour
     * le reste ça affiche leur clé".
     *
     * The names were already on the device: `GroupMemberAliasStore` has kept
     * every definition's `members[].alias` since 23/09.
     */
    @Test
    fun `the mention picker consults the definition aliases it already stores`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/service/GroupManager.kt")
        val fn = SourceText.between(src, "fun mentionCandidates(groupId: String)", "MentionWire.Mention(key, name)")
        assertTrue(
            "mentionCandidates no longer reads GroupMemberAliasStore — members who are not " +
                "contacts and have not spoken go back to rendering as key.take(5)",
            fn.contains("GroupMemberAliasStore.forGroup"),
        )
        assertTrue(
            "the definition alias must be tried BEFORE the key prefix, or it changes nothing",
            fn.indexOf("fromDefinition") < fn.indexOf("key.take(5)"),
        )
    }

    /**
     * Strictly a fallback. Reordering could rename someone the user had
     * deliberately aliased, so the contact alias must still come first.
     */
    @Test
    fun `a contact alias still outranks what the group definition claims`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/service/GroupManager.kt")
        val fn = SourceText.between(src, "fun mentionCandidates(groupId: String)", "MentionWire.Mention(key, name)")
        val listing = SourceText.between(fn, "listOfNotNull(", ")")
        assertTrue("expected the resolution order on one line, got: $listing", listing.contains("alias"))
        assertTrue(
            "a user-set contact alias must stay the strongest source: $listing",
            listing.indexOf("alias") < listing.indexOf("fromDefinition"),
        )
    }

    // ---------------- N6: "Créé le" showed today's date

    /**
     * `key_creation_date` was READ with a `?: System.currentTimeMillis()`
     * fallback and written by nothing at all, so Settings showed today — a date
     * that moved every day, for everyone, since the row shipped.
     */
    @Test
    fun `identity creation stamps the key creation date`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/MainViewModel.kt")
        val fn = SourceText.between(src, "fun createIdentity(displayName: String)", "_isOnboarded.value = true")
        assertTrue(
            "createIdentity no longer stamps key_creation_date — the Settings row goes back " +
                "to showing today's date forever",
            fn.contains("KEY_KEY_CREATION_DATE"),
        )
    }

    /** The read side must never invent a date again. */
    @Test
    fun `the settings screen does not fall back to now for the key age`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt")
        assertFalse(
            "the key age is being fabricated from the clock again",
            src.contains("preferences[KEY_KEY_CREATION_DATE] ?: System.currentTimeMillis()"),
        )
        assertTrue(
            "no backfill for identities older than the stamping fix",
            src.contains("backfillKeyCreationDate()"),
        )
    }

    /**
     * The backfill writes to DataStore, and its caller runs inside
     * `dataStore.data.collect`. Editing a DataStore from inside a collector of
     * that same flow can deadlock, so the write has to be launched separately.
     */
    @Test
    fun `the backfill does not write to the datastore it is being read from`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt")
        val fn = SourceText.between(src, "private fun backfillKeyCreationDate(): Long {", "return seed")
        assertTrue(
            "the backfill edits DataStore inline; it is called from inside dataStore.data.collect",
            fn.contains("viewModelScope.launch"),
        )
        assertTrue(
            "the edit must re-check the key: this collector fires more than once",
            fn.contains("if (prefs[KEY_KEY_CREATION_DATE] == null)"),
        )
    }

    // ---------------- N4: the map always opened in Paris

    /**
     * `_mapCenterLat/_mapCenterLon` were `MutableStateFlow(48.8566/2.3522)` held
     * in memory only, and nothing anywhere persisted the centre or the last
     * known location. Every launch started in Paris and stayed there until a GPS
     * fix arrived — indoors, with location denied, or on a slow fix, it never
     * moved. The fallback was not a fallback, it was the default experience.
     */
    @Test
    fun `the map centre is seeded from the last one, not from Paris`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/MapViewModel.kt")
        assertFalse(
            "the map centre is a bare Paris literal again — it will never remember where you were",
            src.contains("MutableStateFlow(48.8566)"),
        )
        assertTrue("the centre is not read back from storage", src.contains("PREF_CENTER_LAT"))
        assertTrue("nothing persists the centre", src.contains("rememberMapCenter("))
    }

    /**
     * Persisting 0,0 would park the map in the Atlantic permanently, and Null
     * Island is exactly what a bad position decode produces.
     */
    @Test
    fun `a null island centre is never persisted`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/MapViewModel.kt")
        val fn = SourceText.between(src, "private fun rememberMapCenter(", "mapPrefs.edit()")
        assertTrue("0,0 is not refused", fn.contains("lat == 0.0 && lon == 0.0"))
        assertTrue("out-of-range coordinates are not refused", fn.contains("-90.0..90.0"))
    }

    /** iOS/macOS had the identical literal and the identical omission. */
    @Test
    fun `the swift map remembers its centre too`() {
        val f = listOf(java.io.File("../OSHI/OfflineMapView.swift"),
                       java.io.File("../../OSHI/OfflineMapView.swift")).firstOrNull { it.exists() }
        assertTrue("OSHI/OfflineMapView.swift not found", f != null)
        val swift = SourceText.stripComments(f!!.readText())
        assertTrue("the Swift map no longer seeds from the last centre",
                   swift.contains("lastCenteredRegion()"))
        assertTrue("nothing persists the Swift map centre", swift.contains("rememberCenter("))
    }

    // ---------------- N8: a black band between the header and the messages

    /**
     * Every wallpaper branch must paint a floor. Two of them were a bare
     * `Modifier` and drew their backdrop as a CHILD instead — the image one is
     * decoded off-thread and its decode is wrapped in `catch { null }`, so a
     * wallpaper that fails to decode leaves the thread unpainted FOR EVER and
     * the dark scheme shows through as black.
     *
     * Groups have no wallpaper at all, which is why the band showed up in 1:1
     * and nowhere else — the asymmetry in the report is the clue to the cause.
     */
    @Test
    fun `every chat wallpaper branch paints a background`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        val branch = SourceText.window(src, "when (wallpaper) {", 1600)
        for (kind in listOf("Default", "SolidColor", "Gradient", "EmojiPattern",
                            "OshiDynamic", "ImageWallpaper")) {
            val at = branch.indexOf("ChatWallpaper.$kind ->")
            assertTrue("no branch for ChatWallpaper.$kind", at >= 0)
            val arm = branch.substring(at, minOf(at + 160, branch.length))
            assertTrue(
                "ChatWallpaper.$kind has no background — a backdrop drawn as a child is not " +
                    "a floor, and an image that fails to decode never arrives",
                arm.contains(".background("),
            )
        }
    }

    // ---------------- N4: "an enormous number of points appear"

    /**
     * Whether those points are in the RIGHT places is still open with Anatole.
     * Whether he should be able to stop seeing them is not: the map dropped up
     * to fifty offline POIs the moment a fix landed, with no way to decline.
     *
     * The toggle answers the complaint as he stated it, independently of the
     * cause — which is why it was worth building before his answer, while a fix
     * aimed at the coordinates would not have been.
     */
    @Test
    fun `the map points can be turned off, and the choice is remembered`() {
        val vm = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/MapViewModel.kt")
        assertTrue("no toggle on the view model", vm.contains("fun toggleShowPOIs()"))
        assertTrue(
            "the choice is not persisted — a setting you re-make every launch is not one",
            vm.contains("putBoolean(PREF_SHOW_POIS"),
        )
        assertTrue(
            "the toggle does not read its stored value back at startup",
            vm.contains("mapPrefs.getBoolean(PREF_SHOW_POIS, true)"),
        )
    }

    /** Defaulting to off would silently remove a feature people use. */
    @Test
    fun `places stay visible by default`() {
        val vm = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/MapViewModel.kt")
        assertTrue(
            "the default flipped to hidden — that is a removal, not a choice",
            vm.contains("getBoolean(PREF_SHOW_POIS, true)"),
        )
    }

    /** A toggle the renderer ignores is the dead-control bug this project keeps shipping. */
    @Test
    fun `the map actually honours the toggle`() {
        val map = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/MapScreen.kt")
        assertTrue(
            "the POI loop ignores showPOIs — the switch would do nothing",
            map.contains("if (showPOIs) visiblePOIs else emptyList()"),
        )
        assertTrue("nothing calls the toggle", map.contains("viewModel.toggleShowPOIs()"))
    }
}
