package com.oshi.messenger.wear

import android.content.Context
import com.oshi.messenger.wear.data.Outcome
import com.oshi.messenger.wear.data.Reason
import com.oshi.messenger.wear.data.WearSource
import com.oshi.messenger.wear.data.WearThread
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearMedia
import com.oshi.wearbridge.WearMessage
import com.oshi.wearbridge.WearProtocol
import kotlinx.coroutines.delay

/**
 * DEBUG ONLY (src/debug — not compiled into release). Sample conversations
 * for an emulator with no paired phone, opted into with
 * `./gradlew :wear:assembleDebug -PwearFakeData`. Fictional people, in memory.
 *
 * `adb shell setprop debug.oshi.wear.fake <disabled|locked|empty>` forces the
 * matching status screen, for screenshots.
 */
object DebugSources {
    fun fake(context: Context): WearSource? = if (BuildConfig.FAKE_DATA) FakeWearSource() else null
}

class FakeWearSource : WearSource {

    private fun forced(): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, "debug.oshi.wear.fake", "") as String
    }.getOrDefault("")

    private val now = System.currentTimeMillis()
    private fun ago(min: Int) = now - min * 60_000L

    private val convs = mutableListOf(
        WearConversation(WearConvKind.DIRECT, "Lq8m3Vb0yTzX2pRk7sN4dHc1fWgE9aJ5uYiO6lQ+vMw=", "Léa Martin",
            "On se retrouve à 19h devant le ciné ?", 2, ago(3), "Lq8m3Vb0yTzX2pRk7sN4dHc1fWgE9aJ5uYiO6lQ+vMw="),
        WearConversation(WearConvKind.GROUP, "7f3c2a10-5b9e-4c1d-8e2f-a6b7c8d9e0f1", "Climbing crew 🧗",
            "Sam: 🎤 Voice", 5, ago(12), "7f3c2a10-5b9e-4c1d-8e2f-a6b7c8d9e0f1"),
        WearConversation(WearConvKind.DIRECT, "Zk2Pq9Wn4Rt7Yv1Bx6Cm3Lf8Hd5Gs0Ja2Ue9Io4Ky7=", "Noor",
            "📷 Photo", 1, ago(41), "Zk2Pq9Wn4Rt7Yv1Bx6Cm3Lf8Hd5Gs0Ja2Ue9Io4Ky7="),
        WearConversation(WearConvKind.GROUP, "c41d9e2a-0b3f-4a6c-9d8e-1f2a3b4c5d6e", "Famille 🏡",
            "Maman: Bisous, à dimanche ❤️", 0, ago(95), "c41d9e2a-0b3f-4a6c-9d8e-1f2a3b4c5d6e"),
        WearConversation(WearConvKind.DIRECT, "Mt5Xc8Vb1Nq4Wr7Ty0Ui3Op6As9Df2Gh5Jk8Lz1Xc4=", "Mateo Rossi",
            "Thanks for the notes!", 0, ago(60 * 26), "Mt5Xc8Vb1Nq4Wr7Ty0Ui3Op6As9Df2Gh5Jk8Lz1Xc4=", lastFromMe = true),
        WearConversation(WearConvKind.DIRECT, "Qa1Ws2Ed3Rf4Tg5Yh6Uj7Ik8Ol9Pz0Xc1Vb2Nm3Qw4=", "Dr. Ines Haddad",
            "GIF", 0, ago(60 * 50), "Qa1Ws2Ed3Rf4Tg5Yh6Uj7Ik8Ol9Pz0Xc1Vb2Nm3Qw4="),
    )

    private val threads = mutableMapOf(
        convs[0].id to mutableListOf(
            WearMessage("m1", false, text = "Tu as fini le boulot ?", timestamp = ago(30)),
            WearMessage("m2", true, text = "Presque, encore 20 min", timestamp = ago(28), reactions = listOf("👍")),
            WearMessage("m3", false, text = "", media = WearMedia.PHOTO, timestamp = ago(10)),
            WearMessage("m4", false, text = "Regarde la file d'attente 😅", timestamp = ago(9),
                reactions = listOf("😂")),
            WearMessage("m5", false, text = "On se retrouve à 19h devant le ciné ?", timestamp = ago(3)),
        ),
        convs[1].id to mutableListOf(
            WearMessage("g1", false, "Chloé", "Zk2Pq9Wn4Rt7Yv1Bx6Cm3Lf8Hd5Gs0Ja2Ue9Io4Ky7=",
                "Who's in for Saturday?", timestamp = ago(60)),
            WearMessage("g2", true, text = "Me! 🙋", timestamp = ago(55), reactions = listOf("🔥", "⭕")),
            WearMessage("g3", false, "Sam", "Mt5Xc8Vb1Nq4Wr7Ty0Ui3Op6As9Df2Gh5Jk8Lz1Xc4=",
                "", WearMedia.GIF, ago(30)),
            WearMessage("g4", false, "Chloé", "Zk2Pq9Wn4Rt7Yv1Bx6Cm3Lf8Hd5Gs0Ja2Ue9Io4Ky7=",
                "Meet at the gym at 10, bring the long rope", timestamp = ago(20)),
            WearMessage("g5", false, "Sam", "Mt5Xc8Vb1Nq4Wr7Ty0Ui3Op6As9Df2Gh5Jk8Lz1Xc4=",
                "", WearMedia.VOICE, ago(12)),
        ),
    )

    override suspend fun conversations(): Outcome<List<WearConversation>> {
        delay(250)
        return when (forced()) {
            "disabled" -> Outcome.Fail(Reason.DISABLED)
            "locked" -> Outcome.Fail(Reason.LOCKED)
            "empty" -> Outcome.Ok(emptyList())
            else -> Outcome.Ok(convs.sortedByDescending { it.timestamp })
        }
    }

    override suspend fun thread(kind: WearConvKind, id: String): Outcome<WearThread> {
        delay(200)
        val c = convs.firstOrNull { it.id == id } ?: return Outcome.Fail(Reason.NOT_FOUND)
        val msgs = threads.getOrPut(id) {
            mutableListOf(WearMessage("x-$id", c.lastFromMe, text = c.preview, timestamp = c.timestamp))
        }
        return Outcome.Ok(WearThread(c.name, msgs.takeLast(WearProtocol.MAX_THREAD_MESSAGES)))
    }

    override suspend fun send(kind: WearConvKind, id: String, text: String): Outcome<Unit> {
        delay(400)
        threads.getOrPut(id) { mutableListOf() }
            .add(WearMessage("s${System.nanoTime()}", true, text = text, timestamp = System.currentTimeMillis()))
        val i = convs.indexOfFirst { it.id == id }
        if (i >= 0) convs[i] = convs[i].copy(preview = text, unread = 0, timestamp = System.currentTimeMillis(), lastFromMe = true)
        return Outcome.Ok(Unit)
    }

    override suspend fun react(kind: WearConvKind, id: String, messageId: String, emoji: String): Outcome<Unit> {
        delay(250)
        val list = threads[id] ?: return Outcome.Fail(Reason.NOT_FOUND)
        val i = list.indexOfFirst { it.id == messageId }.takeIf { it >= 0 } ?: return Outcome.Fail(Reason.NOT_FOUND)
        val m = list[i]
        list[i] = if (m.myReaction == emoji) m.copy(myReaction = null, reactions = m.reactions - emoji)
        else m.copy(myReaction = emoji, reactions = (listOf(emoji) + (m.reactions - listOfNotNull(m.myReaction))).distinct().take(3))
        return Outcome.Ok(Unit)
    }
}
