package com.oshi.messenger.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.GroupManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * DEBUG BUILDS ONLY (src/debug). Writes a realistic local chat history — and single
 * "incoming" messages — straight into the local stores, so the thread's scroll and
 * position behaviour can be checked on an emulator without a second device and
 * without touching any server.
 *
 *   adb shell am broadcast -n com.oshi.messenger/.debug.DebugChatSeedReceiver \
 *       -a com.oshi.messenger.debug.SEED [--ei count 90] [--ei unread 35]
 *   adb shell am broadcast -n com.oshi.messenger/.debug.DebugChatSeedReceiver \
 *       -a com.oshi.messenger.debug.INCOMING --es target dm|group [--es text "…"] [--ez long true]
 *   adb shell am broadcast -n com.oshi.messenger/.debug.DebugChatSeedReceiver \
 *       -a com.oshi.messenger.debug.AVATARS
 *     (__AVATAR_PALETTE_V2_2026_11_17__ a dozen picture-less contacts and six groups,
 *      one message each, so the default-avatar palette can be looked at in the lists)
 *
 * The seeded 1:1 peer key and group id are logged under the tag `DebugSeed`.
 */
@AndroidEntryPoint
class DebugChatSeedReceiver : BroadcastReceiver() {

    @Inject lateinit var database: AppDatabase
    @Inject lateinit var groupManager: GroupManager
    @Inject lateinit var cryptoManager: CryptoManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SEED -> seed(
                        context,
                        count = intent.getIntExtra("count", 90),
                        unread = intent.getIntExtra("unread", 35)
                    )
                    ACTION_AVATARS -> seedAvatars(context)
                    ACTION_INCOMING -> {
                        // `--ei n 30` = a burst of 30 unread incoming rows (every 7th a
                        // photo, every 5th long), oldest first, one second apart.
                        val n = intent.getIntExtra("n", 1)
                        if (n <= 1) incoming(
                            context,
                            target = intent.getStringExtra("target") ?: "dm",
                            text = intent.getStringExtra("text"),
                            long = intent.getBooleanExtra("long", false)
                        ) else burst(context, intent.getStringExtra("target") ?: "dm", n)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "debug seed failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(context: Context, count: Int, unread: Int) {
        val me = cryptoManager.getPublicKeyString()
        database.contactDao().insert(Contact(publicKey = PEER_A, alias = "Debug Alice"))
        database.contactDao().insert(Contact(publicKey = PEER_B, alias = "Debug Bob"))
        database.contactDao().insert(Contact(publicKey = PEER_C, alias = "Debug Carol"))

        val now = System.currentTimeMillis()
        val span = 5L * 24 * 3600 * 1000 // five days of history
        val photo = photoBytes()

        // 1:1 — the last `unread` rows are incoming and unread.
        val dm = (0 until count).map { i ->
            val ts = now - span + span * i / count
            val tail = i >= count - unread
            val fromMe = !tail && i % 3 == 0
            val isPhoto = !tail && i % 13 == 5
            Message(
                id = "dbg-dm-$i",
                senderPublicKey = if (fromMe) me else PEER_A,
                recipientPublicKey = if (fromMe) PEER_A else me,
                content = if (isPhoto) "" else body(i),
                timestamp = ts,
                isFromMe = fromMe,
                isRead = !tail,
                isDelivered = true,
                mediaType = if (isPhoto) MediaType.IMAGE else null,
                mediaData = if (isPhoto) photo else null
            )
        }
        database.messageDao().insertAll(dm)

        // Group — created locally (the member keys are fake; offline emulator).
        val prefs = context.getSharedPreferences("debug_seed", Context.MODE_PRIVATE)
        val existing = prefs.getString("group_id", null)
            ?.takeIf { id -> groupManager.groups.value.any { it.id == id } }
        val groupId = existing ?: groupManager.createGroup(
            name = "Debug Group",
            memberPublicKeys = listOf(PEER_A, PEER_B, PEER_C)
        ).id
        prefs.edit().putString("group_id", groupId).apply()
        val senders = listOf(PEER_A to "Debug Alice", PEER_B to "Debug Bob", PEER_C to "Debug Carol")
        // A re-seed must not append the same ids twice (duplicate LazyColumn keys crash).
        val already = groupManager.groupMessages.value[groupId].orEmpty().map { it.id }.toSet()
        for (i in 0 until count) {
            if ("dbg-gr-$i" in already) continue
            val ts = now - span + span * i / count
            val tail = i >= count - unread
            val fromMe = !tail && i % 4 == 0
            val (key, name) = if (fromMe) me to "Me" else senders[i % 3]
            val isPhoto = !tail && i % 13 == 5
            addGroupMessage(
                groupId,
                GroupMessage(
                    id = "dbg-gr-$i",
                    groupId = groupId,
                    senderPublicKey = key,
                    senderName = name,
                    content = if (isPhoto) "" else body(i),
                    timestamp = ts,
                    isFromMe = fromMe,
                    isRead = !tail,
                    mediaType = if (isPhoto) MediaType.IMAGE else null,
                    mediaData = if (isPhoto) photo else null
                )
            )
        }
        saveGroupMessages()
        Log.i(TAG, "SEEDED dm=$PEER_A group=$groupId count=$count unread=$unread")
    }

