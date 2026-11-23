package com.oshi.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __SETTINGS_FREEZE_2026_11_09__ "Opening Settings sometimes freezes the app
 * for a second." SettingsScreen took `mainViewModel = hiltViewModel()` by
 * default; inside a NavHost destination that is scoped to the back-stack entry,
 * so every open constructed a SECOND MainViewModel whose init, on the main
 * thread, built EncryptedSharedPreferences + a StrongBox-requested MasterKey,
 * restored the keys and re-ran initializeServices() (VPS/mesh/call init,
 * cross-platform mesh reconfigure, FCM registration). The fix passes the
 * activity's instance at the call site. Source scan, because the failure mode
 * is "someone drops the argument" and nothing else would notice.
 *
 * Note: Gradle does not re-run a test when a SOURCE file it reads changes;
 * use --rerun-tasks after edits.
 */
class SettingsOpenDoesNotRebuildMainViewModelTest {

    private val root = File("src/main/java/com/oshi/messenger")
    private val main by lazy { File(root, "ui/MainActivity.kt").readText() }

    private fun callBlock(name: String): String {
        val start = main.indexOf("$name(\n")
        assertTrue("$name call site not found in MainActivity", start >= 0)
        return main.substring(start, minOf(main.length, start + 2500))
    }

    @Test
    fun `Settings gets the activity-scoped MainViewModel`() {
        assertTrue(callBlock("SettingsScreen").contains("mainViewModel = viewModel,"))
    }

    @Test
    fun `QR and key-backup screens get the activity-scoped MainViewModel`() {
        val qr = callBlock("QRCodeScreen").substringBefore("\n                            }")
        val kb = callBlock("KeyBackupScreen").substringBefore("\n                            }")
        assertTrue(qr.contains("viewModel = viewModel"))
        assertTrue(kb.contains("viewModel = viewModel"))
    }

    @Test
    fun `SettingsViewModel does no blocking or dead full-table collection`() {
        val vm = File(root, "ui/viewmodel/SettingsViewModel.kt").readText()
        assertFalse(vm.contains("runBlocking"))
        assertFalse(vm.contains("getUnreadMessages().collect"))
    }
}
