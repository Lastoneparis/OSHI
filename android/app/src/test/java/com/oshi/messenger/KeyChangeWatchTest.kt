package com.oshi.messenger

import com.oshi.messenger.service.KeyChangeWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyChangeWatch] — the thing that makes a silent key substitution impossible on Android.
 *
 * The case that matters is `a key that changes between two messages is reported`. Everything
 * else exists to stop that one being satisfied by a function that simply shouts on every
 * call, which would train users to dismiss the warning and leave the app worse off than with
 * no warning at all.
 *
 * These mirror `LinkedDeviceWatchTest` case for case, deliberately: the two watches answer
 * the same shape of attack — a correct, working, fully-encrypted session with the wrong party
 * on the other end — and one of them behaving differently from the other is two things to
 * reason about, and the second will rot.
 *
 * Plain JVM test, no Robolectric: the logic is pure, `SharedPreferences` is touched only by
 * the persistence helpers, and a fake covers those without pulling in a
 * graphics-mode-dependent runtime.
 */
class KeyChangeWatchTest {

    private val me = "MY_KEY"
    private val fpA = "111112222233333444445555566666777778888899999000001111122222"
    private val fpB = "999998888877777666665555544444333332222211111000009999988888"

    // ---------------------------------------------------------------- the property

    @Test
    fun `a key that changes between two messages is reported`() {
        assertEquals(
            KeyChangeWatch.Outcome.Changed(previous = fpA, current = fpB),
            KeyChangeWatch.classify(me, currentFingerprint = fpB, knownFingerprint = fpA),
        )
    }

    @Test
    fun `the outcome carries both fingerprints so the alert can show what changed`() {
        val outcome = KeyChangeWatch.classify(me, fpB, fpA)
        assertTrue(outcome is KeyChangeWatch.Outcome.Changed)
        outcome as KeyChangeWatch.Outcome.Changed
        assertEquals(fpA, outcome.previous)
        assertEquals(fpB, outcome.current)
    }

    // ------------------------------------------------- the noise guards, which make it believable

    @Test
    fun `nothing is reported when the key is the same`() {
        assertEquals(
            KeyChangeWatch.Outcome.Unchanged,
            KeyChangeWatch.classify(me, fpA, fpA),
        )
    }

    @Test
    fun `the first sight of a contact seeds and does not alarm`() {
        // null means "we have never seen this contact". Treating a first message as a key
        // change would fire a security warning at every new conversation, and at every
        // existing contact on upgrade day.
        assertEquals(
            KeyChangeWatch.Outcome.FirstSight,
            KeyChangeWatch.classify(me, fpA, knownFingerprint = null),
        )
    }

    @Test
    fun `null and a stored fingerprint are not the same thing`() {
        // The entire point. Conflating them either silences every real change (null read as
        // "seen, and equal") or alarms on every new contact (a value read as "never seen").
        assertEquals(
            KeyChangeWatch.Outcome.FirstSight,
            KeyChangeWatch.classify(me, fpA, null),
        )
        assertEquals(
            KeyChangeWatch.Outcome.Changed(fpA, fpB),
            KeyChangeWatch.classify(me, fpB, fpA),
        )
    }

    @Test
    fun `a key that changes back to the original is reported again`() {
        // Rotate away and back: still two changes, still two alarms. A watch that only
        // remembers "ever differed" would miss the return trip.
        assertEquals(
            KeyChangeWatch.Outcome.Changed(fpB, fpA),
            KeyChangeWatch.classify(me, fpA, fpB),
        )
    }

    @Test
    fun `a blank address or fingerprint is not a key change`() {
        // A transport can hand up an empty sender; that is an artefact, not a contact, and an
        // alert about nobody is worse than silence.
        assertEquals(KeyChangeWatch.Outcome.FirstSight, KeyChangeWatch.classify("", fpB, fpA))
        assertEquals(KeyChangeWatch.Outcome.FirstSight, KeyChangeWatch.classify(me, "", fpA))
    }

    // ---------------------------------------------------------------- persistence

    /** Enough SharedPreferences to exercise contains/getString/getAll/edit, and nothing more. */
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
    fun `the baseline distinguishes never-seen from seen`() {
        val p = FakePrefs()
        assertNull("before anything is stored, we have never seen this contact",
            KeyChangeWatch.knownFingerprint(p, "alice"))

        KeyChangeWatch.remember(p, "alice", fpA)
        assertEquals(fpA, KeyChangeWatch.knownFingerprint(p, "alice"))

        // And a DIFFERENT contact is still never-seen: one baseline must not answer for all.
        assertNull(KeyChangeWatch.knownFingerprint(p, "bob"))
    }