    private suspend fun seedAvatars(context: Context) {
        val me = cryptoManager.getPublicKeyString()
        val now = System.currentTimeMillis()
        val people = listOf("Chloé Henry", "Boris", "Grace Okafor", "Amina Diallo", "Léo Martin", "Sofia",
            "Kenji Sato", "Nina", "Paul Roux", "Rania", "Tom Becker", "Zoé Laurent")
        people.forEachIndexed { i, name ->
            val key = Base64.encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest("avatar-demo-$name".toByteArray()),
                Base64.NO_WRAP
            )
            database.contactDao().insert(Contact(publicKey = key, alias = name))
            database.messageDao().insert(Message(
                id = "dbg-av-$i", senderPublicKey = key, recipientPublicKey = me,
                content = "See you at ${8 + i % 10}:30?", timestamp = now - i * 600_000L,
                isFromMe = false, isRead = i % 3 != 0, isDelivered = true
            ))
        }
        val prefs = context.getSharedPreferences("debug_seed", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("avatar_groups", false)) {
            for (name in listOf("Book Club", "Climbing Crew", "Family", "Lisbon Trip", "Design Team", "Neighbours")) {
                groupManager.createGroup(name = name, memberPublicKeys = listOf(PEER_A, PEER_B, PEER_C))
            }
            prefs.edit().putBoolean("avatar_groups", true).apply()
        }
        Log.i(TAG, "SEEDED avatars: ${people.size} contacts, 6 groups")
    }

    private suspend fun incoming(context: Context, target: String, text: String?, long: Boolean) {
        val me = cryptoManager.getPublicKeyString()
        val now = System.currentTimeMillis()
        val content = text ?: if (long) longBody("Incoming long") else "Incoming at ${java.text.SimpleDateFormat("HH:mm:ss").format(now)}"
        if (target == "group") {
            val groupId = context.getSharedPreferences("debug_seed", Context.MODE_PRIVATE)
                .getString("group_id", null) ?: return
            addGroupMessage(
                groupId,
                GroupMessage(
                    id = "dbg-gr-in-$now", groupId = groupId, senderPublicKey = PEER_B,
                    senderName = "Debug Bob", content = content, timestamp = now, isFromMe = false
                )
            )
            saveGroupMessages()
        } else {
            database.messageDao().insert(
                Message(
                    id = "dbg-dm-in-$now", senderPublicKey = PEER_A, recipientPublicKey = me,
                    content = content, timestamp = now, isFromMe = false, isDelivered = true
                )
            )
        }
        Log.i(TAG, "INCOMING $target: ${content.take(40)}")
    }

    private suspend fun burst(context: Context, target: String, n: Int) {
        val me = cryptoManager.getPublicKeyString()
        val now = System.currentTimeMillis()
        val photo = photoBytes()
        val groupId = context.getSharedPreferences("debug_seed", Context.MODE_PRIVATE)
            .getString("group_id", null)
        for (i in 0 until n) {
            val ts = now - (n - i) * 1000L
            val isPhoto = i % 7 == 3
            val content = when {
                isPhoto -> ""
                i % 5 == 2 -> longBody("Unread long #$i")
                else -> "Unread #$i"
            }
            if (target == "group" && groupId != null) {
                addGroupMessage(groupId, GroupMessage(
                    id = "dbg-gr-u-$ts", groupId = groupId, senderPublicKey = PEER_C,
                    senderName = "Debug Carol", content = content, timestamp = ts, isFromMe = false,
                    mediaType = if (isPhoto) MediaType.IMAGE else null, mediaData = if (isPhoto) photo else null
                ))
            } else {
                database.messageDao().insert(Message(
                    id = "dbg-dm-u-$ts", senderPublicKey = PEER_A, recipientPublicKey = me,
                    content = content, timestamp = ts, isFromMe = false, isDelivered = true,
                    mediaType = if (isPhoto) MediaType.IMAGE else null, mediaData = if (isPhoto) photo else null
                ))
            }
        }
        if (target == "group") saveGroupMessages()
        Log.i(TAG, "BURST $target n=$n")
    }

    // GroupManager's append path is private on purpose (it is the receive path);
    // a debug-only reflective call keeps release code untouched.
    private fun addGroupMessage(groupId: String, message: GroupMessage) {
        val m = GroupManager::class.java.getDeclaredMethod(
            "addMessageToGroup", String::class.java, GroupMessage::class.java
        )
        m.isAccessible = true
        m.invoke(groupManager, groupId, message)
    }

    private fun saveGroupMessages() {
        runCatching {
            val m = GroupManager::class.java.getDeclaredMethod("saveMessages")
            m.isAccessible = true
            m.invoke(groupManager)
        }
    }

    private fun body(i: Int): String = when {
        i % 7 == 3 -> longBody("Long message #$i")
        i % 11 == 4 -> "Message #$i with a link https://oshi-messenger.com/blog/offline-messaging and some trailing words"
        i % 5 == 1 -> "Message #$i — a medium line of text that should wrap onto a second line on a phone screen."
        else -> "Message #$i"
    }

    private fun longBody(title: String): String = buildString {
        append(title).append('\n')
        for (l in 1..18) {
            append("Line $l: the quick brown fox jumps over the lazy dog, ")
            append("and keeps running across the field.")
            if (l < 18) append('\n')
        }
        append("\nEND OF $title")
    }

    private fun photoBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(90, 60, 160))
        val p = Paint().apply { color = Color.WHITE; textSize = 120f; isAntiAlias = true }
        c.drawText("DEBUG PHOTO", 120f, 480f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "DebugSeed"
        const val ACTION_SEED = "com.oshi.messenger.debug.SEED"
        const val ACTION_INCOMING = "com.oshi.messenger.debug.INCOMING"
        const val ACTION_AVATARS = "com.oshi.messenger.debug.AVATARS"
        private fun key(seed: Int) = Base64.encodeToString(ByteArray(32) { (it * 7 + seed).toByte() }, Base64.NO_WRAP)
        val PEER_A = key(11)
        val PEER_B = key(23)
        val PEER_C = key(37)
    }
}
