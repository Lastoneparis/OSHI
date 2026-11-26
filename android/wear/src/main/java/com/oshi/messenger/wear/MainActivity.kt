package com.oshi.messenger.wear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.oshi.messenger.wear.data.WearSession
import com.oshi.messenger.wear.ui.ConversationListScreen
import com.oshi.messenger.wear.ui.OshiWearTheme
import com.oshi.messenger.wear.ui.ReplyScreen
import com.oshi.messenger.wear.ui.ThreadScreen
import com.oshi.messenger.wear.ui.VoiceScreen
import com.oshi.wearbridge.WearConvKind

/**
 * __WEAR_BRIDGE_2026_11_17__ OSHI on the wrist: list → thread → reply.
 * A remote view of the phone; see [WearSession] for the memory-only cache.
 */
class MainActivity : ComponentActivity() {

    private val session: WearSession by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A voice note left by a process killed mid-recording never survives a launch.
        com.oshi.messenger.wear.data.VoiceRecorder.sweep(this)
        setContent { OshiWearApp(session, intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { OshiWearApp(session, intent) }
    }

    override fun onStart() {
        super.onStart()
        session.refreshList()
    }

    /** Leaving the app forgets every message (in-memory only, never on disk). */
    override fun onStop() {
        super.onStop()
        if (!session.awaitingInput) session.clear()
    }

    companion object {
        const val EXTRA_KIND = "oshi.wear.kind"
        const val EXTRA_ID = "oshi.wear.id"
        /** __WEAR_POLISH_2026_11_25__ "reply": the tile's Reply button opens the reply screen directly. */
        const val EXTRA_ACTION = "oshi.wear.action"
        const val ACTION_REPLY = "reply"
    }
}

object Routes {
    const val LIST = "list"
    const val THREAD = "thread/{kind}/{id}"
    const val REPLY = "reply/{kind}/{id}?mid={mid}"
    const val VOICE = "voice/{kind}/{id}"

    fun thread(kind: WearConvKind, id: String) = "thread/${kind.wire}/${Uri.encode(id)}"
    fun reply(kind: WearConvKind, id: String, messageId: String?) =
        "reply/${kind.wire}/${Uri.encode(id)}?mid=${Uri.encode(messageId.orEmpty())}"
    fun voice(kind: WearConvKind, id: String) = "voice/${kind.wire}/${Uri.encode(id)}"
}

@Composable
fun OshiWearApp(session: WearSession, intent: Intent?) {
    OshiWearTheme {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.LIST) {
                composable(Routes.LIST) {
                    ConversationListScreen(session) { c -> nav.navigate(Routes.thread(c.kind, c.id)) }
                }
                composable(Routes.THREAD) { entry ->
                    val kind = WearConvKind.of(entry.arguments?.getString("kind")) ?: WearConvKind.DIRECT
                    val id = entry.arguments?.getString("id").orEmpty()
                    ThreadScreen(session, kind, id) { mid -> nav.navigate(Routes.reply(kind, id, mid)) }
                }
                composable(Routes.REPLY) { entry ->
                    val kind = WearConvKind.of(entry.arguments?.getString("kind")) ?: WearConvKind.DIRECT
                    val id = entry.arguments?.getString("id").orEmpty()
                    val mid = entry.arguments?.getString("mid")?.takeIf { it.isNotEmpty() }
                    ReplyScreen(session, kind, id, mid, onVoice = { nav.navigate(Routes.voice(kind, id)) }) { nav.popBackStack() }
                }
                composable(Routes.VOICE) { entry ->
                    val kind = WearConvKind.of(entry.arguments?.getString("kind")) ?: WearConvKind.DIRECT
                    val id = entry.arguments?.getString("id").orEmpty()
                    // Sent: back to the thread (skip the reply screen it came from).
                    VoiceScreen(session, kind, id) {
                        if (!nav.popBackStack(Routes.THREAD, inclusive = false)) nav.popBackStack()
                    }
                }
            }
            // Tile / complication deep link straight into a conversation.
            LaunchedEffect(intent) {
                val kind = WearConvKind.of(intent?.getStringExtra(MainActivity.EXTRA_KIND))
                val id = intent?.getStringExtra(MainActivity.EXTRA_ID)
                if (kind != null && !id.isNullOrEmpty()) {
                    val action = intent.getStringExtra(MainActivity.EXTRA_ACTION)
                    // Consume the deep link: a later recomposition must not open it again.
                    intent.removeExtra(MainActivity.EXTRA_KIND)
                    intent.removeExtra(MainActivity.EXTRA_ID)
                    intent.removeExtra(MainActivity.EXTRA_ACTION)
                    nav.navigate(Routes.thread(kind, id))
                    if (action == MainActivity.ACTION_REPLY) nav.navigate(Routes.reply(kind, id, null))
                }
            }
        }
    }
}
