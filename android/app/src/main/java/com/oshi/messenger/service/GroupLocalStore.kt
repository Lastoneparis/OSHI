package com.oshi.messenger.service

import android.content.Context
import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.SealedFile
import com.oshi.messenger.data.local.atrest.SealedStringStore
import org.json.JSONObject
import java.io.File

/**
 * __LEAK_FIX_2026_09_25__ Where the group manager keeps its state on disk: sealed files
 * (`OSHISEA1`, AES-256-GCM under the Keystore-wrapped at-rest key), no longer the
 * plaintext `shared_prefs/groups.xml`.
 *
 * ## What was wrong (audit 2026-09-24, finding A — CRITICAL)
 *
 * `groups.xml` held, in cleartext:
 *  * `group_messages_data` — the last 300 messages of every group (bodies, captions,
 *    inline media under 100 KB, reactions, edits, sender names);
 *  * `groups_data` — every group's name, roster, admins, picture, wallpaper AND its
 *    `encryptionKey`;
 *  * `left_group_ids`.
 * The Room database that mirrors the same rows is SQLCipher-encrypted, so the XML was the
 * weakest copy of the most sensitive data on the device. `oshi_devsync_left_groups.xml`
 * (name + legacy key of every group left) and `group_mentions.xml` (`@name` → member key
 * index) had the same problem on a smaller scale.
 *
 * ## Migration (no flag day, nothing lost)
 *
 * Each document is a [SealedStringStore] whose legacy source is the old prefs entry. The
 * first read after the update seals the value into `files/groups_store/<name>.sealed`,
 * reads it back, compares it byte for byte, and only then removes the entry from the XML
 * (`commit()`); the XML file itself is deleted once empty. Any failure — no Keystore key
 * yet, full disk, mismatch — leaves the XML entry in place and the value is served from
 * it, so the next launch retries. The formats inside the documents are unchanged, so the
 * loaders in [GroupManager] and the notification lookups in `FCMService` read exactly
 * what they read before.
 *
 * ## "Present but unreadable" is not "empty"
 *
 * A sealed file that cannot be opened right now (Keystore still locked after a direct-boot
 * start) reads as null. [isUnreadable] tells that apart from "never written", so the
 * manager does NOT save an empty in-memory state over the real one when the key comes
 * back.
 */
class GroupLocalStore(
    private val dir: File,
    keys: AtRestKeyProvider,
    legacy: (Doc) -> SealedStringStore.Legacy?,
) {
    enum class Doc(val fileName: String, val label: String) {
        GROUPS("groups_data.sealed", "groups/groups_data"),
        MESSAGES("group_messages_data.sealed", "groups/group_messages_data"),
        LEFT_IDS("left_group_ids.sealed", "groups/left_group_ids"),
        DEVSYNC_LEFT("devsync_left_groups.sealed", "groups/devsync_left_groups"),
        MENTIONS("group_mentions.sealed", "groups/group_mentions"),
    }

    init { dir.mkdirs() }

    private val stores: Map<Doc, SealedStringStore> = Doc.values().associateWith { d ->
        SealedStringStore(File(dir, d.fileName), d.label, keys, legacy(d))
    }

    fun read(doc: Doc): String? = stores.getValue(doc).read()

    /** @throws java.io.IOException when the value could not be stored. */
    fun write(doc: Doc, value: String) {
        stores.getValue(doc).write(value)
    }

    fun clear(doc: Doc) = stores.getValue(doc).clear()

    fun file(doc: Doc): File = File(dir, doc.fileName)

    /** The sealed file exists but cannot be opened now (see the class doc). */
    fun isUnreadable(doc: Doc): Boolean {
        val f = file(doc)
        if (!f.isFile) return false
        return runCatching {
            val head = f.inputStream().use { s -> ByteArray(SealedFile.MAGIC.size).also { s.read(it) } }
            SealedFile.isSealed(head) && read(doc) == null
        }.getOrDefault(false)
    }

    companion object {
        const val DIR_NAME = "groups_store"

        /** The pre-2026-09-25 plaintext locations. */
        const val LEGACY_PREFS = "groups"
        const val LEGACY_KEY_GROUPS = "groups_data"
        const val LEGACY_KEY_MESSAGES = "group_messages_data"
        const val LEGACY_KEY_LEFT = "left_group_ids"
        const val LEGACY_PREFS_DEVSYNC_LEFT = "oshi_devsync_left_groups"
        const val LEGACY_PREFS_MENTIONS = "group_mentions"
        const val LEGACY_KEY_MENTIONS = "v1"

        @Volatile private var shared: GroupLocalStore? = null

        /** The process-wide store (GroupManager and FCMService must share one instance). */
        fun get(context: Context): GroupLocalStore {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val app = context.applicationContext
                return GroupLocalStore(File(app.filesDir, DIR_NAME), AtRestKey) { doc -> legacyFor(app, doc) }
                    .also { shared = it }
            }
        }

        /** The `groups_data` JSON array (the notification lookups read the name / mute flag). */
        fun groupsJson(context: Context): String? = get(context).read(Doc.GROUPS)

        private fun legacyFor(app: Context, doc: Doc): SealedStringStore.Legacy = when (doc) {
            // `groups.xml` is deleted by the LAST of its three keys to migrate.
            Doc.GROUPS -> SealedStringStore.prefsLegacy(app, LEGACY_PREFS, LEGACY_KEY_GROUPS)
            Doc.MESSAGES -> SealedStringStore.prefsLegacy(app, LEGACY_PREFS, LEGACY_KEY_MESSAGES)
            Doc.LEFT_IDS -> SealedStringStore.prefsLegacy(app, LEGACY_PREFS, LEGACY_KEY_LEFT)
            Doc.MENTIONS -> SealedStringStore.prefsLegacy(app, LEGACY_PREFS_MENTIONS, LEGACY_KEY_MENTIONS)
            Doc.DEVSYNC_LEFT -> wholePrefsLegacy(app, LEGACY_PREFS_DEVSYNC_LEFT)
        }

        /**
         * A prefs file used as a map (one string entry per group id) as ONE JSON object
         * document: `{"<gid>": "<the entry's JSON string>", …}`.
         */
        private fun wholePrefsLegacy(app: Context, prefsName: String) = object : SealedStringStore.Legacy {
            override fun read(): String? {
                val all = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all
                if (all.isEmpty()) return null
                return entriesToJson(all)
            }

            override fun clear(): Boolean {
                val prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                if (prefs.all.isEmpty()) return true
                val ok = prefs.edit().clear().commit()
                if (ok) runCatching { app.deleteSharedPreferences(prefsName) }
                return ok
            }
        }

        /** Pure: string entries of a prefs map → JSON object (non-strings are dropped). */
        fun entriesToJson(all: Map<String, *>): String {
            val o = JSONObject()
            for ((k, v) in all) if (v is String) o.put(k, v)
            return o.toString()
        }
    }
}
