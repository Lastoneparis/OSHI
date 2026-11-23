package com.oshi.messenger

import com.oshi.messenger.util.PanicGesture
import com.oshi.messenger.util.PanicWipePolicy
import com.oshi.messenger.util.PanicWipePolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __PANIC_WIPE_2026_11_03__ The invariants of the panic wipe, and the gesture
 * that fires it.
 *
 * Every assertion here is one that, if broken, produces a panic wipe that LOOKS
 * like it worked: the app returns to the first-run screen either way. That is
 * the whole reason this file exists — there is no user-visible difference
 * between a wipe that erased the identity and one that left the backup blob
 * behind, until the app is reopened.
 */
class PanicWipePolicyTest {

    // ---------------------------------------------------------------- order

    @Test
    fun `radios are stopped before anything is destroyed`() {
        assertEquals(
            "a wipe that destroys state under a live mesh session leaves this " +
                "phone transmitting under keys that no longer exist",
            Step.STOP_RADIOS,
            PanicWipePolicy.ORDER.first()
        )
    }

    @Test
    fun `the notification shade is cleared before the database`() {
        // The shade is a copy of the message list. clearAllTables() does not
        // touch it, so a wipe that skips this leaves previews readable on a
        // locked screen.
        assertTrue(
            PanicWipePolicy.ORDER.indexOf(Step.CANCEL_NOTIFICATIONS) <
                PanicWipePolicy.ORDER.indexOf(Step.WIPE_DATABASE)
        )
    }

    @Test
    fun `media goes before the keys that protect it`() {
        assertTrue(
            "a decrypted file left on disk does not need our key to be recovered",
            PanicWipePolicy.ORDER.indexOf(Step.WIPE_MEDIA) <
                PanicWipePolicy.ORDER.indexOf(Step.WIPE_KEYS)
        )
    }

    @Test
    fun `preferences are wiped after the keys and before onboarding returns`() {
        val prefs = PanicWipePolicy.ORDER.indexOf(Step.WIPE_PREFERENCES)
        assertTrue(prefs > PanicWipePolicy.ORDER.indexOf(Step.WIPE_KEYS))
        assertTrue(prefs < PanicWipePolicy.ORDER.indexOf(Step.RETURN_TO_ONBOARDING))
    }

    @Test
    fun `returning to onboarding is last`() {
        assertEquals(Step.RETURN_TO_ONBOARDING, PanicWipePolicy.ORDER.last())
    }

    @Test
    fun `every step is in the order exactly once`() {
        // A step declared and never run is the shape of bug this codebase has
        // already shipped once: a catalog entry is not a feature.
        assertEquals(Step.entries.size, PanicWipePolicy.ORDER.size)
        assertEquals(Step.entries.toSet(), PanicWipePolicy.ORDER.toSet())
    }

    // -------------------------------------------------------------- network

    @Test
    fun `no step needs the network`() {
        for (step in Step.entries) {
            assertFalse(
                "$step would make the wipe defeatable by jamming the signal",
                PanicWipePolicy.requiresNetwork(step)
            )
        }
    }

    @Test
    fun `the push unregister budget is bounded and short`() {
        assertTrue(PanicWipePolicy.PUSH_UNREGISTER_BUDGET_MS > 0)
        assertTrue(
            "a panic wipe may not wait a noticeable time on a round trip",
            PanicWipePolicy.PUSH_UNREGISTER_BUDGET_MS <= 1_000L
        )
    }

    // ------------------------------------------------------- what must run

    @Test
    fun `the four destructive steps may never be skipped`() {
        assertEquals(
            setOf(Step.WIPE_MEDIA, Step.WIPE_DATABASE, Step.WIPE_KEYS, Step.WIPE_PREFERENCES),
            PanicWipePolicy.MUST_RUN
        )
    }

    @Test
    fun `a wipe missing the preferences step is not complete`() {
        // The one that hands the identity back on next launch via
        // recoverIdentityFromDevice().
        val everythingElse = PanicWipePolicy.ORDER.toSet() - Step.WIPE_PREFERENCES
        assertFalse(PanicWipePolicy.isComplete(everythingElse))
    }

