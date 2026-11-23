package com.oshi.messenger.data.local.atrest

import android.content.Context
import android.util.Log
import com.oshi.messenger.network.v2.V2PendingEnvelopeStore
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.media.MediaAttachmentStore
import com.oshi.messenger.service.media.V2SendResumeStore
import java.io.File

/**
 * One background pass per process start that brings existing installs to rest-encrypted:
 *
 *  1. sweep decrypted scratch copies ([PlaintextScratch.sweepAtStartup]);
 *  2. delete the plaintext `.tmp` siblings the pre-2026-09-22 JSON writers could leave
 *     after a crash (fixed names the new writers never produce);
 *  3. migrate legacy plaintext attachments (`files/media`, `files/groups/media`) and
 *     the small JSON stores to their sealed formats.
 *
 * Every step is idempotent and fail-safe: a file that cannot be migrated stays exactly
 * as it was (still readable — every reader accepts legacy plaintext) and is retried on
 * the next start. Without a key (Keystore failure) only the sweeps run.
 *
 * `scheduled_messages.json` is NOT migrated here: `ScheduledMessageManager` reads it in
 * its constructor and seals it right there, inside the one object that writes it.
 */
object AtRestMigration {
    private const val TAG = "AtRestMigration"

    private val LEGACY_JSON_TEMPS = listOf(
        "scheduled_messages.json.tmp",
        "push_retry_queue.json.tmp",
        "v2_send_resume.json.tmp",
    )

    /** Starts the pass on a low-priority daemon thread and returns immediately. */
    fun start(context: Context) {
        val app = context.applicationContext
        Thread({ runCatching { run(app) }.onFailure { Log.w(TAG, "at-rest pass failed: ${it.message}") } }, "oshi-at-rest")
            .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            .start()
    }

    fun run(context: Context) {
        val swept = runCatching { PlaintextScratch.sweepAtStartup(context.cacheDir) }.getOrDefault(0)
        var legacyTemps = 0
        for (name in LEGACY_JSON_TEMPS) {
            val f = File(context.filesDir, name)
            if (f.isFile && f.delete()) legacyTemps++
        }
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ base64(URL)-named plaintext link previews →
        // sealed + HMAC-named (or deleted without a key: it is a re-fetchable cache).
        val previews = runCatching {
            com.oshi.messenger.ui.components.LinkPreviewCache.shared(context).migrateLegacy()
        }.getOrDefault(0)
        if (previews > 0) Log.i(TAG, "link previews: $previews legacy plaintext file(s) converted/removed")
        if (AtRestKey.masterKey() == null) {
            Log.w(TAG, "no at-rest key — swept $swept scratch file(s), nothing migrated")
            return
        }
        val direct = MediaAttachmentStore(context.filesDir).migrateLegacy()
        val group = MediaAttachmentStore(GroupManager.groupMediaRoot(context.filesDir)).migrateLegacy()
        val pending = runCatching { V2PendingEnvelopeStore(context.filesDir).migrateLegacy() }.getOrDefault(0)
        val resume = runCatching { V2SendResumeStore(context.filesDir).migrateLegacy() }.getOrDefault(false)
        val retry = runCatching {
            SealedFile.migrate(File(context.filesDir, "push_retry_queue.json"), com.oshi.messenger.service.PushRetryQueue.SEAL_LABEL, AtRestKey)
        }.getOrDefault(false)
        Log.i(
            TAG,
            "at-rest pass: scratch=$swept legacyTmp=$legacyTemps " +
                "media=${direct.migrated}/${direct.migrated + direct.failed} (failed ${direct.failed}) " +
                "groupMedia=${group.migrated}/${group.migrated + group.failed} (failed ${group.failed}) " +
                "pending=$pending resume=$resume retryQueue=$retry",
        )
    }
}
