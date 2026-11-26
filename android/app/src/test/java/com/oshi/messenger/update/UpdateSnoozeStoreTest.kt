package com.oshi.messenger.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __UPDATE_PROMPT_ANDROID_2026_11_21__ "Later" persistence and the flexible-started mark. */
class UpdateSnoozeStoreTest {

    private class MapStorage : UpdateSnoozeStore.Storage {
        val map = HashMap<String, Any>()
        override fun getInt(key: String) = map[key] as Int?
        override fun getLong(key: String) = map[key] as Long?
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun remove(vararg keys: String) { keys.forEach { map.remove(it) } }
    }

    private val now = 1_800_000_000_000L
    private val storage = MapStorage()
    private val store = UpdateSnoozeStore(storage)

    @Test fun `fresh store snoozes nothing`() {
        assertNull(store.snoozedVersionCode)
        assertNull(store.snoozedUntilMs)
        assertFalse(store.isSnoozed(150, now))
    }

    @Test fun `later snoozes that version for exactly 3 days`() {
        store.snooze(150, now)
        assertEquals(150, store.snoozedVersionCode)
        assertEquals(now + FlexibleUpdatePolicy.SNOOZE_MS, store.snoozedUntilMs)
        assertTrue(store.isSnoozed(150, now))
        assertTrue(store.isSnoozed(150, now + FlexibleUpdatePolicy.SNOOZE_MS - 1))
        assertFalse(store.isSnoozed(150, now + FlexibleUpdatePolicy.SNOOZE_MS))
    }

    @Test fun `snooze is per version`() {
        store.snooze(150, now)
        assertFalse(store.isSnoozed(151, now))
    }

    @Test fun `a second later replaces the first`() {
        store.snooze(150, now)
        store.snooze(151, now + 1000)
        assertFalse(store.isSnoozed(150, now + 2000))
        assertTrue(store.isSnoozed(151, now + 2000))
    }

    @Test fun `survives a new store instance on the same storage`() {
        store.snooze(150, now)
        assertTrue(UpdateSnoozeStore(storage).isSnoozed(150, now + 1))
    }

    @Test fun `clearSnooze`() {
        store.snooze(150, now)
        store.clearSnooze()
        assertFalse(store.isSnoozed(150, now))
        assertTrue(storage.map.isEmpty())
    }

    @Test fun `flexible started mark`() {
        assertNull(store.flexibleStartedVersionCode)
        store.flexibleStartedVersionCode = 150
        assertEquals(150, store.flexibleStartedVersionCode)
        store.flexibleStartedVersionCode = null
        assertNull(store.flexibleStartedVersionCode)
    }
}
