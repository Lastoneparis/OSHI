package com.oshi.messenger.wear

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.AppScaffold
import com.github.takahirom.roborazzi.captureRoboImage
import com.oshi.messenger.wear.data.WearSession
import com.oshi.messenger.wear.ui.ConversationListScreen
import com.oshi.messenger.wear.ui.OshiWearTheme
import com.oshi.messenger.wear.ui.RecordingFace
import com.oshi.messenger.wear.ui.ReplyScreen
import com.oshi.messenger.wear.ui.ThreadScreen
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearNet
import com.oshi.wearbridge.WearProtocol
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * __WEAR_POLISH_2026_11_25__ Every new / changed screen, on the two round
 * sizes Play reviews against (small 192 dp, large 227 dp) at the default font
 * and at the largest Wear font (1.24×), with the debug sample data.
 *
 * Asserts the Wear quality rule that every touch target is ≥ 48 dp; with
 * `-PwearShots` it also writes a PNG per screen × device × font to
 * `wear/build/outputs/roborazzi/` for a human to look at (clipping, bezel,
 * scroll indicator).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WearScreensTest(private val sizeDp: Int, private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp @ {1}x")
        fun params() = listOf(arrayOf<Any>(192, 1.0f), arrayOf<Any>(192, 1.24f), arrayOf<Any>(227, 1.0f), arrayOf<Any>(227, 1.24f))

        private const val LEA = "Lq8m3Vb0yTzX2pRk7sN4dHc1fWgE9aJ5uYiO6lQ+vMw="
        private const val CREW = "7f3c2a10-5b9e-4c1d-8e2f-a6b7c8d9e0f1"
    }

    @get:Rule val compose = createComposeRule()

    private lateinit var fake: FakeWearSource
    private lateinit var session: WearSession

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w${sizeDp}dp-h${sizeDp}dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
        RuntimeEnvironment.setFontScale(fontScale)
        // A watch with a microphone (Robolectric reports none by default).
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE, true)
        fake = FakeWearSource().apply { latencyMs = 0 }
        WearSession.testSource = fake
        session = WearSession(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() {
        WearSession.testSource = null
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { OshiWearTheme { AppScaffold { content() } } }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        val font = if (fontScale > 1f) "124" else "100"
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${sizeDp}dp_font$font.png")
    }

    /** Wear quality: every tappable thing is at least 48 × 48 dp. */
    private fun assertTouchTargets() {
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("no clickable node found", nodes.isNotEmpty())
        nodes.forEachIndexed { i, _ ->
            val b = compose.onAllNodes(hasClickAction())[i].getUnclippedBoundsInRoot()
            assertTrue("touch target #$i is ${b.width} x ${b.height}", b.width >= 47.5.dp && b.height >= 47.5.dp)
        }
    }

    /**
     * The thread opens at its newest message, and that whole bubble (time and
     * ticks included) must sit above the fully grown Reply edge button.
     */
    private fun assertLastBubbleClearOfEdgeButton(lastId: String) {
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        val bubble = compose.onNode(androidx.compose.ui.test.hasTestTag(com.oshi.messenger.wear.ui.BUBBLE_TAG_PREFIX + lastId))
            .getUnclippedBoundsInRoot()
        val edge = compose.onNode(androidx.compose.ui.test.hasTestTag(com.oshi.messenger.wear.ui.REPLY_EDGE_TAG))
            .getUnclippedBoundsInRoot()
        assertTrue("last bubble bottom ${bubble.bottom} overlaps Reply top ${edge.top}", bubble.bottom <= edge.top + 0.5.dp)
        assertTrue("last bubble top ${bubble.top} is off screen", bubble.top >= 0.dp)
    }

    private fun loadList() {
        session.refreshList()
        compose.waitForIdle()
    }

    @Test
    fun conversationList() {
        loadList()
        show { ConversationListScreen(session) {} }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("Pinned")))
            .assertExists()
        assertTouchTargets()
        shot("list")
    }

    @Test
    fun conversationListPhoneOffline() {
        fake.fakeNet = WearNet(internet = false)
        loadList()
        show { ConversationListScreen(session) {} }
        compose.onNode(androidx.compose.ui.test.hasText("Phone offline", substring = true)).assertExists()
        shot("list_offline")
    }

    @Test
    fun conversationListMeshAndLora() {
        fake.fakeNet = WearNet(internet = false, meshPeers = 1, lora = true)
        loadList()
        show { ConversationListScreen(session) {} }
        compose.onNode(androidx.compose.ui.test.hasText("1 nearby · LoRa")).assertExists()
        shot("list_mesh_lora")
    }

    @Test
    fun directThreadWithPhotoAndTicks() {
        loadList()
        show { ThreadScreen(session, WearConvKind.DIRECT, LEA) {} }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Opening it marked it read (badge gone in the list model).
        assertTrue(session.conversation(WearConvKind.DIRECT, LEA)!!.unread == 0)
        // The photo preview arrived (memory only).
        assertTrue(session.thumbs.value["m3"] != null)
        assertLastBubbleClearOfEdgeButton("m5")
        assertTouchTargets()
        shot("thread_dm")
    }

    @Test
    fun groupThreadWithVoiceAndFaces() {
        loadList()
        show { ThreadScreen(session, WearConvKind.GROUP, CREW) {} }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Voice, 0:14", substring = true), useUnmergedTree = true).assertExists()
        assertLastBubbleClearOfEdgeButton("g5")
        shot("thread_group")
    }

    @Test
    fun replyWithReactionsVoiceAndEmoji() {
        loadList()
        session.refreshThread(WearConvKind.DIRECT, LEA)
        compose.waitForIdle()
        show { ReplyScreen(session, WearConvKind.DIRECT, LEA, "m5", onVoice = {}) {} }
        compose.onNode(androidx.compose.ui.test.hasText("Voice message")).assertExists()
        assertTouchTargets()
        shot("reply")
    }

    @Test
    fun replyHidesVoiceForAnOlderPhone() {
        fake.fakeCaps = emptySet()
        loadList()
        show { ReplyScreen(session, WearConvKind.DIRECT, LEA, null, onVoice = {}) {} }
        compose.onNode(androidx.compose.ui.test.hasText("Voice message")).assertDoesNotExist()
    }

    /** The photo preview itself (the thread opens at the bottom, below it). */
    @Test
    fun photoBubble() {
        show {
            androidx.compose.foundation.layout.Column {
                com.oshi.messenger.wear.ui.Bubble(
                    com.oshi.wearbridge.WearMessage("p", false, text = "Sunset from the roof", media = com.oshi.wearbridge.WearMedia.PHOTO,
                        timestamp = System.currentTimeMillis(), hasThumb = true),
                    isGroup = false, thumb = samplePhoto(),
                ) {}
                com.oshi.messenger.wear.ui.Bubble(
                    com.oshi.wearbridge.WearMessage("q", true, text = "Wow 😍", timestamp = System.currentTimeMillis(),
                        delivery = com.oshi.wearbridge.WearDelivery.READ),
                    isGroup = false,
                ) {}
            }
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Photo", substring = true)).assertExists()
        shot("photo_bubble")
    }

    private fun samplePhoto(): ByteArray = kotlinx.coroutines.runBlocking {
        (fake.thumbnail(WearConvKind.DIRECT, LEA, "m3") as com.oshi.messenger.wear.data.Outcome.Ok).value
    }

    @Test
    fun recordingFace() {
        show { RecordingFace(elapsedMs = 14_000, title = "Léa Martin", onSend = {}, onCancel = {}) }
        assertTouchTargets()
        shot("voice_recording")
    }

    @Test
    fun disabledStatus() {
        fake.fakeForced = "disabled"
        loadList()
        show { ConversationListScreen(session) {} }
        compose.onNode(androidx.compose.ui.test.hasText("Smartwatch is off")).assertExists()
        shot("status_disabled")
    }
}
