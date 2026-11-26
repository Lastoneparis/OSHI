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
 *   adb shell am broadcast -n com.oshi.messenger/.debug.DebugChatSeedReceiver \
 *       -a com.oshi.messenger.debug.MEDIA   (group + channel with photos, see seedMedia)
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
                    ACTION_COMMENTS -> seedComments(context)
                    ACTION_MEDIA -> seedMedia(context)
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

    /**
     * __GROUP_COMMENTS_2026_11_21__ A channel (only admins post, comments on) where WE are the
     * admin, two posts, and member comments pushed through the REAL receive path
     * (`handleIncomingGroupMessage`), so the spec §2.12.4 rules run on the device:
     *   adb shell am broadcast -n com.oshi.messenger/.debug.DebugChatSeedReceiver \
     *       -a com.oshi.messenger.debug.COMMENTS
     * Verdicts are logged under `GroupManager` / `DebugSeed`.
     */
    private suspend fun seedComments(context: Context) {
        database.contactDao().insert(Contact(publicKey = PEER_A, alias = "Alice Martin"))
        database.contactDao().insert(Contact(publicKey = PEER_B, alias = "Bob Keller"))
        val me = cryptoManager.getPublicKeyString()
        val prefs = context.getSharedPreferences("debug_seed", Context.MODE_PRIVATE)
        val groupId = prefs.getString("comments_group_id", null)
            ?.takeIf { id -> groupManager.groups.value.any { it.id == id } }
            ?: groupManager.createGroup(name = "Swiss Quant Daily", memberPublicKeys = listOf(PEER_A, PEER_B)).id
        prefs.edit().putString("comments_group_id", groupId).apply()
        groupManager.updateSettings(groupId) {
            it.copy(postPolicy = com.oshi.messenger.data.model.GroupSettings.PostPolicy.ADMINS, comments = true)
        }
        val now = System.currentTimeMillis()
        val posts = listOf(
            "dbg-post-1" to "Weekly position: BTC 40% long, ETH flat. Volatility target 20%.",
            "dbg-post-2" to "Tonight 21:00 CET: live Q&A on the new crypto trend model.",
        )
        val have = groupManager.groupMessages.value[groupId].orEmpty().map { it.id }.toSet()
        posts.forEachIndexed { i, (id, text) ->
            if (id !in have) addGroupMessage(groupId, GroupMessage(
                id = id, groupId = groupId, senderPublicKey = me, senderName = "Me", content = text,
                timestamp = now - (3 - i) * 3_600_000L, isFromMe = true, isRead = true
            ))
        }
        saveGroupMessages()
        fun inject(id: String, sender: String, body: String, commentOn: String?, ts: Long) {
            val json = GroupManager.wireGroupMessageJson(
                id = id, wireGroupId = GroupManager.canonicalGroupId(groupId), senderPublicKey = sender,
                appleTimestamp = ts / 1000.0 - 978307200.0,
                encryptedContentB64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP),
                iosMediaType = null, mediaFileName = null, body = body, includeBodyCompat = true,
                commentOn = commentOn,
            )
            groupManager.handleIncomingGroupMessage("\uD83D\uDC65GROUP_MESSAGE\uD83D\uDC65$json", sender)
        }
        val p1 = posts[0].first
        val t0 = now - 2 * 3_600_000L
        inject("DBG-C-1", PEER_A, GroupManager.buildReplyEnvelope("Why 40 and not 50 this week?", p1, me, posts[0].second, now), p1, t0)
        inject("DBG-C-2", PEER_B, GroupManager.buildReplyEnvelope("Vol is up, that is the cap.", "DBG-C-1", PEER_A, "Why 40 and not 50 this week?", t0), p1, t0 + 60_000)
        inject("DBG-C-3", PEER_B, GroupManager.buildReplyEnvelope("Thanks for the clear notes 🙏", p1, me, posts[0].second, now), p1, t0 + 120_000)
        // must be DROPPED: a member's plain post in a channel, and a comment on a comment
        inject("DBG-X-1", PEER_B, "plain post from a member (must not show)", null, t0 + 180_000)
        inject("DBG-X-2", PEER_A, GroupManager.buildReplyEnvelope("comment on a comment (must not show)", "DBG-C-1", PEER_A, "x", t0), "DBG-C-1", t0 + 240_000)
        // a comment on the second post
        inject("DBG-C-4", PEER_A, GroupManager.buildReplyEnvelope("Will there be a replay?", "dbg-post-2", me, posts[1].second, now), "dbg-post-2", t0 + 300_000)
        val all = groupManager.groupMessages.value[groupId].orEmpty().map { it.id }
        val links = groupManager.commentLinks.value
        Log.i(TAG, "SEEDED comments group=$groupId rows=${all.size} comments=${links.size} " +
            "X1present=${"DBG-X-1" in all} X2present=${"DBG-X-2" in all} " +
            "C1=${groupManager.commentPostOf("DBG-C-1")} C4=${groupManager.commentPostOf("DBG-C-4")}")
    }

    /**
     * __BUBBLE_MEDIA_2026_11_21__ BUBBLE_MEDIA_SPEC §3 proof fixture: a group with text, a
     * landscape, a portrait, a small image and captioned photos (incoming and outgoing),
     * and a channel (admins post, comments on) with a captioned photo post.
     *   adb shell am broadcast -n com.oshi.messenger/.debug.DebugChatSeedReceiver \
     *       -a com.oshi.messenger.debug.MEDIA
     * Fake member keys, generated pictures; nothing leaves the device.
     */
    private suspend fun seedMedia(context: Context) {
        database.contactDao().insert(Contact(publicKey = PEER_A, alias = "Alice Martin"))
        database.contactDao().insert(Contact(publicKey = PEER_B, alias = "Bob Keller"))
        database.contactDao().insert(Contact(publicKey = PEER_C, alias = "Carol Diaz"))
        val me = cryptoManager.getPublicKeyString()
        val prefs = context.getSharedPreferences("debug_seed", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val groupId = prefs.getString("media_group_id", null)
            ?.takeIf { id -> groupManager.groups.value.any { it.id == id } }
            ?: groupManager.createGroup(name = "Weekend Hike", memberPublicKeys = listOf(PEER_A, PEER_B, PEER_C)).id
        prefs.edit().putString("media_group_id", groupId).apply()
        val g = groupManager.groupMessages.value[groupId].orEmpty().map { it.id }.toSet()
        data class Row(val id: String, val key: String, val name: String, val text: String, val photo: ByteArray?)
        val rows = listOf(
            Row("dbg-md-1", PEER_A, "Alice Martin", "Look at these from Saturday 👇", null),
            Row("dbg-md-2", PEER_A, "Alice Martin", "", scene(1600, 1000, 0)),
            Row("dbg-md-3", PEER_B, "Bob Keller", "", scene(900, 1600, 1)),
            Row("dbg-md-4", PEER_C, "Carol Diaz", "", scene(160, 120, 2)),
            Row("dbg-md-5", me, "Me", "Sunset from the ridge — worth the climb.", scene(1200, 900, 3)),
            Row("dbg-md-6", PEER_B, "Bob Keller", "The trail sign, for next time.", scene(1000, 1500, 4)),
            Row("dbg-md-7", me, "Me", "", scene(1080, 1920, 1)),
            Row("dbg-md-8", me, "Me", "Same again next month?", null),
        )
        rows.forEachIndexed { i, r ->
            if (r.id in g) return@forEachIndexed
            addGroupMessage(groupId, GroupMessage(
                id = r.id, groupId = groupId, senderPublicKey = r.key, senderName = r.name,
                content = r.text, timestamp = now - (rows.size - i) * 120_000L,
                isFromMe = r.key == me, isRead = true,
                mediaType = if (r.photo != null) MediaType.IMAGE else null, mediaData = r.photo
            ))
        }

        val channelId = prefs.getString("media_channel_id", null)
            ?.takeIf { id -> groupManager.groups.value.any { it.id == id } }
            ?: groupManager.createGroup(name = "Alpine News", memberPublicKeys = listOf(PEER_A, PEER_B)).id
        prefs.edit().putString("media_channel_id", channelId).apply()
        groupManager.updateSettings(channelId) {
            it.copy(postPolicy = com.oshi.messenger.data.model.GroupSettings.PostPolicy.ADMINS, comments = true)
        }
        val c = groupManager.groupMessages.value[channelId].orEmpty().map { it.id }.toSet()
        val posts = listOf(
            Triple("dbg-ch-1", "First snow on the pass this morning. Road open, chains advised above 1800 m.", scene(1600, 1066, 0)),
            Triple("dbg-ch-2", "Hut opening hours for the winter season are now online.", null),
            Triple("dbg-ch-3", "", scene(1000, 1400, 4)),
        )
        posts.forEachIndexed { i, (id, text, photo) ->
            if (id in c) return@forEachIndexed
            addGroupMessage(channelId, GroupMessage(
                id = id, groupId = channelId, senderPublicKey = me, senderName = "Me", content = text,
                timestamp = now - (posts.size - i) * 3_600_000L, isFromMe = true, isRead = true,
                mediaType = if (photo != null) MediaType.IMAGE else null, mediaData = photo
            ))
        }
        saveGroupMessages()
        Log.i(TAG, "SEEDED media group=$groupId channel=$channelId")
    }

    /** A generated "photo": sky gradient, sun, two mountain layers, a frame line and its size. */
    private fun scene(w: Int, h: Int, palette: Int): ByteArray {
        val skies = listOf(
            intArrayOf(Color.rgb(255, 170, 110), Color.rgb(120, 90, 200)),
            intArrayOf(Color.rgb(130, 200, 255), Color.rgb(230, 245, 255)),
            intArrayOf(Color.rgb(255, 220, 120), Color.rgb(255, 120, 120)),
            intArrayOf(Color.rgb(250, 120, 80), Color.rgb(60, 40, 110)),
            intArrayOf(Color.rgb(120, 220, 180), Color.rgb(30, 90, 120)),
        )
        val sky = skies[palette % skies.size]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint().apply { isAntiAlias = true }
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), sky[0], sky[1], android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(230, 255, 245, 200)
        cv.drawCircle(w * 0.72f, h * 0.32f, minOf(w, h) * 0.09f, p)
        fun ridge(base: Float, amp: Float, color: Int) {
            val path = android.graphics.Path()
            path.moveTo(0f, h.toFloat())
            val n = 6
            for (k in 0..n) {
                val x = w * k / n.toFloat()
                val y = h * base - (if (k % 2 == 1) amp else amp * 0.35f) * h
                path.lineTo(x, y)
            }
            path.lineTo(w.toFloat(), h.toFloat()); path.close()
            p.color = color
            cv.drawPath(path, p)
        }
        ridge(0.78f, 0.22f, Color.argb(255, 70, 60, 110))
        ridge(0.92f, 0.12f, Color.argb(255, 35, 30, 60))
        // A 2-px frame: any crop or letterbox band is visible at a glance.
        p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(2f, w / 200f); p.color = Color.WHITE
        cv.drawRect(p.strokeWidth / 2, p.strokeWidth / 2, w - p.strokeWidth / 2, h - p.strokeWidth / 2, p)
        p.style = Paint.Style.FILL; p.textSize = maxOf(12f, w / 14f)
        cv.drawText("${w}×$h", w * 0.05f, h * 0.12f + p.textSize / 2, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        return out.toByteArray()
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
        const val ACTION_COMMENTS = "com.oshi.messenger.debug.COMMENTS"
        const val ACTION_MEDIA = "com.oshi.messenger.debug.MEDIA"
        private fun key(seed: Int) = Base64.encodeToString(ByteArray(32) { (it * 7 + seed).toByte() }, Base64.NO_WRAP)
        val PEER_A = key(11)
        val PEER_B = key(23)
        val PEER_C = key(37)
    }
}
