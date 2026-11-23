package com.oshi.messenger

import com.oshi.messenger.service.BlockedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __BLOCKED_NOTIF_2026_09_23__ a block that survives the contact row. */
class BlockedKeysTest {
    private val key = "eAbMQUdW6UtufTtaK+q/9Zx1abcDEF0123456789ABCdef="

    @Test fun matchesAcrossSpellings() {
        val list = BlockedKeys.add(emptyList(), key, "Bob", 1L)
        val urlSafe = key.replace('+', '-').replace('/', '_')
        listOf(key, urlSafe, key.dropLast(1), " $key\n").forEach { assertTrue(it, BlockedKeys.contains(list, it)) }
        assertFalse(BlockedKeys.contains(list, "someoneElse"))
        assertFalse(BlockedKeys.contains(list, ""))
    }

    @Test fun blockingTwiceKeepsOneEntryAndFirstDate() {
        var list = BlockedKeys.add(emptyList(), key, null, 1L)
        list = BlockedKeys.add(list, key.replace('+', '-'), "Bob", 99L)
        assertEquals(1, list.size)
        assertEquals(1L, list[0].blockedAt)
        assertEquals("Bob", list[0].name)   // name learned later
    }

    @Test fun unblockRemovesEverySpelling() {
        val list = BlockedKeys.add(emptyList(), key, "Bob", 1L)
        assertTrue(BlockedKeys.remove(list, key.dropLast(1)).isEmpty())
    }

    /** The reported bug: the contact row is gone, the person must still be listed. */
    @Test fun screenListsBlockedKeysWithoutContactRow() {
        val list = BlockedKeys.add(emptyList(), key, "Bob", 5L)
        val merged = BlockedKeys.merge(list, blockedRows = emptyList())
        assertEquals(listOf(key), merged.map { it.publicKey })
        assertEquals("Bob", merged[0].name)
    }

    @Test fun legacyRowsAreListedAndNamedFromContact() {
        val list = BlockedKeys.add(emptyList(), key, null, 5L)
        val merged = BlockedKeys.merge(list, listOf(key to "Bobby", "legacyKey" to "Old"))
        assertEquals(2, merged.size)
        assertEquals("Bobby", merged.first { BlockedKeys.normalize(it.publicKey) == BlockedKeys.normalize(key) }.name)
    }

    @Test fun roundTripsAndSurvivesCorruption() {
        val list = BlockedKeys.add(BlockedKeys.add(emptyList(), key, "Bob", 1L), "k2", null, 2L)
        assertEquals(list, BlockedKeys.decode(BlockedKeys.encode(list)))
        assertTrue(BlockedKeys.decode("not json").isEmpty())
        assertTrue(BlockedKeys.decode(null).isEmpty())
    }
}
