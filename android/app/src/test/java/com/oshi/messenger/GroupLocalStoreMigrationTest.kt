package com.oshi.messenger

import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.SealedFile
import com.oshi.messenger.data.local.atrest.SealedStringStore
import com.oshi.messenger.service.GroupLocalStore
import com.oshi.messenger.service.GroupLocalStore.Doc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __LEAK_FIX_2026_09_25__ Finding A: `groups.xml` (messages + group keys in cleartext) →
 * sealed files. The migration must lose NOTHING and leave no plaintext copy behind.
 */
class GroupLocalStoreMigrationTest {

    /** An in-memory stand-in for one SharedPreferences entry (the legacy XML). */
    private class FakeLegacy(var value: String?, var failClear: Boolean = false) : SealedStringStore.Legacy {
        var clears = 0
        override fun read(): String? = value
        override fun clear(): Boolean {
            clears++
            if (failClear) return false
            value = null
            return true
        }
    }

    private lateinit var dir: File
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val keyed = AtRestKeyProvider { key }

    private val groupKeyB64 = "q83vEjRWeJCrze8SNFZ4kKvN7xI0VniQq83vEjRWeJA="
    private val groupsJson = JSONArray().put(
        JSONObject()
            .put("id", "3F2504E0-4F89-11D3-9A0C-0305E82C3301")
            .put("name", "Secret plans")
            .put("description", "")
            .put("creator", "creatorKeyAAAA")
            .put("members", JSONArray(listOf("creatorKeyAAAA", "memberKeyBBBB")))
            .put("admins", JSONArray(listOf("creatorKeyAAAA")))
            .put("encryptionKey", groupKeyB64)
            .put("isMuted", true)
    ).toString()
    private val messagesJson = JSONObject().put(
        "3F2504E0-4F89-11D3-9A0C-0305E82C3301",
        JSONArray().put(
            JSONObject().put("id", "M1").put("groupId", "3F2504E0-4F89-11D3-9A0C-0305E82C3301")
                .put("senderPublicKey", "memberKeyBBBB").put("content", "meet at the dock at 9")
                .put("timestamp", 1_790_000_000_123L)
        )
    ).toString()
    private val leftJson = JSONArray(listOf("AAAA-LEFT")).toString()
    private val devsyncJson = GroupLocalStore.entriesToJson(
        mapOf("AAAA-LEFT" to JSONObject().put("leftAt", 1L).put("name", "Old group").put("key", groupKeyB64).toString())
    )
    private val mentionsJson = """{"M1":[{"k":"memberKeyBBBB","n":"Bob"}]}"""

    private fun legacies() = mapOf(
        Doc.GROUPS to FakeLegacy(groupsJson),
        Doc.MESSAGES to FakeLegacy(messagesJson),
        Doc.LEFT_IDS to FakeLegacy(leftJson),
        Doc.DEVSYNC_LEFT to FakeLegacy(devsyncJson),
        Doc.MENTIONS to FakeLegacy(mentionsJson),
    )

    private fun expected(doc: Doc) = when (doc) {
        Doc.GROUPS -> groupsJson
        Doc.MESSAGES -> messagesJson
        Doc.LEFT_IDS -> leftJson
        Doc.DEVSYNC_LEFT -> devsyncJson
        Doc.MENTIONS -> mentionsJson
    }