    @Test
    fun `a wipe missing only a non-destructive step is complete`() {
        val noNotifications = PanicWipePolicy.ORDER.toSet() - Step.CANCEL_NOTIFICATIONS
        assertTrue(PanicWipePolicy.isComplete(noNotifications))
    }

    @Test
    fun `a second trigger while one is running is swallowed`() {
        assertTrue(PanicWipePolicy.shouldStart(alreadyRunning = false))
        assertFalse(
            "two wipes over one database is a crash, not a wipe",
            PanicWipePolicy.shouldStart(alreadyRunning = true)
        )
    }

    // -------------------------------------------------------------- gesture

    @Test
    fun `three taps inside the window fire`() {
        val t = 10_000L
        val taps = PanicGesture.retained(listOf(t, t + 200, t + 400), now = t + 400)
        assertTrue(PanicGesture.shouldTrigger(taps))
    }

    @Test
    fun `two taps do not fire`() {
        val t = 10_000L
        assertFalse(PanicGesture.shouldTrigger(PanicGesture.retained(listOf(t, t + 200), t + 200)))
    }

    @Test
    fun `three deliberate taps spread beyond the window do not fire`() {
        // Someone tapping the wordmark once each time they open the screen must
        // never accumulate a wipe.
        val first = 0L
        val second = 5_000L
        val third = 10_000L
        val afterSecond = PanicGesture.retained(listOf(first, second), now = second)
        assertEquals(listOf(second), afterSecond)
        val afterThird = PanicGesture.retained(afterSecond + third, now = third)
        assertEquals(listOf(third), afterThird)
        assertFalse(PanicGesture.shouldTrigger(afterThird))
    }

    @Test
    fun `a tap exactly at the window edge is already stale`() {
        val t = 0L
        assertEquals(
            emptyList<Long>(),
            PanicGesture.retained(listOf(t), now = t + PanicGesture.WINDOW_MILLIS)
        )
    }

    @Test
    fun `the gesture matches bitchat's three taps`() {
        assertEquals(3, PanicGesture.TAPS_REQUIRED)
    }

    // ------------------------------------------ starting over after the wipe

    @Test
    fun `panic clears the prekey and ratchet stores, which is what lets a new conversation work`() {
        // This is the whole answer to "can I message someone normally after a
        // panic wipe". V2MessageRouter.publishBundleIfEnabled() says it in its
        // own comment: "Never published from this install (or the prekey store
        // was wiped): the server has no long-lived material of ours, so
        // publish the full set." So the wipe MUST take v2_prekeys with it —
        // otherwise hasPublished() stays true, no fresh bundle is published,
        // and peers go on encrypting to one-time prekeys whose private halves
        // this wipe destroyed. That is undecryptable for ever, and it looks
        // exactly like "I get the notification but never the message".
        assertTrue(
            "v2_prekeys must be wiped or the server keeps advertising dead prekeys",
            com.oshi.messenger.util.PrefsWipePolicy.shouldWipe("v2_prekeys")
        )
        assertTrue(
            "oshi_ratchet must be wiped or stale chain keys outlive the identity",
            com.oshi.messenger.util.PrefsWipePolicy.shouldWipe("oshi_ratchet")
        )
        assertTrue(
            com.oshi.messenger.util.PrefsWipePolicy.shouldWipe("oshi_identity_backup")
        )
    }

    // ------------------------------------------------- the wipe is exhaustive

    private fun settingsViewModelSource(): String {
        val f = listOf(
            File("src/main/java/com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt"),
            File("app/src/main/java/com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt"),
        ).firstOrNull { it.exists() }
        assertTrue("SettingsViewModel.kt not found from ${File(".").absolutePath}", f != null)
        return f!!.readText()
    }

    @Test
    fun `the panic file wipe enumerates filesDir instead of naming directories`() {
        // The first version of wipePanicFiles named `groups` by hand and so
        // left oshi_mesh_custody.json (other people's messages, held for
        // relay), the pending-envelope store, the direct attachment store and
        // the scheduled messages on disk — while still returning the user to
        // the first-run screen. A wipe that looks like a wipe.
        val source = settingsViewModelSource()
        val body = source.substringAfter("private fun wipePanicFiles()")
            .substringBefore("\n    }")
        assertTrue(
            "the panic wipe must enumerate filesDir, not name subdirectories",
            body.contains("context.filesDir") && body.contains("listFiles()")
        )
        assertFalse(
            "naming a media directory by hand is how the first version missed six others",
            body.contains("groupMediaRoot") || body.contains("\"groups\"")
        )
    }

