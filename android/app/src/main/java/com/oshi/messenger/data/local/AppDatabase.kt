package com.oshi.messenger.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.oshi.messenger.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for persisting scheduled calls (task 3.1.5)
 */
@androidx.room.Entity(tableName = "scheduled_calls")
data class ScheduledCallRecord(
    @androidx.room.PrimaryKey val id: String,
    val creatorPublicKey: String,
    val peerPublicKey: String,
    val scheduledTime: Long,
    val timezone: String,
    val isVideoCall: Boolean,
    val status: String,   // CallEventStatus name
    val myResponse: String, // ResponseStatus name
    val createdAt: Long
)

@Dao
interface ScheduledCallDao {
    @Query("SELECT * FROM scheduled_calls ORDER BY scheduledTime ASC")
    fun getAllScheduledCalls(): kotlinx.coroutines.flow.Flow<List<ScheduledCallRecord>>

    @Query("SELECT * FROM scheduled_calls ORDER BY scheduledTime ASC")
    suspend fun getAllScheduledCallsList(): List<ScheduledCallRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ScheduledCallRecord)

    @Query("UPDATE scheduled_calls SET status = :status, myResponse = :myResponse WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, myResponse: String)

    @Query("DELETE FROM scheduled_calls WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * FTS4 virtual table for full-text search on group messages
 */
@Fts4(contentEntity = GroupMessage::class)
@Entity(tableName = "group_messages_fts")
data class GroupMessageFts(
    val content: String
)

/**
 * Room Database for OSHI
 * Stores messages, contacts, groups, and call history locally
 */
@Database(
    entities = [Message::class, Contact::class, CallHistoryEntry::class, Group::class, GroupMessage::class, MessageReaction::class, com.oshi.messenger.data.model.OSHIBot::class, com.oshi.messenger.data.model.BotTrigger::class, com.oshi.messenger.data.model.BotMessage::class, GroupMessageFts::class, ScheduledCallRecord::class],
    version = 21,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun callHistoryDao(): CallHistoryDao
    abstract fun groupDao(): GroupDao
    abstract fun reactionDao(): ReactionDao
    abstract fun botDao(): BotDao
    abstract fun scheduledCallDao(): ScheduledCallDao

    companion object {
        /**
         * The same number as `version` in the `@Database` annotation above.
         *
         * An annotation argument cannot be read back at runtime without
         * reflection on the annotation itself, and `RoomSchemaGuardTest` needs
         * it to prove the migration chain actually reaches the version the app
         * ships. Kept adjacent so the two are changed together.
         */
        const val DECLARED_VERSION = 21

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * True when [table] already has a column named [column].
         *
         * SQLite has no `ADD COLUMN IF NOT EXISTS`, and two of the edges below
         * (5→6, 8→9) cannot be pinned to a single column set from history — see the
         * comment on [MIGRATION_5_6]. Probing first makes those edges idempotent, so
         * a database that stopped at any of v5/v6/v7/v8 reaches v9 with the same
         * schema instead of failing the schema check.
         */
        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == column) return true
                }
            }
            return false
        }

        private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, type: String) {
            if (!hasColumn(db, table, column)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $type")
            }
        }

        /**
         * v1 → v2: no-op.
         *
         * Room was introduced in this codebase already at `version = 2` (the commit
         * that added this file declares it), so no build ever shipped a v1 database
         * and there is nothing to migrate. The edge exists only so the chain from 1
         * is contiguous and `fallbackToDestructiveMigrationOnDowngrade` never has to
         * consider it.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }

        /** v2 → v3: `message_reactions` (persisted reactions, previously in-memory). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `message_reactions` (
                        `id` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `senderPublicKey` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        /** v3 → v4: the bot tables (`bots`, `bot_triggers`, `bot_messages`) + their botId indices. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bots` (
                        `id` TEXT NOT NULL,
                        `token` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `avatarEmoji` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `permissions` TEXT NOT NULL,
                        `webhookURL` TEXT,
                        `scriptContent` TEXT,
                        `assignedGroups` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastActivity` INTEGER,
                        `messagesSent` INTEGER NOT NULL,
                        `ownerPublicKey` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bot_triggers` (
                        `id` TEXT NOT NULL,
                        `botId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `condition` TEXT NOT NULL,
                        `responseTemplate` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`botId`) REFERENCES `bots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_triggers_botId` ON `bot_triggers` (`botId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bot_messages` (
                        `id` TEXT NOT NULL,
                        `botId` TEXT NOT NULL,
                        `groupId` TEXT,
                        `content` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `isFromBot` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`botId`) REFERENCES `bots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_messages_botId` ON `bot_messages` (`botId`)")
            }
        }

        /** v4 → v5: `groups.isMuted`. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "groups", "isMuted", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6 (and, identically, v8 → v9): the columns added between v5 and v9.
         *
         * The version went 5 → 9 in ONE commit, which also introduced [MIGRATION_6_7]
         * (the FTS table) and [MIGRATION_7_8] (`scheduled_calls`). No build ever
         * shipped v6, v7 or v8, so *which* of 5→6 / 8→9 carried each column is not
         * recoverable — and inventing an answer would be worse than making both edges
         * idempotent. Both add the same column set through [addColumnIfMissing], so
         * every start point in 5..8 lands on the same v9 schema.
         */
        private fun migrateV5ColumnsToV9(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "groups", "pinnedMessageId", "TEXT")
            addColumnIfMissing(db, "messages", "editedAt", "INTEGER")
            addColumnIfMissing(db, "messages", "isDeletedForEveryone", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "messages", "editedContent", "TEXT")
            addColumnIfMissing(db, "messages", "isFailed", "INTEGER NOT NULL DEFAULT 0")
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateV5ColumnsToV9(db)
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateV5ColumnsToV9(db)
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS `group_messages_fts`
                    USING FTS4(`content`, content=`group_messages`)
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO group_messages_fts(group_messages_fts) VALUES('rebuild')
                """.trimIndent())
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scheduled_calls` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `creatorPublicKey` TEXT NOT NULL,
                        `peerPublicKey` TEXT NOT NULL,
                        `scheduledTime` INTEGER NOT NULL,
                        `timezone` TEXT NOT NULL,
                        `isVideoCall` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `myResponse` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * v9 → v10: add groupType, wallpaperId, wallpaperImage columns on `groups`.
         * Backfills `groupType` from `isPublic` so existing rows keep the right semantics.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `groupType` TEXT")
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `wallpaperId` TEXT")
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `wallpaperImage` TEXT")
                db.execSQL("UPDATE `groups` SET `groupType` = 'PUBLIC' WHERE `isPublic` = 1 AND `groupType` IS NULL")
                db.execSQL("UPDATE `groups` SET `groupType` = 'COLLABORATIVE' WHERE `isPublic` = 0 AND `groupType` IS NULL")
            }
        }

        /** v10 → v11: the group-wide wallpaper columns on `groups` (iOS `MessageGroup.groupWallpaperData`). */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 🔴 THIS SAID "TEXT", AND THE COMMENT EXPLAINING WHY WAS WRONG.
                //
                // The premise was that `ByteArray` goes through the base64
                // `@TypeConverter` at the bottom of this file. It does not: Room's
                // BUILT-IN ByteArray adapter wins over a user converter for entity
                // columns, so `Group.groupWallpaperData: ByteArray?` is a BLOB column.
                // Room's own generated schema proves it — `AppDatabase_Impl` emits
                // `groupWallpaperData` BLOB in CREATE TABLE and validates
                // `TableInfo.Column(..., "BLOB", ...)`.
                //
                // `TableInfo.Column.equals` compares affinity, and TEXT != BLOB, so a
                // database that came through THIS edge fails validation at open with
                // "Migration didn't properly handle groups(...)". There is no
                // fallbackToDestructiveMigration, so it throws on EVERY launch, for
                // ever, and the only exit is clear-data — which destroys the identity.
                //
                // Invisible on a fresh install, because `createAllTables` emits BLOB.
                // It only bites a device whose database was created at v10 or below.
                addColumnIfMissing(db, "groups", "groupWallpaperData", "BLOB")
                addColumnIfMissing(db, "groups", "groupWallpaperUpdatedAt", "INTEGER")
                addColumnIfMissing(db, "groups", "groupWallpaperUpdatedBy", "TEXT")
            }
        }

        /**
         * v11 → v12: strip oversized `mediaData` blobs.
         *
         * The v11→v12 bump changed NO schema. It was a deliberate destructive bump:
         * a row whose `mediaData` exceeded Android's CursorWindow (~2 MB) crashed the
         * app with `SQLiteBlobTooBigException` on the first `SELECT *` over `messages`,
         * and wiping the database was how the crash was cleared. Inserts are capped
         * since (`MessageRepository.sanitizeMediaForInsert`, 1.5 MB), so the only thing
         * this edge still has to do is remove the blobs that were already stored —
         * which keeps the message rows (text, timestamps, threading) instead of
         * destroying the whole database to lose them.
         *
         * `mediaData` is base64 TEXT (the ByteArray TypeConverter), so 1.5 MB of bytes
         * is ~2 000 000 characters.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `messages` SET `mediaData` = NULL WHERE length(`mediaData`) > $MAX_INLINE_MEDIA_CHARS")
            }
        }

        /**
         * v12 → v13: add `pinnedBy` and `avatar` columns on `groups`.
         *
         * Both are iOS `MessageGroup` fields (`GroupMessaging.swift:104,112`, CodingKeys
         * at `:259`) that Android had no home for, so the emoji avatar and the "who
         * pinned it" attribution were dropped on every round-trip.
         *
         * A real migration and not a version bump alone: the builder below falls back to
         * DESTRUCTIVE migration, so shipping v13 without this would wipe every message,
         * contact and call record on the update that adds an emoji avatar.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `pinnedBy` TEXT")
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `avatar` TEXT")
            }
        }

        /**
         * v13 → v14: `group_messages.mediaFileName`.
         *
         * iOS `GroupMessage.mediaFileName`, carried as `fileName` inside the media
         * JSON (`GroupMessaging.swift:2542`). Android had no column for it, so a
         * document's name rode in `content` and an iPhone drew it as the caption.
         *
         * `group_messages` is the content table of the `group_messages_fts` FTS4
         * virtual table, which indexes only `content` — adding an unindexed column
         * to the content table leaves the index untouched, so no rebuild is needed.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "group_messages", "mediaFileName", "TEXT")
            }
        }

        /**
         * v14 → v15: `group_messages.mediaDurationMs`.
         *
         * A voice note's length in milliseconds. It used to ride inside `content` as
         * `"[AUDIO] 0:05"`, and `content` becomes iOS's `plaintextContent` — the one key
         * an iPhone draws a caption from (`GroupViews.swift:2617`) — so that literal was
         * printed under every voice note. The body is now empty and the number lives
         * here. Exactly the shape of the v13 → v14 `mediaFileName` edge, one field over.
         *
         * `INTEGER` and nullable: existing rows read null, which is what
         * `VoiceNoteAudio.resolveDurationSeconds` treats as "fall back to the legacy body,
         * then to the audio container". No backfill, no rewrite of user data.
         *
         * Same FTS note as v13 → v14: `group_messages_fts` indexes only `content`, so an
         * unindexed column on the content table needs no rebuild.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "group_messages", "mediaDurationMs", "INTEGER")
            }
        }

        /**
         * v15 → v16: `group_messages.editedContent` + `group_messages.editedAt`.
         *
         * Group message EDIT. iOS has carried both fields since
         * `GroupMessaging.swift:361-362`; it broadcasts an edit as a
         * `systemMessageType: .messageEdited` envelope holding `editedMessageId` +
         * `editedContent` (`:1321-1345`) and applies it with
         * `msgs[idx].editedAt = message.timestamp; msgs[idx].editedContent = newContent`
         * (`:3296-3305`). Android had no column to receive either, so every edit an
         * iPhone made in a group was decoded and then dropped, and Android's own group
         * overlay had no Edit row at all.
         *
         * ## Why this is written the way it is
         *
         * Two `ALTER TABLE … ADD COLUMN`, both NULLABLE, no `NOT NULL`, no `DEFAULT`,
         * no table rebuild, no data rewrite. Every existing row keeps every byte it had
         * and simply reads `null` for the two new columns — which is exactly what an
         * un-edited message means. There is no backfill because there is nothing to
         * back-fill from: an edit that never happened has no content and no timestamp.
         *
         * This shape is deliberate and is the reason this edge cannot lose data the way
         * v10 → v11 and v11 → v12 once did. A `CREATE TABLE … / INSERT INTO … SELECT /
         * DROP TABLE / ALTER TABLE … RENAME` rebuild — the other way to change a
         * schema — is what destroys rows when the column list drifts; `ADD COLUMN` on
         * SQLite is a header-only operation that cannot touch existing rows.
         *
         * `addColumnIfMissing` probes `PRAGMA table_info` first, so re-running the edge
         * (a device that crashed mid-upgrade, or a build that shipped the column via a
         * different route) is a no-op rather than a `duplicate column name` throw —
         * and a throw inside a migration is what Room turns into a launch crash.
         *
         * Same FTS note as v13 → v14 and v14 → v15: `group_messages_fts` is an FTS4
         * table with `contentEntity = GroupMessage` that indexes only `content`, so an
         * unindexed column added to the content table leaves the index and its triggers
         * untouched — no rebuild, no `INSERT INTO group_messages_fts(group_messages_fts)
         * VALUES('rebuild')`.
         *
         * `INTEGER` for `editedAt`: Unix millis, the same storage `timestamp` already
         * uses on this table. It is a LOCAL column — see the note on
         * `GroupMessage.editedAt`; the wire carries the edit time as the edit envelope's
         * own Apple-epoch `timestamp`, never as an `editedAt` key.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "group_messages", "editedContent", "TEXT")
                addColumnIfMissing(db, "group_messages", "editedAt", "INTEGER")
            }
        }

        /**
         * Every edge of the 1 → [DB_VERSION] chain, in order. Room needs a CONTIGUOUS
         * path from the installed version; one missing edge used to mean a silent
         * `DROP TABLE` of every message, contact, group and call record for everyone
         * below it, because `fallbackToDestructiveMigration()` turns "no path" into a
         * wipe. Adding a version WITHOUT adding its edge here is now a crash at launch,
         * not a data loss — keep this list complete.
         */
        /**
         * v16 → v17: RESCUE. Rebuild `groups` so `groupWallpaperData` is declared BLOB.
         *
         * `MIGRATION_10_11` created that column as TEXT while the entity is a
         * `ByteArray?`, i.e. BLOB. Room compares column AFFINITY at open, so every
         * device whose database passed through that edge fails validation and throws
         * on EVERY launch — unopenable, with clear-data (and identity loss) the only
         * way out. Fixing 10→11 helps a device still sitting at v10; it cannot help a
         * device already at v16, because that edge will never run again. Only a NEW
         * edge reaches them, and Room runs migrations BEFORE it validates, so this
         * repairs the schema in time for the check that would otherwise fail.
         *
         * SQLite cannot change a column's declared type, so the table is rebuilt. That
         * is safe for the DATA: SQLite is dynamically typed and stores a BLOB value as
         * a BLOB whatever the column's affinity says, so the bytes already on disk copy
         * across unchanged — only the DECLARATION was ever wrong.
         *
         * The CREATE below is Room's own emitted schema for `groups`, copied verbatim
         * from `AppDatabase_Impl.createAllTables`, so the rebuilt table is by
         * construction the one `onValidateSchema` expects.
         *
         * Columns are listed EXPLICITLY on both sides of the INSERT. `INSERT INTO …
         * SELECT *` would silently mis-map if the two column orders ever diverged, and
         * a mis-mapped copy loses data quietly instead of failing loudly.
         *
         * A no-op on a device that never had the bad column: rebuilding a correct table
         * into an identical correct table changes nothing.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cols = "`id`, `name`, `description`, `creatorPublicKey`, " +
                    "`memberPublicKeys`, `adminPublicKeys`, `groupImage`, `createdAt`, " +
                    "`updatedAt`, `isPublic`, `isMuted`, `encryptionKey`, " +
                    "`pinnedMessageId`, `pinnedBy`, `avatar`, `groupType`, " +
                    "`wallpaperId`, `wallpaperImage`, `groupWallpaperData`, " +
                    "`groupWallpaperUpdatedAt`, `groupWallpaperUpdatedBy`"

                db.execSQL("DROP TABLE IF EXISTS `groups_rebuild_v17`")
                db.execSQL(
                    "CREATE TABLE `groups_rebuild_v17` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`creatorPublicKey` TEXT NOT NULL, `memberPublicKeys` TEXT NOT NULL, " +
                        "`adminPublicKeys` TEXT NOT NULL, `groupImage` BLOB, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`isPublic` INTEGER NOT NULL, `isMuted` INTEGER NOT NULL, " +
                        "`encryptionKey` BLOB, `pinnedMessageId` TEXT, `pinnedBy` TEXT, " +
                        "`avatar` TEXT, `groupType` TEXT, `wallpaperId` TEXT, " +
                        "`wallpaperImage` TEXT, `groupWallpaperData` BLOB, " +
                        "`groupWallpaperUpdatedAt` INTEGER, `groupWallpaperUpdatedBy` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("INSERT INTO `groups_rebuild_v17` ($cols) SELECT $cols FROM `groups`")
                db.execSQL("DROP TABLE `groups`")
                db.execSQL("ALTER TABLE `groups_rebuild_v17` RENAME TO `groups`")
            }
        }

        /**
         * v17 → v18: `group_messages.replyMarkup`.
         *
         * The bot platform's inline keyboard, carried on an inbound bot message as a
         * `replyMarkup` object and stored here as its raw JSON text. Same shape as
         * v15 → v16: one NULLABLE `ADD COLUMN`, no `NOT NULL`, no `DEFAULT`, no table
         * rebuild — a header-only operation in SQLite that cannot touch an existing
         * row, which is the whole reason it is written this way rather than as a
         * `CREATE / INSERT SELECT / DROP / RENAME`. Every pre-existing message reads
         * `null`, which is exactly what "this message had no keyboard" means.
         *
         * `addColumnIfMissing` probes `PRAGMA table_info` first, so a device that
         * crashed mid-upgrade re-runs this edge as a no-op instead of throwing
         * `duplicate column name` — and a throw inside a migration is a launch crash.
         *
         * FTS: `group_messages_fts` is FTS4 with `contentEntity = GroupMessage` and
         * indexes only `content`, so an unindexed column added to the content table
         * leaves the index and its triggers alone — no rebuild needed.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "group_messages", "replyMarkup", "TEXT")
            }
        }

        /**
         * v18 → v19: `group_messages.botId`.
         *
         * __BOT_PUBLIC_ID_2026_09_16__ The sending bot's public id — see
         * [com.oshi.messenger.data.model.GroupMessage.botId]. Same one-nullable-column
         * `ADD COLUMN` shape as v17 → v18 and v15 → v16, for the same reason.
         *
         * ## Why this is a NEW edge and not two columns folded into v17 → v18
         *
         * v18 was written a few hours earlier in the same unreleased branch, so folding
         * `botId` into that edge is tempting and would save a version number. It is also
         * exactly the mistake `MIGRATION_16_17` exists to repair: any database that has
         * ALREADY run v17 → v18 — a developer's device, an emulator left over from a
         * build on this branch, a CI image — is stamped 18 and will never run that edge
         * again. It would reach Room's validation missing `botId`, and Room turns a
         * schema mismatch into a crash on EVERY launch with clear-data (and identity
         * loss) as the only way out. A new edge costs one integer and reaches those
         * devices; an edited edge cannot.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "group_messages", "botId", "TEXT")
            }
        }

        /**
         * v19 → v20: __VIDEO_NOTE_2026_09_24__ round video messages
         * (docs/VIDEO_NOTE_SPEC.md). `messages.isVideoNote` + `messages.mediaDurationMs`,
         * `group_messages.isVideoNote` (the group row already has `mediaDurationMs`, v14).
         * Same idempotent `addColumnIfMissing` shape as every edge since v5; the boolean
         * gets `NOT NULL DEFAULT 0` exactly like `messages.isFailed`, and no FTS rebuild
         * is needed because only `content` is indexed.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "messages", "isVideoNote", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "messages", "mediaDurationMs", "INTEGER")
                addColumnIfMissing(db, "group_messages", "isVideoNote", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v20 -> v21: `groups.evictedMemberKeys`.
         *
         * 🔴 THIS MIGRATION IS A BUG FIX FOR A MISSING MIGRATION.
         *
         * `Group` gained `evictedMemberKeys` in 1.6.69 to stop the member count
         * oscillating. `Group` is a Room `@Entity`, and the column was added
         * WITHOUT bumping the version or writing this migration. Room stores a
         * hash of the expected schema in `room_master_table` and checks it on
         * every open, so for every user who already had a database the hash no
         * longer matched and Room threw at launch. There is no
         * `fallbackToDestructiveMigration` here, by design, so it threw rather
         * than wiping: the app closed by itself, every time, for everyone who
         * had the app before the update.
         *
         * A fresh install was fine, because the schema is created from the
         * entities. That is exactly why the unit suite, which builds its own
         * database, did not catch it, and why I did not either.
         *
         * `TEXT NOT NULL DEFAULT ''` matches the `List<String>` converter below:
         * it joins on commas and reads an empty string back as an empty list,
         * which is the right starting value for "nobody has been evicted".
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "groups", "evictedMemberKeys", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
            MIGRATION_19_20, MIGRATION_20_21
        )

        /**
         * The `mediaData` ceiling, in CHARACTERS, as SQLite sees it.
         *
         * `mediaData` is a ByteArray behind a TypeConverter that stores base64 TEXT, so
         * `length()` counts base64 characters, not bytes: MIGRATION_11_12's 2 000 000 is
         * ~1.5 MB of payload, the same cap `MessageRepository.MAX_INLINE_MEDIA_BYTES`
         * enforces on the way in. Named once so the two halves cannot drift apart.
         */
        const val MAX_INLINE_MEDIA_CHARS = 2_000_000

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // CSPN data-at-rest: install the SQLCipher open-helper factory when
                // encryption is enabled AND provisioning/migration succeeded. Returns
                // null (Room stays on its plaintext helper) when the flag is off, the
                // KeyStore is unavailable, or a migration did not complete cleanly —
                // in every one of those cases the plaintext database is left intact.
                // MUST be computed here, before Room opens the file, because the
                // one-time plaintext->encrypted migration runs inside it.
                val encryptedFactory =
                    DatabaseEncryption.openHelperFactory(context.applicationContext)
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "oshi_database"
                )
                // __DB_KEYSTORE_HICCUP_2026_11_11__ Always through the wrapper: a
                // corruption report must move the file aside, never delete it.
                builder.openHelperFactory(
                    DatabaseEncryption.KeepOnCorruptionFactory(
                        encryptedFactory ?: androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
                    )
                )
                val instance = builder
                    .addMigrations(*ALL_MIGRATIONS)
                    // NOT fallbackToDestructiveMigration(): a missing upgrade edge must
                    // fail LOUDLY at launch rather than silently drop the user's entire
                    // history. Only a DOWNGRADE (a user sideloading an older build) is
                    // allowed to reset, which is the one direction we cannot migrate.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    // OPEN-TIME REPAIR, and it belongs here rather than in a migration.
                    //
                    // MIGRATION_11_12 strips oversized `mediaData` for exactly this reason,
                    // but a migration only runs when the version changes. The write path
                    // could still create a new oversized row inside an already-current
                    // database (`MessageRepository.updateMessage` documents how, and now
                    // prevents it) — and once one exists, the app cannot open far enough to
                    // fix itself: the first SELECT over `messages` throws
                    // SQLiteBlobTooBigException on the main dispatcher, before any screen is
                    // drawn. That is unrecoverable from inside the app and, on a release
                    // build with no `run-as` and no root, from outside it too. The only
                    // remaining exit was clearing app data, which destroys the identity.
                    //
                    // So the strip runs on EVERY open. It is one `length()` scan over one
                    // table, it matches nothing on a healthy database, and it is the only
                    // thing standing between a poisoned row and a phone that cannot be
                    // opened. The bytes are not lost where the file store already holds
                    // them; where it does not, an empty bubble beats a dead app.
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL(
                                "UPDATE `messages` SET `mediaData` = NULL " +
                                    "WHERE `mediaData` IS NOT NULL AND length(`mediaData`) > $MAX_INLINE_MEDIA_CHARS"
                            )
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Type converters for Room
 */
class Converters {
    @TypeConverter
    fun fromDeliveryMethod(value: DeliveryMethod): String = value.name

    @TypeConverter
    /**
     * `valueOf` THROWS on a name it does not know, and Room calls this while
     * materialising a row — so a single unrecognised value takes down the whole
     * query, not one message. That can happen to anyone who installs a newer
     * build and goes back (new enum members are written by the newer one), and
     * a crash loop on the message list is a far worse failure than a mislabelled
     * transport pill. [DeliveryMethod.VPS] is the column's own default.
     */
    fun toDeliveryMethod(value: String): DeliveryMethod =
        runCatching { DeliveryMethod.valueOf(value) }.getOrDefault(DeliveryMethod.VPS)

    @TypeConverter
    fun fromMediaType(value: MediaType?): String? = value?.name

    /**
     * 🔴 A TYPE CONVERTER THAT THROWS DOES NOT LOSE ONE ROW — IT LOSES THE QUERY.
     *
     * Room's generated DAO calls this inside the cursor loop, so an
     * `IllegalArgumentException` on ONE `mediaType` string propagates out of
     * `getMessagesForConversation` entirely: the thread renders empty, and the
     * `Flow` collector dies. One unreadable label, a whole conversation gone —
     * the same shape as the iOS defect where a single unknown `mediaType` failed
     * the decode of the entire stored array.
     *
     * Nothing in this build can WRITE an unknown value (the column is only ever
     * written from the typed enum, and every inbound mapper goes through
     * [MediaType.fromWireLabel], which returns null rather than an unknown case).
     * The exposure is a row written by a LATER build — the `.gif` case iOS is
     * adding, a sideloaded downgrade — and the cost of being wrong about that is
     * the user's history. Degrading to null costs one media bubble.
     */
    @TypeConverter
    fun toMediaType(value: String?): MediaType? = value?.let {
        runCatching { MediaType.valueOf(it) }.getOrNull()
    }

    @TypeConverter
    fun fromCallStatus(value: CallStatus): String = value.name

    @TypeConverter
    fun toCallStatus(value: String): CallStatus = CallStatus.valueOf(value)

    @TypeConverter
    fun fromByteArray(value: ByteArray?): String? = value?.let {
        android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
    }

    @TypeConverter
    fun toByteArray(value: String?): ByteArray? = value?.let {
        android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
    }

    // List<String> converters for group members
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(",")

    // Map<String, String> converters for reactions
    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String {
        return value.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    @TypeConverter
    fun toStringMap(value: String): Map<String, String> {
        if (value.isEmpty()) return emptyMap()
        return value.split(";").associate {
            val (key, mapValue) = it.split(":", limit = 2)
            key to mapValue
        }
    }

    // Bot type converters
    @TypeConverter
    fun fromBotType(value: com.oshi.messenger.data.model.BotType): String = value.name

    @TypeConverter
    fun toBotType(value: String): com.oshi.messenger.data.model.BotType = com.oshi.messenger.data.model.BotType.valueOf(value)

    // GroupType — nullable so pre-migration rows persist as null until derived at runtime
    @TypeConverter
    fun fromGroupType(value: com.oshi.messenger.data.model.GroupType?): String? = value?.name

    @TypeConverter
    fun toGroupType(value: String?): com.oshi.messenger.data.model.GroupType? =
        value?.let { runCatching { com.oshi.messenger.data.model.GroupType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromTriggerType(value: com.oshi.messenger.data.model.TriggerType): String = value.name

    @TypeConverter
    fun toTriggerType(value: String): com.oshi.messenger.data.model.TriggerType = com.oshi.messenger.data.model.TriggerType.valueOf(value)

    @TypeConverter
    fun fromBotPermissions(value: com.oshi.messenger.data.model.BotPermissions): String {
        return com.google.gson.Gson().toJson(value)
    }

    @TypeConverter
    fun toBotPermissions(value: String): com.oshi.messenger.data.model.BotPermissions {
        return com.google.gson.Gson().fromJson(value, com.oshi.messenger.data.model.BotPermissions::class.java)
    }
}

/**
 * __CHAT_OPEN_PERF_2026_11_14__ What [MessageDao.getThreadRowsForContact] keeps inline.
 */
object ThreadListProjection {
    /** Attachments at or under this size stay on the thread's rows; larger ones load per visible bubble. */
    const val INLINE_MAX_BYTES = 64 * 1024
}

/**
 * Message DAO
 */
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE (senderPublicKey = :publicKey OR recipientPublicKey = :publicKey) AND isDeleted = 0 ORDER BY timestamp DESC")
    fun getMessagesForContact(publicKey: String): Flow<List<Message>>

    /**
     * __CHAT_OPEN_PERF_2026_11_14__ The rows the 1:1 THREAD draws — every column of
     * [getMessagesForContact] except the bytes it never needs up front.
     *
     * `SELECT *` pulled `encryptedContent` and every inline attachment (photos and
     * clips up to 1.5 MB live ON the row) for the WHOLE conversation through
     * SQLCipher and the CursorWindow, and Room re-runs the query on every write to
     * `messages` — any delivery tick, any read flag, in any conversation. A thread
     * with 300 photos materialised tens of MB per emission to draw ten bubbles.
     *
     * Here `encryptedContent` is never read (the thread renders `content`), and
     * `mediaData` is kept only when it is small ([ThreadListProjection.INLINE_MAX_BYTES]:
     * voice notes, contact cards, thumbnails-sized images) or when `mediaType` is
     * NULL — legacy iOS voice notes carry their audio there with no type, and the
     * bubble's lazy reader is gated on a type, so they must keep their bytes. Every
     * other media row reaches the UI with `mediaData = null`, which the bubble
     * already resolves lazily, for the rows actually on screen
     * (`rememberBubbleMedia` → `ChatViewModel.loadMediaData`).
     *
     * Column list must match [Message] exactly; Room maps by name.
     */
    @Query(
        "SELECT id, senderPublicKey, recipientPublicKey, content, NULL AS encryptedContent, " +
            "timestamp, isFromMe, isRead, isDelivered, deliveryMethod, mediaType, " +
            "CASE WHEN mediaType IS NULL OR length(mediaData) <= ${ThreadListProjection.INLINE_MAX_BYTES} " +
            "THEN mediaData ELSE NULL END AS mediaData, " +
            "replyToId, isDeleted, editedAt, isDeletedForEveryone, editedContent, isViewOnce, " +
            "viewOnceOpened, viewOnceOpenedAt, isFailed, isVideoNote, mediaDurationMs " +
            "FROM messages WHERE (senderPublicKey = :publicKey OR recipientPublicKey = :publicKey) " +
            "AND isDeleted = 0 ORDER BY timestamp DESC"
    )
    fun getThreadRowsForContact(publicKey: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): Message?

    @Query("SELECT * FROM messages WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(): Message?

    @Query("SELECT * FROM messages WHERE isRead = 0 AND isFromMe = 0 AND isDeleted = 0")
    fun getUnreadMessages(): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages WHERE senderPublicKey = :publicKey AND isRead = 0 AND isFromMe = 0 AND isDeleted = 0")
    fun getUnreadCountForContact(publicKey: String): Flow<Int>

    /**
     * __OPEN_AT_THE_FIRST_UNREAD__ The unread incoming rows of one thread, OLDEST
     * first — where the thread should land on open. Same predicate as
     * [getUnreadCountForContact] / [markAsRead], so it names exactly the rows the
     * read-mark is about to flip.
     */
    @Query("SELECT id FROM messages WHERE senderPublicKey = :publicKey AND isRead = 0 AND isFromMe = 0 AND isDeleted = 0 ORDER BY timestamp ASC")
    suspend fun getUnreadIncomingIdsForContact(publicKey: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Update
    suspend fun update(message: Message)

    /**
     * Flip every INCOMING row of this thread to read, and return how many rows
     * actually changed.
     *
     * The `Int` return is load-bearing, not decoration: `markConversationAsRead`
     * now runs on every arrival into an open chat, and the read receipt it sends
     * must fire only when something genuinely flipped — the same `if changed`
     * gate iOS applies at `OSHI/MessageManager.swift:7406-7433`. Without it,
     * every re-emission of the thread would put another receipt on the wire.
     *
     * `isFromMe = 0` stays: our OWN sent rows carry the peer's read state
     * (`getUnreadSentMessages` selects `isFromMe = 1 AND isRead = 0`,
     * AppDatabase.kt:552) and must never be flipped by our own reading.
     */
    @Query("UPDATE messages SET isRead = 1 WHERE senderPublicKey = :publicKey AND isFromMe = 0 AND isRead = 0")
    suspend fun markAsRead(publicKey: String): Int

    /**
     * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ Re-point the greeting thread
     * from the legacy `oshi.welcome` label to the author's real key.
     *
     * BOTH COLUMNS, and that is the whole lesson from iOS (`__BOTH_DIRECTIONS__`,
     * 2026-10-19): the first version there re-keyed only `senderPublicKey` and
     * matched 0 of 71 stored rows, because the rows that survive are the user's
     * OWN replies — where the legacy string is the RECIPIENT, not the sender.
     * A conversation is keyed by the other party: sender on an inbound row,
     * recipient on an outbound one. Migrating one side leaves the thread split
     * in two and still unanswerable.
     *
     * Idempotent: once nothing carries the legacy key it matches nothing.
     */
    @Query("UPDATE messages SET senderPublicKey = :realKey WHERE senderPublicKey = :legacyKey")
    suspend fun migrateWelcomeSender(legacyKey: String, realKey: String): Int

    @Query("UPDATE messages SET recipientPublicKey = :realKey WHERE recipientPublicKey = :legacyKey")
    suspend fun migrateWelcomeRecipient(legacyKey: String, realKey: String): Int

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsReadById(messageId: String)

    @Query("UPDATE messages SET isDelivered = 1 WHERE id = :messageId")
    suspend fun markAsDelivered(messageId: String)

    @Query("UPDATE messages SET content = :content, timestamp = :timestamp WHERE id = :messageId")
    suspend fun updateContent(messageId: String, content: String, timestamp: Long)

    @Query("SELECT * FROM messages WHERE senderPublicKey = :senderPublicKey AND content LIKE '%' || :sessionId || '%' AND mediaType = 'LOCATION' AND isDeleted = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun findLocationBySessionId(senderPublicKey: String, sessionId: String): Message?

    /**
     * __CHECKIN_UPDATE_MERGE_2026_09_27__ The latest check-in row of one session from one
     * sender — what an iPhone's 5-minute `update` rewrites instead of stacking a bubble.
     */
    @Query("SELECT * FROM messages WHERE senderPublicKey = :senderPublicKey AND content LIKE :prefix || '%' AND content LIKE '%' || :sessionId || '%' AND isDeleted = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun findCheckInBySessionId(senderPublicKey: String, prefix: String, sessionId: String): Message?

    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :messageId")
    suspend fun softDelete(messageId: String)

    @Query("UPDATE messages SET editedContent = :newContent, editedAt = :editedAt WHERE id = :messageId")
    suspend fun editMessage(messageId: String, newContent: String, editedAt: Long)

    @Query("UPDATE messages SET isDeletedForEveryone = 1, content = '', editedContent = NULL, mediaData = NULL WHERE id = :messageId")
    suspend fun deleteForEveryone(messageId: String)

    /**
     * 🔒 Consume a view-once message: flag it AND destroy the bytes in the same
     * statement, so a crash between the two can never leave a "spent" message whose
     * media is still readable.
     *
     * iOS does exactly this in `MessageManager.markViewOnceAsViewed`
     * (`OSHI/MessageManager.swift:6997-7006`): `hasBeenViewed = true`,
     * `mediaAttachment = nil`, `originalMediaData = nil`, then `saveMessages()`.
     * `viewOnceOpenedAt` has no iOS counterpart (iOS stores only the Bool) — it is a
     * local-only audit value and never goes on the wire.
     */
    @Query("UPDATE messages SET viewOnceOpened = 1, viewOnceOpenedAt = :openedAt, mediaData = NULL WHERE id = :messageId")
    suspend fun markViewOnceOpened(messageId: String, openedAt: Long)

    @Query("SELECT * FROM messages WHERE recipientPublicKey = :publicKey AND isFromMe = 1 AND isRead = 0 AND isDeleted = 0")
    suspend fun getUnreadSentMessages(publicKey: String): List<Message>

    /** A profile may be shared only after its owner has sent a real message to
     * this key. Control envelopes never count as consent to reveal a profile. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE recipientPublicKey = :publicKey AND isFromMe = 1 AND isDeleted = 0 AND content NOT LIKE '📸PROFILE_%')")
    suspend fun hasOutgoingUserMessage(publicKey: String): Boolean

    @Query("DELETE FROM messages WHERE senderPublicKey = :publicKey OR recipientPublicKey = :publicKey")
    suspend fun deleteAllForContact(publicKey: String)

    // 🔧 FIX 2: One-shot dedup migration helpers — rewrite every message that
    // belongs to a duplicate contact key onto the canonical key, so the chat
    // list groups them under a single conversation.
    @Query("UPDATE messages SET senderPublicKey = :canonical WHERE senderPublicKey = :duplicate")
    suspend fun rewriteSenderPublicKey(duplicate: String, canonical: String): Int

    @Query("UPDATE messages SET recipientPublicKey = :canonical WHERE recipientPublicKey = :duplicate")
    suspend fun rewriteRecipientPublicKey(duplicate: String, canonical: String): Int

    // 🔑 Identity-spelling migration (MessageRepository.canonicalizeIdentitiesOnce).
    // The thread query is a strict SQL `=`, so rows written in a second spelling of
    // the same key are invisible in the thread. These two enumerate what is on disk
    // so the one-shot pass can rewrite only what actually differs.
    // READ-ONLY additions: no column, table or index changes, so the Room schema
    // hash is unaffected and no migration edge is needed.
    @Query("SELECT DISTINCT senderPublicKey FROM messages")
    suspend fun distinctSenderKeys(): List<String>

    @Query("SELECT DISTINCT recipientPublicKey FROM messages")
    suspend fun distinctRecipientKeys(): List<String>

    /**
     * 📞 Dedup guard for locally-recorded rows (`MessageRepository.insertLocalMessage`).
     *
     * A call summary can be written twice for ONE call — the local hang-up, then the
     * peer's `callEnd` arriving right after — and each write mints a fresh UUID, so
     * dedup by id cannot see it. iOS asks the same question in Swift over its in-memory
     * array (`MessageManager.swift:5645-5654`); on Android the rows are in SQLite, so
     * it is a query. READ-ONLY: no schema change.
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE content = :content " +
            "AND (senderPublicKey = :peer OR recipientPublicKey = :peer) " +
            "AND timestamp BETWEEN :fromTs AND :toTs AND isDeleted = 0"
    )
    suspend fun countSameContentForPeer(peer: String, content: String, fromTs: Long, toTs: Long): Int
}

/**
 * Contact DAO
 */
@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE isBlocked = 0 ORDER BY alias ASC")
    fun getAllContacts(): Flow<List<Contact>>

    // 📸 Non-flow version for profile broadcasting
    @Query("SELECT * FROM contacts WHERE isBlocked = 0 ORDER BY alias ASC")
    suspend fun getAllContactsList(): List<Contact>

    // 🔑 EVERY contact, blocked included — the identity-spelling migration must
    // convert a blocked row too, or the block stops matching the rewritten
    // messages. `getAllContactsList` filters blocked out, so it cannot be reused.
    @Query("SELECT * FROM contacts")
    suspend fun getEveryContact(): List<Contact>

    @Query("SELECT * FROM contacts WHERE publicKey = :publicKey")
    suspend fun getContactByPublicKey(publicKey: String): Contact?

    // Blocking version for use in non-coroutine callbacks (must run off main thread)
    @Query("SELECT * FROM contacts WHERE publicKey = :publicKey")
    fun getContactByPublicKeySync(publicKey: String): Contact?

    @Query("SELECT * FROM contacts WHERE publicKey = :publicKey")
    fun getContactFlow(publicKey: String): Flow<Contact?>

    @Query("SELECT * FROM contacts WHERE isBlocked = 1")
    fun getBlockedContacts(): Flow<List<Contact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: Contact)

    @Update
    suspend fun update(contact: Contact)

    @Query("UPDATE contacts SET isBlocked = :blocked WHERE publicKey = :publicKey")
    suspend fun setBlocked(publicKey: String, blocked: Boolean)

    @Query("UPDATE contacts SET alias = :alias WHERE publicKey = :publicKey")
    suspend fun updateAlias(publicKey: String, alias: String)

    /**
     * 🔐 Key verification, as the UI sees it.
     *
     * `SafetyNumberManager` records a verification in SharedPreferences, because it
     * needs the FINGERPRINT to notice a later key change. But a list cannot read
     * prefs per row, so the flag the interface actually renders lives here on the
     * contact. The manager writes both: prefs keeps the fingerprint, this keeps the
     * boolean, and the conversations list stays a single reactive Room query.
     */
    @Query("UPDATE contacts SET isVerified = :verified WHERE publicKey = :publicKey")
    suspend fun setVerified(publicKey: String, verified: Boolean)

    // 📸 Profile picture sync
    //
    // Returns the number of rows actually updated. It is `Int` and not `Unit`
    // deliberately: contact rows are stored under `canonicalIdentity(key)`
    // (`ensureContactExists`), while the key on an inbound payload is the RAW wire
    // key. When a peer's key arrives base64url-encoded or unpadded the two differ,
    // this statement matches NOTHING, and a `Unit` return made that indistinguishable
    // from success — the caller logged "updated" over a write that never happened.
    @Query("UPDATE contacts SET profilePicture = :profilePicture WHERE publicKey = :publicKey")
    suspend fun updateProfilePicture(publicKey: String, profilePicture: ByteArray?): Int

    // 📸 Last seen sync. `Int` for the same reason as above.
    @Query("UPDATE contacts SET lastSeen = :lastSeen WHERE publicKey = :publicKey")
    suspend fun updateLastSeen(publicKey: String, lastSeen: Long): Int

    @Delete
    suspend fun delete(contact: Contact)

    @Query("DELETE FROM contacts WHERE publicKey = :publicKey")
    suspend fun deleteByPublicKey(publicKey: String)
}

/**
 * Call History DAO
 */
@Dao
interface CallHistoryDao {
    @Query("SELECT * FROM call_history ORDER BY startTime DESC")
    fun getAllCalls(): Flow<List<CallHistoryEntry>>

    @Query("SELECT * FROM call_history WHERE peerPublicKey = :publicKey ORDER BY startTime DESC")
    fun getCallsForContact(publicKey: String): Flow<List<CallHistoryEntry>>

    @Query("SELECT * FROM call_history WHERE callId = :callId")
    suspend fun getCallById(callId: String): CallHistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(call: CallHistoryEntry)

    @Update
    suspend fun update(call: CallHistoryEntry)

    @Query("DELETE FROM call_history WHERE callId = :callId")
    suspend fun delete(callId: String)
}

/**
 * Group DAO
 */
@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY updatedAt DESC")
    fun getAllGroups(): Flow<List<Group>>

    @Query("SELECT * FROM groups WHERE id = :groupId")
    suspend fun getGroupById(groupId: String): Group?

    @Query("SELECT * FROM groups WHERE id = :groupId")
    fun getGroupFlow(groupId: String): Flow<Group?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: Group)

    @Update
    suspend fun updateGroup(group: Group)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    // Group Messages
    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun getGroupMessages(groupId: String): Flow<List<GroupMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroupMessage(message: GroupMessage)

    @Query("UPDATE group_messages SET reactions = :reactions WHERE id = :messageId")
    suspend fun updateReactions(messageId: String, reactions: String)

    @Query("DELETE FROM group_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT * FROM group_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): GroupMessage?

    @Query("SELECT * FROM group_messages WHERE id IN (SELECT docid FROM group_messages_fts WHERE group_messages_fts MATCH :query) AND groupId = :groupId ORDER BY timestamp DESC")
    fun searchMessages(groupId: String, query: String): List<GroupMessage>
}

/**
 * Reaction DAO — Persists reactions so they survive app restarts
 */
@Dao
interface ReactionDao {
    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId")
    fun getReactionsForMessage(messageId: String): Flow<List<MessageReaction>>

    @Query("SELECT * FROM message_reactions")
    fun getAllReactions(): Flow<List<MessageReaction>>

    @Query("SELECT * FROM message_reactions")
    suspend fun getAllReactionsList(): List<MessageReaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reaction: MessageReaction)

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId AND senderPublicKey = :senderPublicKey")
    suspend fun removeReaction(messageId: String, senderPublicKey: String)

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId")
    suspend fun removeAllForMessage(messageId: String)
}

/**
 * Bot DAO — CRUD for bots, triggers, and bot messages
 */
@Dao
interface BotDao {
    @Query("SELECT * FROM bots ORDER BY createdAt DESC")
    fun getAllBots(): Flow<List<com.oshi.messenger.data.model.OSHIBot>>

    @Query("SELECT * FROM bots ORDER BY createdAt DESC")
    suspend fun getAllBotsList(): List<com.oshi.messenger.data.model.OSHIBot>

    @Query("SELECT * FROM bots WHERE id = :botId")
    suspend fun getBotById(botId: String): com.oshi.messenger.data.model.OSHIBot?

    @Query("SELECT * FROM bots WHERE id = :botId")
    fun getBotFlow(botId: String): Flow<com.oshi.messenger.data.model.OSHIBot?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBot(bot: com.oshi.messenger.data.model.OSHIBot)

    @Update
    suspend fun updateBot(bot: com.oshi.messenger.data.model.OSHIBot)

    @Query("DELETE FROM bots WHERE id = :botId")
    suspend fun deleteBot(botId: String)

    // Triggers
    @Query("SELECT * FROM bot_triggers WHERE botId = :botId")
    fun getTriggersForBot(botId: String): Flow<List<com.oshi.messenger.data.model.BotTrigger>>

    @Query("SELECT * FROM bot_triggers WHERE botId = :botId")
    suspend fun getTriggersForBotList(botId: String): List<com.oshi.messenger.data.model.BotTrigger>

    /** __BOT_IN_THE_PIPELINE_2026_11_09__ every trigger, so the group `/` menu follows trigger edits. Query only — no schema change. */
    @Query("SELECT * FROM bot_triggers")
    fun getAllTriggers(): Flow<List<com.oshi.messenger.data.model.BotTrigger>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrigger(trigger: com.oshi.messenger.data.model.BotTrigger)

    @Query("DELETE FROM bot_triggers WHERE id = :triggerId")
    suspend fun deleteTrigger(triggerId: String)

    // Bot messages
    @Query("SELECT * FROM bot_messages WHERE botId = :botId ORDER BY timestamp DESC LIMIT :limit")
    fun getBotMessages(botId: String, limit: Int = 50): Flow<List<com.oshi.messenger.data.model.BotMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBotMessage(message: com.oshi.messenger.data.model.BotMessage)

    @Query("SELECT COUNT(*) FROM bot_messages WHERE botId = :botId")
    suspend fun getBotMessageCount(botId: String): Int
}