    @Before fun setUp() { dir = Files.createTempDirectory("grpstore").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun firstReadMigratesEveryDocumentSealsItAndWipesTheLegacyCopy() {
        val legacy = legacies()
        val store = GroupLocalStore(dir, keyed) { legacy[it] }
        for (doc in Doc.values()) {
            assertEquals("${doc.name} read back", expected(doc), store.read(doc))
            assertNull("${doc.name} legacy wiped", legacy.getValue(doc).value)
            val bytes = store.file(doc).readBytes()
            assertTrue("${doc.name} sealed", SealedFile.isSealed(bytes))
            val asText = String(bytes, Charsets.ISO_8859_1)
            for (secret in listOf("Secret plans", "meet at the dock", groupKeyB64, "memberKeyBBBB", "Old group", "AAAA-LEFT")) {
                assertFalse("${doc.name} leaks '$secret'", asText.contains(secret))
            }
        }
        // A fresh instance (next launch) reads the sealed files, with no legacy left at all.
        val next = GroupLocalStore(dir, keyed) { null }
        for (doc in Doc.values()) assertEquals(expected(doc), next.read(doc))
    }

    @Test
    fun withoutAKeyNothingIsWipedAndTheValueIsStillServed() {
        val legacy = legacies()
        val store = GroupLocalStore(dir, AtRestKeyProvider.NONE) { legacy[it] }
        for (doc in Doc.values()) {
            assertEquals(expected(doc), store.read(doc))
            assertEquals("${doc.name} legacy kept", expected(doc), legacy.getValue(doc).value)
            assertFalse(store.file(doc).exists())
        }
        // Key back on the next launch: migrated then.
        val later = GroupLocalStore(dir, keyed) { legacy[it] }
        for (doc in Doc.values()) {
            assertEquals(expected(doc), later.read(doc))
            assertNull(legacy.getValue(doc).value)
            assertTrue(SealedFile.isSealed(later.file(doc).readBytes()))
        }
    }

    @Test
    fun aLegacyClearThatDoesNotStickIsRetriedAndTheDataSurvives() {
        val legacy = FakeLegacy(groupsJson, failClear = true)
        val store = GroupLocalStore(dir, keyed) { if (it == Doc.GROUPS) legacy else null }
        assertEquals(groupsJson, store.read(Doc.GROUPS))
        assertEquals(groupsJson, legacy.value)            // still there
        legacy.failClear = false
        assertEquals(groupsJson, store.read(Doc.GROUPS))  // sealed file wins, residue removed
        assertNull(legacy.value)
    }

    @Test
    fun writesAreSealedAndReplaceTheLegacyEntry() {
        val legacy = FakeLegacy(groupsJson)
        val store = GroupLocalStore(dir, keyed) { if (it == Doc.GROUPS) legacy else null }
        val updated = JSONArray(groupsJson).also { it.getJSONObject(0).put("name", "Renamed") }.toString()
        store.write(Doc.GROUPS, updated)
        assertNull(legacy.value)
        assertEquals(updated, store.read(Doc.GROUPS))
        assertFalse(String(store.file(Doc.GROUPS).readBytes(), Charsets.ISO_8859_1).contains("Renamed"))
    }

    @Test
    fun aSealedFileWithoutItsKeyIsUnreadableNotEmptyAndIsNeverOverwritten() {
        GroupLocalStore(dir, keyed) { null }.write(Doc.MESSAGES, messagesJson)
        val locked = GroupLocalStore(dir, AtRestKeyProvider.NONE) { null }
        assertNull(locked.read(Doc.MESSAGES))
        assertTrue(locked.isUnreadable(Doc.MESSAGES))
        assertFalse(locked.isUnreadable(Doc.GROUPS)) // never written ⇒ just empty
        val refused = runCatching { locked.write(Doc.MESSAGES, "{}") }.isFailure
        assertTrue("an empty state must not replace the sealed history", refused)
        assertEquals(messagesJson, GroupLocalStore(dir, keyed) { null }.read(Doc.MESSAGES))
    }

    @Test
    fun devSyncLeftPrefsMapBecomesOneJsonObject() {
        val json = GroupLocalStore.entriesToJson(mapOf("G1" to "{\"leftAt\":5}", "junk" to 3))
        val o = JSONObject(json)
        assertEquals(setOf("G1"), o.keys().asSequence().toSet())
        assertEquals("{\"leftAt\":5}", o.getString("G1"))
    }
}
