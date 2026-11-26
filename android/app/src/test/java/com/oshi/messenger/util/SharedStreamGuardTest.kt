package com.oshi.messenger.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** __SHARE_TARGET_OWN_FILES_2026_11_20__ */
class SharedStreamGuardTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dataDir: File
    private lateinit var shared: File
    private val own = listOf("com.oshi.messenger.fileprovider")

    @Before fun setUp() {
        dataDir = tmp.newFolder("data", "user", "0", "com.oshi.messenger")
        File(dataDir, "shared_prefs").mkdirs()
        File(dataDir, "shared_prefs/oshi_identity_backup.xml").writeText("secret")
        shared = tmp.newFolder("sdcard", "Download")
        File(shared, "holiday.jpg").writeText("jpeg")
    }

    private fun file(path: String) = SharedStreamGuard.isAllowed("file", null, path, own, listOf(dataDir))

    @Test fun refusesFileInsideOwnSandbox() {
        assertFalse(file(File(dataDir, "shared_prefs/oshi_identity_backup.xml").path))
        assertFalse(file(dataDir.path))
    }

    @Test fun refusesTraversalBackIntoSandbox() {
        val sneaky = File(shared, "../../data/user/0/com.oshi.messenger/shared_prefs/oshi_identity_backup.xml").path
        assertFalse(file(sneaky))
    }

    @Test fun refusesSymlinkIntoSandbox() {
        val link = File(shared, "innocent.jpg").toPath()
        java.nio.file.Files.createSymbolicLink(link, File(dataDir, "shared_prefs/oshi_identity_backup.xml").toPath())
        assertFalse(file(link.toString()))
    }

    @Test fun refusesProcViews() {
        assertFalse(file("/proc/self/fd/42"))
        assertFalse(file("/proc/self/root/data/data/com.oshi.messenger/databases/oshi_database"))
    }

    @Test fun refusesOwnFileProviderAnyCase() {
        assertFalse(SharedStreamGuard.isAllowed("content", "com.oshi.messenger.fileprovider", "/files_root/x", own, listOf(dataDir)))
        assertFalse(SharedStreamGuard.isAllowed("CONTENT", "COM.OSHI.MESSENGER.FILEPROVIDER", "/x", own, listOf(dataDir)))
    }

    @Test fun allowsOrdinaryShares() {
        assertTrue(file(File(shared, "holiday.jpg").path))
        assertTrue(SharedStreamGuard.isAllowed("content", "com.android.providers.media.documents", "/document/image:12", own, listOf(dataDir)))
        // A sibling whose name merely starts with ours is not inside our sandbox.
        val sibling = tmp.newFolder("data", "user", "0", "com.oshi.messenger.evil")
        File(sibling, "a.txt").writeText("x")
        assertTrue(file(File(sibling, "a.txt").path))
    }

    @Test fun refusesNonStreamSchemes() {
        assertFalse(SharedStreamGuard.isAllowed("https", "example.com", "/a", own, listOf(dataDir)))
        assertFalse(SharedStreamGuard.isAllowed(null, null, null, own, listOf(dataDir)))
    }
}
