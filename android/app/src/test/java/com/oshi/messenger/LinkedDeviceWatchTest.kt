package com.oshi.messenger

import com.oshi.messenger.network.web.LinkedDeviceWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LinkedDeviceWatch] — the thing that makes a silent device link impossible on Android.
 *
 * The case that matters is `a device linked between two checks is reported`. Everything
 * else exists to stop that one being satisfied by a function that simply shouts on every
 * call, which would train users to dismiss the warning and leave the app worse off than
 * with no warning at all.
 *
 * These mirror `OSHITests/LinkedDeviceWatchTests.swift` case for case, deliberately: a
 * two-platform defence that behaves differently on each platform is two defences to reason
 * about and one of them will rot.
 *
 * The pure logic is tested here as a plain JVM test with no Robolectric — `SharedPreferences`
 * is only touched by the persistence helpers, and a fake covers those without pulling in a
 * graphics-mode-dependent runtime.
 */
class LinkedDeviceWatchTest {

    // ---------------------------------------------------------------- the property

    @Test
    fun `a device linked between two checks is reported`() {
        assertEquals(
            listOf("unknown-laptop"),
            LinkedDeviceWatch.newlyLinked(listOf("phone", "unknown-laptop"), setOf("phone")),
        )
        assertEquals(
            LinkedDeviceWatch.Outcome.Linked(listOf("unknown-laptop")),
            LinkedDeviceWatch.classify(listOf("phone", "unknown-laptop"), setOf("phone")),
        )
    }

    @Test
    fun `several linked at once are all reported`() {
        assertEquals(
            listOf("b", "c"),
            LinkedDeviceWatch.newlyLinked(listOf("a", "b", "c"), setOf("a")),
        )
    }

    // ------------------------------------------------- the noise guards, which make it believable

    @Test
    fun `nothing is reported when nothing changed`() {
        assertEquals(
            LinkedDeviceWatch.Outcome.Unchanged,
            LinkedDeviceWatch.classify(listOf("a", "b"), setOf("a", "b")),
        )
    }

    @Test
    fun `the first check ever seeds and does not alarm`() {
        // null means "we have never looked". Treating the sessions a user already had as
        // newly linked would fire a security warning at everyone on upgrade day.
        assertEquals(emptyList<String>(), LinkedDeviceWatch.newlyLinked(listOf("a", "b"), null))
        assertEquals(
            LinkedDeviceWatch.Outcome.Seeded(2),
            LinkedDeviceWatch.classify(listOf("a", "b"), null),
        )
    }

    @Test
    fun `null and empty are not the same thing`() {
        // Empty means "last time there were none", so a device appearing IS news.
        // Conflating the two turns the first-run compromise into a permanent blind spot.
        assertEquals(listOf("a"), LinkedDeviceWatch.newlyLinked(listOf("a"), emptySet()))
        assertEquals(emptyList<String>(), LinkedDeviceWatch.newlyLinked(listOf("a"), null))
    }

    @Test
    fun `a revoked device that returns is reported again`() {
        val known = setOf("a", "laptop") - "laptop"   // the user revoked it
        assertEquals(listOf("laptop"), LinkedDeviceWatch.newlyLinked(listOf("a", "laptop"), known))
    }

    @Test
    fun `a disappearing device is not reported as new`() {
        assertEquals(
            emptyList<String>(),
            LinkedDeviceWatch.newlyLinked(listOf("a"), setOf("a", "b")),
        )
    }

    // ---------------------------------------------------------------- persistence

    /** Enough SharedPreferences to exercise contains/getStringSet/edit, and nothing more. */
    private class FakePrefs : android.content.SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun contains(key: String) = map.containsKey(key)
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun edit() = object : android.content.SharedPreferences.Editor {
            val staged = HashMap<String, Any?>(map)
            val removed = HashSet<String>()
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { staged[key] = values?.toSet() }
            override fun remove(key: String) = apply { removed.add(key); staged.remove(key) }
            override fun apply() { map.clear(); map.putAll(staged) }
            override fun commit(): Boolean { apply(); return true }
            override fun clear() = apply { staged.clear() }
            override fun putString(k: String, v: String?) = apply { staged[k] = v }
            override fun putInt(k: String, v: Int) = apply { staged[k] = v }
            override fun putLong(k: String, v: Long) = apply { staged[k] = v }
            override fun putFloat(k: String, v: Float) = apply { staged[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { staged[k] = v }
        }
        override fun getAll() = map as MutableMap<String, *>
        override fun getString(k: String, d: String?) = map[k] as? String ?: d
        override fun getInt(k: String, d: Int) = map[k] as? Int ?: d
        override fun getLong(k: String, d: Long) = map[k] as? Long ?: d
        override fun getFloat(k: String, d: Float) = map[k] as? Float ?: d
        override fun getBoolean(k: String, d: Boolean) = map[k] as? Boolean ?: d
        override fun registerOnSharedPreferenceChangeListener(
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    @Test
    fun `the baseline distinguishes never-looked from looked-and-found-none`() {
        val p = FakePrefs()
        assertNull("before any save, we have never looked", LinkedDeviceWatch.loadKnown(p))

        LinkedDeviceWatch.saveKnown(p, emptyList())
        assertEquals(
            "having looked and found none must not read back as never having looked",
            emptySet<String>(), LinkedDeviceWatch.loadKnown(p),
        )

        LinkedDeviceWatch.saveKnown(p, listOf("b", "a"))
        assertEquals(setOf("a", "b"), LinkedDeviceWatch.loadKnown(p))
    }

    @Test
    fun `reset makes the next check seed instead of alarming`() {
        val p = FakePrefs()
        LinkedDeviceWatch.saveKnown(p, listOf("old-identity-session"))
        LinkedDeviceWatch.reset(p)
        assertNull(LinkedDeviceWatch.loadKnown(p))
        assertEquals(
            emptyList<String>(),
            LinkedDeviceWatch.newlyLinked(listOf("fresh"), LinkedDeviceWatch.loadKnown(p)),
        )
    }

    // ---------------------------------------------------------------- end to end

    @Test
    fun `a silent link is impossible across two checks`() {
        val p = FakePrefs()
        var fetched = listOf("phone")
        assertEquals(
            LinkedDeviceWatch.Outcome.Seeded(1),
            LinkedDeviceWatch.classify(fetched, LinkedDeviceWatch.loadKnown(p)),
        )
        LinkedDeviceWatch.saveKnown(p, fetched)

        // Between the two checks, somebody with the unlocked phone links a computer.
        fetched = listOf("phone", "investigators-laptop")

        assertEquals(
            LinkedDeviceWatch.Outcome.Linked(listOf("investigators-laptop")),
            LinkedDeviceWatch.classify(fetched, LinkedDeviceWatch.loadKnown(p)),
        )
    }

    @Test
    fun `saveKnown stores a copy the caller cannot mutate afterwards`() {
        // getStringSet documents that the returned set must not be modified; storing an
        // instance the caller still holds is the same mistake pointed the other way.
        val p = FakePrefs()
        val ids = mutableListOf("a")
        LinkedDeviceWatch.saveKnown(p, ids)
        ids.add("smuggled-in-later")
        assertTrue("the stored baseline must not follow the caller's list",
            LinkedDeviceWatch.loadKnown(p) == setOf("a"))
    }
}