    @Test
    fun `the panic file wipe keeps nothing`() {
        val body = settingsViewModelSource()
            .substringAfter("private fun wipePanicFiles()")
            .substringBefore("\n    }")
        // A keep-list here would have to justify itself per entry, and the one
        // candidate — offline map regions — is a map of where somebody
        // expected to be. If a keep-list is ever added, this test should fail
        // and be rewritten deliberately rather than quietly deleted.
        assertFalse("a keep-list appeared in the panic wipe", body.contains("KEEP"))
        assertFalse("a filter appeared in the panic wipe", body.contains(".filter"))
    }

    @Test
    fun `the cache directory is cleared too, not just app files`() {
        val body = settingsViewModelSource()
            .substringAfter("private fun wipePanicFiles()")
            .substringBefore("\n    }")
        // PlaintextScratch writes decrypted captures and share files into
        // cacheDir; they are the most directly readable bytes on the device.
        assertTrue(body.contains("context.cacheDir"))
    }

    // --------------------------------------------------------------- parity

    @Test
    fun `the iOS mirror declares the same order and the same budget`() {
        // Same two candidates as OfflineOnlyParityTest: the working directory
        // differs between a module-level and a root-level Gradle invocation.
        val swift = listOf(
            File("../OSHI/PanicWipePolicy.swift"),
            File("../../OSHI/PanicWipePolicy.swift"),
        ).firstOrNull { it.exists() }
        assertTrue(
            "OSHI/PanicWipePolicy.swift not found from ${File(".").absolutePath}",
            swift != null
        )
        val text = swift!!.readText()
        for (step in Step.entries) {
            val camel = step.name.split("_")
                .mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.uppercase() } }
                .joinToString("")
            assertTrue("$camel missing from PanicWipePolicy.swift", text.contains(camel))
        }
        assertTrue(
            "the push budget diverged from Android",
            text.contains("pushUnregisterBudget")
        )
        assertTrue(
            "the tap count diverged from Android",
            text.contains("tapsRequired") && text.contains("3")
        )
    }

    @Test
    fun `the iOS wipe deletes keychain items by class, not by name`() {
        val swift = listOf(
            File("../OSHI/PanicWipe.swift"),
            File("../../OSHI/PanicWipe.swift"),
        ).firstOrNull { it.exists() }
        assertTrue("OSHI/PanicWipe.swift not found", swift != null)
        val text = swift!!.readText()
        // Same reason Android enumerates shared_prefs: a hand-written list of
        // keychain accounts rots, and the two entries that rotted last time on
        // Android were both private key material (oshi_ratchet, v2_prekeys).
        // Deleting by kSecClass cannot miss one.
        assertTrue("iOS must delete by kSecClass", text.contains("kSecClass"))
        assertTrue(text.contains("kSecClassGenericPassword"))
        assertTrue(text.contains("kSecClassKey"))
        assertFalse(
            "naming individual keychain accounts is the rot this avoids",
            text.contains("KeychainHelper.delete(key:")
        )
    }

    @Test
    fun `the iOS wipe sweeps defaults after a main-queue hop`() {
        val swift = listOf(
            File("../OSHI/PanicWipe.swift"),
            File("../../OSHI/PanicWipe.swift"),
        ).firstOrNull { it.exists() }!!
        val text = swift.readText()
        // disconnectWallet() ARCHIVES the identity into per-identity
        // UserDefaults keys, and posts some of those writes with
        // DispatchQueue.main.async. Sweeping synchronously loses that race and
        // the archives land after the wipe meant to remove them.
        val afterDisconnect = text.substringAfter("identity.disconnectWallet()")
        assertTrue(
            "the defaults sweep must be ordered after disconnectWallet's own main-queue writes",
            afterDisconnect.contains("DispatchQueue.main.async")
        )
        assertTrue(
            afterDisconnect.indexOf("DispatchQueue.main.async") <
                afterDisconnect.indexOf("wipePreferences()")
        )
    }
}
