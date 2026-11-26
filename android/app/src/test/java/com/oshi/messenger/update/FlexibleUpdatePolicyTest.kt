package com.oshi.messenger.update

import com.oshi.messenger.update.FlexibleUpdatePolicy.Install
import com.oshi.messenger.update.FlexibleUpdatePolicy.PlayFacts
import com.oshi.messenger.update.FlexibleUpdatePolicy.Prompt
import com.oshi.messenger.update.FlexibleUpdatePolicy.Surface
import com.oshi.messenger.update.ForcedUpdatePolicy.PlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __UPDATE_PROMPT_ANDROID_2026_11_21__ The non-forced "Update available" decision. */
class FlexibleUpdatePolicyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val available = PlayFacts(flexibleAvailable = true, availableVersionCode = 150, install = Install.IDLE, forced = false)
    private val free = Surface(onboarded = true, inCall = false, locked = false)

    private fun decide(
        play: PlayFacts? = available,
        surface: Surface = free,
        asked: Boolean = false,
        snoozedVc: Int? = null,
        snoozedUntil: Long? = null,
    ) = FlexibleUpdatePolicy.decide(play, surface, asked, snoozedVc, snoozedUntil, now)

    @Test fun `available on a free screen asks`() = assertEquals(Prompt.ASK, decide())

    @Test fun `Play silent or unreachable shows nothing`() = assertEquals(Prompt.NONE, decide(play = null))

    @Test fun `nothing on the store shows nothing`() =
        assertEquals(Prompt.NONE, decide(play = available.copy(flexibleAvailable = false)))

    @Test fun `forced wall owns the screen`() {
        assertEquals(Prompt.NONE, decide(play = available.copy(forced = true)))
        assertEquals(Prompt.NONE, decide(play = available.copy(forced = true, install = Install.DOWNLOADED)))
    }

    @Test fun `never during onboarding a call or the lock cover`() {
        assertEquals(Prompt.NONE, decide(surface = free.copy(onboarded = false)))
        assertEquals(Prompt.NONE, decide(surface = free.copy(inCall = true)))
        assertEquals(Prompt.NONE, decide(surface = free.copy(locked = true)))
    }

    @Test fun `restart bar not during a call — restarting would end it`() =
        assertEquals(Prompt.NONE, decide(play = available.copy(install = Install.DOWNLOADED), surface = free.copy(inCall = true)))

    @Test fun `once per app start`() = assertEquals(Prompt.NONE, decide(asked = true))

    @Test fun `download in progress shows nothing`() =
        assertEquals(Prompt.NONE, decide(play = available.copy(install = Install.IN_PROGRESS)))

    @Test fun `downloaded offers restart even when snoozed or already asked`() {
        val downloaded = available.copy(install = Install.DOWNLOADED, flexibleAvailable = false)
        assertEquals(Prompt.RESTART, decide(play = downloaded))
        assertEquals(Prompt.RESTART, decide(play = downloaded, asked = true, snoozedVc = 150, snoozedUntil = now + day))
    }

    @Test fun `snoozed version is not asked for 3 days`() {
        assertEquals(Prompt.NONE, decide(snoozedVc = 150, snoozedUntil = now + 1))
        assertEquals(Prompt.NONE, decide(snoozedVc = 150, snoozedUntil = now + FlexibleUpdatePolicy.SNOOZE_MS))
    }

    @Test fun `snooze over asks again`() {
        assertEquals(Prompt.ASK, decide(snoozedVc = 150, snoozedUntil = now))
        assertEquals(Prompt.ASK, decide(snoozedVc = 150, snoozedUntil = now - day))
    }

    @Test fun `a newer store version beats the snooze`() =
        assertEquals(Prompt.ASK, decide(snoozedVc = 149, snoozedUntil = now + day))

    @Test fun `clock set back does not make an endless snooze`() =
        assertFalse(FlexibleUpdatePolicy.isSnoozed(150, 150, now + FlexibleUpdatePolicy.SNOOZE_MS + 1, now))

    @Test fun `forcedPlayState unchanged when no flexible download is ours`() {
        assertEquals(PlayState.IN_PROGRESS, FlexibleUpdatePolicy.forcedPlayState(true, false, false, false))
        assertEquals(PlayState.IN_PROGRESS, FlexibleUpdatePolicy.forcedPlayState(true, false, true, false))
        assertEquals(PlayState.AVAILABLE, FlexibleUpdatePolicy.forcedPlayState(false, true, true, false))
        assertEquals(PlayState.NOT_AVAILABLE, FlexibleUpdatePolicy.forcedPlayState(false, true, false, false))
        assertEquals(PlayState.NOT_AVAILABLE, FlexibleUpdatePolicy.forcedPlayState(false, false, true, false))
    }

    @Test fun `our flexible download is not resumed as an immediate wall`() {
        assertEquals(PlayState.AVAILABLE, FlexibleUpdatePolicy.forcedPlayState(true, false, true, true))
        assertEquals(PlayState.NOT_AVAILABLE, FlexibleUpdatePolicy.forcedPlayState(true, false, false, true))
        // …so above the blocking floor it no longer blocks…
        assertEquals(
            ForcedUpdatePolicy.Decision.NONE,
            ForcedUpdatePolicy.decide("1.7.2", null, null, FlexibleUpdatePolicy.forcedPlayState(true, false, true, true))
        )
        // …but below it, it still does.
        assertEquals(
            ForcedUpdatePolicy.Decision.BLOCK,
            ForcedUpdatePolicy.decide("1.7.2", null, "1.7.3", FlexibleUpdatePolicy.forcedPlayState(true, false, true, true))
        )
    }

    @Test fun `surface busy flag`() {
        assertFalse(free.busy)
        assertTrue(free.copy(onboarded = false).busy)
    }
}
