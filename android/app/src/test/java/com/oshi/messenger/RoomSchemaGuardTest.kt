package com.oshi.messenger

import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_COLUMN_WITHOUT_A_MIGRATION_CLOSED_THE_APP_2026_11_04__
 *
 * Anatole, 18:46: "L'application se ferme toute seule depuis la mise a jour."
 *
 * `Group` gained `evictedMemberKeys` in 1.6.69, to stop the member count
 * oscillating. `Group` is a Room `@Entity`, and the column went in WITHOUT a
 * version bump or a migration. Room keeps a hash of the expected schema in
 * `room_master_table` and checks it on every open, so for everyone who already
 * had a database the hash no longer matched and Room threw at launch. There is
 * no `fallbackToDestructiveMigration` in this project, deliberately, so it threw
 * rather than wiping: the app closed by itself, every time, for every existing
 * user.
 *
 * A FRESH INSTALL WAS FINE, because the schema is created from the entities.
 * That is why the whole unit suite passed, why the release build was clean, and
 * why nothing I did caught it. The only users who could see it were the ones who
 * already had the app, which is all of them.
 *
 * So this file does the one check that would have caught it: it pins the
 * persisted shape of each entity. Adding, renaming or removing a field now fails
 * here, and the failure message says what to do. The point is not that the list
 * below is interesting; it is that changing an entity can no longer be silent.
 */
class RoomSchemaGuardTest {

    /**
     * Java reflection rather than kotlin-reflect: data-class constructor
     * properties become backing fields, which is exactly what Room persists.
     * `$` filters out synthetic members the compiler adds.
     */
    private fun persistedFields(clazz: Class<*>): List<String> =
        clazz.declaredFields
            .map { it.name }
            .filterNot { it.contains('$') || it == "Companion" || it == "CREATOR" }
            .sorted()

    private fun assertShape(clazz: Class<*>, expected: List<String>) {
        val actual = persistedFields(clazz)
        assertEquals(
            "\n\n" +
                "${clazz.simpleName} is a Room @Entity and its persisted shape changed.\n" +
                "Changing it WITHOUT a migration makes Room throw on every launch for\n" +
                "every existing user, because the schema hash in room_master_table no\n" +
                "longer matches. A fresh install will not show it; your phone will not\n" +
                "show it; only users who already had the app will, and the app simply\n" +
                "closes.\n\n" +
                "If this change is intended, do all three:\n" +
                "  1. bump `version` in @Database,\n" +
                "  2. add MIGRATION_<old>_<new> with addColumnIfMissing(...),\n" +
                "  3. add it to ALL_MIGRATIONS,\n" +
                "then update the expected list in this test.\n",
            expected.sorted(),
            actual,
        )
    }

    @Test
    fun `the Group entity has the shape the database was migrated to`() {
        // `evictedMemberKeys` is the field whose arrival closed the app. It is in
        // this list now, and MIGRATION_20_21 is what puts it in the database.
        assertShape(
            Group::class.java,
            listOf(
                "id", "name", "description", "creatorPublicKey", "memberPublicKeys",
                "adminPublicKeys", "evictedMemberKeys", "groupImage", "createdAt",
                "updatedAt", "isPublic", "isMuted", "encryptionKey", "pinnedMessageId",
                "pinnedBy", "avatar", "groupType", "wallpaperId", "wallpaperImage",
                "groupWallpaperData", "groupWallpaperUpdatedBy", "groupWallpaperUpdatedAt",
            ),
        )
    }

    @Test
    fun `the Message entity has the shape the database was migrated to`() {
        assertShape(
            Message::class.java,
            listOf(
                "id", "senderPublicKey", "recipientPublicKey", "content", "encryptedContent",
                "timestamp", "isFromMe", "isRead", "isDelivered", "deliveryMethod",
                "mediaType", "mediaData", "replyToId", "isDeleted", "editedAt",
                "isDeletedForEveryone", "editedContent", "isViewOnce", "viewOnceOpened",
                "viewOnceOpenedAt", "isFailed", "isVideoNote", "mediaDurationMs",
            ),
        )
    }

    // ------------------------------------------------------- the chain itself

    @Test
    fun `the migration chain is contiguous and reaches the declared version`() {
        // The other half of the same mistake: a version bumped with no migration,
        // or a migration written and never registered. Both leave a user stranded
        // at launch in exactly the same way.
        val migrations = AppDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }
        assertTrue("there must be migrations at all", migrations.isNotEmpty())

        var expectedStart = migrations.first().startVersion
        for (m in migrations) {
            assertEquals(
                "migration chain has a hole: expected one starting at $expectedStart",
                expectedStart, m.startVersion,
            )
            assertEquals(
                "migration ${m.startVersion}->${m.endVersion} must step exactly one version",
                m.startVersion + 1, m.endVersion,
            )
            expectedStart = m.endVersion
        }
        assertEquals(
            "the last migration must land on the version @Database declares",
            AppDatabase.DECLARED_VERSION, migrations.last().endVersion,
        )
    }
}
