package com.oshi.messenger

import com.oshi.messenger.util.PanicGesture
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (N6, Anatole 13/11) The three-taps-on-the-logo wipe is opt-in.
 *
 * Two halves: the pure decision ([PanicGesture.isArmed]) and a source guard
 * that every caller of the wipe goes through the gate — the rule from past
 * mistakes being "after adding a toggle, find ALL the starters of what it
 * disables", and a scan is how the next starter gets caught.
 */
class PanicWipeOptInTest {

    @Test
    fun `off on a fresh install and after an upgrade`() {
        assertFalse(PanicGesture.DEFAULT_ARMED)
        // Absent key = every install before the switch existed.
        assertFalse(PanicGesture.isArmed(null))
        assertFalse(PanicGesture.isArmed(false))
    }

    @Test
    fun `only an explicit opt-in arms the gesture`() {
        assertTrue(PanicGesture.isArmed(true))
    }

    @Test
    fun `the wipe itself checks the opt-in before touching anything`() {
        val vm = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt")
        val body = vm.substringAfter("fun panicWipe()")
        val gate = body.indexOf("PanicGesture.isArmed(")
        val firstStep = body.indexOf("meshManager.stopAll()")
        assertTrue("panicWipe() must consult PanicGesture.isArmed", gate >= 0)
        assertTrue("the gate must come before the first wipe step", firstStep > gate)
    }

    @Test
    fun `every starter of the wipe is known`() {
        // If a new caller appears (a notification action, a widget, a quick
        // settings tile...), this fails, and whoever added it has to decide
        // whether it honours the opt-in — not discover it from a user.
        val root = File("src/main/java")
        assertTrue(root.isDirectory)
        val callers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { SourceText.stripComments(it.readText()).contains("panicWipe()") }
            .map { it.name }
            .toSortedSet()
        assertEquals(sortedSetOf("ConversationsScreen.kt", "SettingsViewModel.kt"), callers)
    }

    @Test
    fun `the setting is reachable from settings`() {
        val screen = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/SettingsScreen.kt")
        assertTrue(screen.contains("settingsViewModel.setPanicWipeOnLogo("))
        assertTrue(screen.contains("R.string.settings_panic_wipe_title"))
    }
}