    @Test
    fun `reset makes the next check seed instead of alarming`() {
        val p = FakePrefs()
        KeyChangeWatch.remember(p, "alice", fpA)
        KeyChangeWatch.reset(p, "alice")
        assertNull(KeyChangeWatch.knownFingerprint(p, "alice"))
        assertEquals(
            KeyChangeWatch.Outcome.FirstSight,
            KeyChangeWatch.classify("alice", fpB, KeyChangeWatch.knownFingerprint(p, "alice")),
        )
    }

    @Test
    fun `resetAll clears every observation and keeps every verification`() {
        val p = FakePrefs()
        KeyChangeWatch.remember(p, "alice", fpA)
        KeyChangeWatch.remember(p, "bob", fpA)
        p.edit().putString(KeyChangeWatch.verifiedKey("alice"), fpA).apply()

        KeyChangeWatch.resetAll(p)

        assertNull(KeyChangeWatch.knownFingerprint(p, "bob"))
        assertNotNull(
            "a verification is the user's own record of meeting someone; this watch does not delete it",
            p.getString(KeyChangeWatch.verifiedKey("alice"), null),
        )
    }

    // -------------------------------------- the one source of truth, and the badge it must not forge

    @Test
    fun `the watch never writes the verified key, so it cannot forge a verification badge`() {
        // `SafetyNumberManager.isContactVerified` is `prefs.contains("verified_<key>")`.
        // Seeding a baseline under that key would make every contact who ever sent a message
        // read back as verified.
        val p = FakePrefs()
        KeyChangeWatch.remember(p, "alice", fpA)
        assertTrue("first sight must not look like a verification",
            !p.contains(KeyChangeWatch.verifiedKey("alice")))
    }

    @Test
    fun `a verified fingerprint is the baseline when nothing has been observed yet`() {
        // The loudest alarm in the app: verified in person, key changed, and this watch had
        // never run for them. Swallowing it as a first sight would waste the verification.
        val p = FakePrefs()
        p.edit().putString(KeyChangeWatch.verifiedKey("alice"), fpA).apply()
        assertEquals(fpA, KeyChangeWatch.knownFingerprint(p, "alice"))
        assertEquals(
            KeyChangeWatch.Outcome.Changed(fpA, fpB),
            KeyChangeWatch.classify("alice", fpB, KeyChangeWatch.knownFingerprint(p, "alice")),
        )
    }

    @Test
    fun `an observation outranks the verified record once there is one`() {
        // Otherwise, after a change is reported and acknowledged, the verified value would
        // keep answering and the alarm would fire on every message forever — which is how an
        // alarm becomes wallpaper.
        val p = FakePrefs()
        p.edit().putString(KeyChangeWatch.verifiedKey("alice"), fpA).apply()
        KeyChangeWatch.remember(p, "alice", fpB)
        assertEquals(fpB, KeyChangeWatch.knownFingerprint(p, "alice"))
        assertEquals(
            KeyChangeWatch.Outcome.Unchanged,
            KeyChangeWatch.classify("alice", fpB, KeyChangeWatch.knownFingerprint(p, "alice")),
        )
    }

    // ---------------------------------------------------------------- end to end

    @Test
    fun `a silent key substitution is impossible across two messages`() {
        val p = FakePrefs()

        // First message ever from this contact: seed, say nothing.
        var observed = fpA
        assertEquals(
            KeyChangeWatch.Outcome.FirstSight,
            KeyChangeWatch.classify("alice", observed, KeyChangeWatch.knownFingerprint(p, "alice")),
        )
        KeyChangeWatch.remember(p, "alice", observed)

        // Second message, same key: still nothing. This is the case that has to stay quiet.
        assertEquals(
            KeyChangeWatch.Outcome.Unchanged,
            KeyChangeWatch.classify("alice", observed, KeyChangeWatch.knownFingerprint(p, "alice")),
        )

        // Between the two, the server hands out a key somebody else holds.
        observed = fpB
        val outcome =
            KeyChangeWatch.classify("alice", observed, KeyChangeWatch.knownFingerprint(p, "alice"))
        assertEquals(KeyChangeWatch.Outcome.Changed(fpA, fpB), outcome)

        // The caller adopts it, so the alarm fires ONCE rather than on every message.
        KeyChangeWatch.remember(p, "alice", observed)
        assertEquals(
            KeyChangeWatch.Outcome.Unchanged,
            KeyChangeWatch.classify("alice", observed, KeyChangeWatch.knownFingerprint(p, "alice")),
        )
    }
}
