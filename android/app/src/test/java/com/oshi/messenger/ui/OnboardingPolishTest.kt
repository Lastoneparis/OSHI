package com.oshi.messenger.ui

import com.oshi.messenger.ui.onboarding.OnboardingFlow
import com.oshi.messenger.ui.onboarding.OnboardingPage
import com.oshi.messenger.ui.onboarding.avatarInitial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __ONBOARDING_POLISH_2026_11_24__ The design pass on top of the 2027 onboarding:
 * a step meter on the setup steps, a notification preview, a live contact-card
 * preview of the chosen name, Skip as a pill — and the flow itself untouched.
 */
class OnboardingPolishTest {

    private fun src(path: String) = File("src/main/java/com/oshi/messenger/$path").readText()

    @Test fun `identity preview initial is the first grapheme, upper-cased`() {
        assertEquals("C", avatarInitial("  camille"))
        assertEquals("É", avatarInitial("élodie"))
        assertEquals("م", avatarInitial("مريم"))
        assertNull(avatarInitial("   "))
    }

    @Test fun `step meter positions are the flow's own steps`() {
        val withAsk = OnboardingFlow.steps(askNotifications = true)
        assertEquals(5, withAsk.indexOf(OnboardingPage.NOTIFICATIONS))
        assertEquals(6, withAsk.indexOf(OnboardingPage.IDENTITY))
        assertEquals(7, withAsk.size)
        val without = OnboardingFlow.steps(askNotifications = false)
        assertEquals(5, without.indexOf(OnboardingPage.IDENTITY))
        assertEquals(6, without.size)
    }

    @Test fun `the screen still drives the same view-model actions through the stateless content`() {
        val screen = src("ui/screens/OnboardingScreen.kt")
        val wrapper = screen.substringAfter("fun OnboardingScreen(").substringBefore("internal fun OnboardingContent(")
        assertTrue(wrapper.contains("onCreateIdentity = { displayName -> viewModel.createIdentity(displayName) }"))
        assertTrue(wrapper.contains("onImportRecoveryKey = { recoveryKey -> viewModel.importRecoveryKey(recoveryKey) }"))
        assertTrue(wrapper.contains("onRecoverFromDevice = { viewModel.recoverIdentityFromDevice() }"))
        val content = screen.substringAfter("internal fun OnboardingContent(")
        assertFalse("the content must not reach the view model itself", content.contains("viewModel."))
    }

    @Test fun `the polish adds no copy - previews are decorative and reuse existing strings`() {
        val visuals = src("ui/onboarding/OnboardingVisuals.kt")
        val polish = visuals.substringAfter("__ONBOARDING_POLISH_2026_11_24__")
        assertFalse(polish.contains("R.string."))
        assertTrue("previews are hidden from TalkBack", polish.split("clearAndSetSemantics").size >= 3)
        val screen = src("ui/screens/OnboardingScreen.kt")
        assertTrue(screen.contains("StepMeter("))
        assertTrue(screen.contains("NotificationPreview("))
        assertTrue(screen.contains("IdentityPreview("))
    }
}
