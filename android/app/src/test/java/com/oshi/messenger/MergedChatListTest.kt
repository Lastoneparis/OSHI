package com.oshi.messenger

import com.oshi.messenger.data.model.ChatListMerge
import com.oshi.messenger.data.model.ChatListMerge.Filter
import com.oshi.messenger.data.model.ChatListMerge.Kind
import com.oshi.messenger.data.model.ChatsTabLayout
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupConversation
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.MergedChatsSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MERGED_CHATS_2026_11_18__ The merged Messages + Groups list: order, chips,
 * unread counts, search across both kinds, and "option OFF = the old two tabs".
 */
class MergedChatListTest {

    private fun conv(key: String, alias: String, at: Long, unread: Int = 0) = Conversation(
        participantPublicKey = key,
        participantAlias = alias,
        participantProfilePicture = null,
        lastMessage = Message(senderPublicKey = key, recipientPublicKey = "me", content = "hi",
            timestamp = at, isFromMe = false),
        unreadCount = unread,
    )

    private fun group(id: String, name: String, at: Long?, unread: Int = 0, created: Long = 1L) = GroupConversation(
        group = Group(id = id, name = name, creatorPublicKey = "me", memberPublicKeys = listOf("me", "a"),
            adminPublicKeys = listOf("me"), createdAt = created, updatedAt = created),
        lastMessage = at?.let {
            GroupMessage(groupId = id, senderPublicKey = "a", senderName = "A", content = "yo", timestamp = it, isFromMe = false)
        },
        unreadCount = unread,
        memberNames = emptyMap(),
    )

    private val isRadio: (String) -> Boolean = { it.startsWith("lora-ch:") }

    private val items = ChatListMerge.itemsOf(
        conversations = listOf(
            conv("alice", "Alice", at = 500, unread = 2),
            conv("bob", "Bob", at = 100),
            conv("lora-ch:0", "LongFast", at = 300, unread = 1),
        ),
        groups = listOf(
            group("G-1", "Book Club", at = 400, unread = 3),
            group("G-2", "Family", at = null, created = 200),
        ),
        isRadioChannel = isRadio,
    )

    private fun titles(list: List<ChatListMerge.Item>) = list.map { it.title }

    @Test
    fun oneChronologicalListAcrossBothKinds() {
        val all = ChatListMerge.arrange(items, pinned = emptySet(), filter = Filter.ALL, query = "")
        assertEquals(listOf("Alice", "Book Club", "LongFast", "Family", "Bob"), titles(all))
    }

    @Test
    fun aGroupWithoutMessagesSortsByCreationTime() {
        val family = items.single { it.title == "Family" }
        assertEquals(200L, family.timestamp)
        assertEquals(Kind.GROUP, family.kind)
    }

    @Test
    fun pinnedRowsComeFirstForBothKindsAndKeepRecencyInside() {
        val pinned = setOf("bob", ChatListMerge.pinKeyForGroup("G-2"))
        val all = ChatListMerge.arrange(items, pinned, Filter.ALL, "")
        assertEquals(listOf("Family", "Bob", "Alice", "Book Club", "LongFast"), titles(all))
    }

    @Test
    fun groupPinKeysCannotCollideWithContactKeysAndIgnoreCase() {
        assertEquals("group:g-1", ChatListMerge.pinKeyForGroup("G-1"))
        assertEquals(ChatListMerge.pinKeyForGroup("ab-CD"), ChatListMerge.pinKeyForGroup("AB-cd"))
        // base64 never contains ':'; a contact key can never equal a group pin key.
        assertTrue(ChatListMerge.pinKeyForGroup("x").contains(':'))
    }

    @Test
    fun chipsFilterByKindAndUnread() {
        assertEquals(listOf("Alice", "Book Club", "LongFast"),
            titles(ChatListMerge.arrange(items, emptySet(), Filter.UNREAD, "")))
        // A LoRa channel is a room: Groups, not People.
        assertEquals(listOf("Book Club", "LongFast", "Family"),
            titles(ChatListMerge.arrange(items, emptySet(), Filter.GROUPS, "")))
        assertEquals(listOf("Alice", "Bob"),
            titles(ChatListMerge.arrange(items, emptySet(), Filter.PEOPLE, "")))
    }

    @Test
    fun unreadCountsCountThreadsPerChip() {
        val counts = ChatListMerge.counts(items)
        assertEquals(3, counts.unread)
        assertEquals(2, counts.groups)
        assertEquals(1, counts.people)
    }

    @Test
    fun searchCoversPeopleAndGroupsAndDropsPinPriority() {
        val pinned = setOf("bob")
        assertEquals(listOf("Book Club", "Bob"), titles(ChatListMerge.arrange(items, pinned, Filter.ALL, "  bo ")))
        assertEquals(listOf("Family"), titles(ChatListMerge.arrange(items, pinned, Filter.ALL, "fam")))
        // The chip still applies while searching.
        assertEquals(listOf("Bob"), titles(ChatListMerge.arrange(items, pinned, Filter.PEOPLE, "bo")))
        assertTrue(ChatListMerge.arrange(items, pinned, Filter.ALL, "zzz").isEmpty())
    }

    @Test
    fun equalTimestampsHaveAStableOrder() {
        val tied = ChatListMerge.itemsOf(
            listOf(conv("k2", "Two", 10), conv("k1", "One", 10)),
            listOf(group("G", "Grp", 10)),
            isRadio,
        )
        val once = ChatListMerge.arrange(tied, emptySet(), Filter.ALL, "")
        val again = ChatListMerge.arrange(tied.reversed(), emptySet(), Filter.ALL, "")
        assertEquals(titles(once), titles(again))
    }

    @Test
    fun keysTellTheKindsApart() {
        assertTrue(items.single { it.title == "Alice" }.key.startsWith(ChatListMerge.CONVERSATION_PREFIX))
        assertTrue(items.single { it.title == "Book Club" }.key.startsWith(ChatListMerge.GROUP_PREFIX))
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun optionOffKeepsTheTwoOriginalTabsInOrder() {
        assertEquals(listOf(ChatsTabLayout.Tab.MESSAGES, ChatsTabLayout.Tab.GROUPS), ChatsTabLayout.tabs(merged = false))
        assertEquals(listOf(ChatsTabLayout.Tab.CHATS), ChatsTabLayout.tabs(merged = true))
    }

    @Test
    fun defaultIsOnOnlyForAFreshInstall() {
        assertTrue(MergedChatsSetting.defaultFor(firstInstallTime = 1_000, lastUpdateTime = 1_000))
        assertFalse("an update keeps the old layout", MergedChatsSetting.defaultFor(1_000, 2_000))
        assertFalse("unknown install time is not 'fresh'", MergedChatsSetting.defaultFor(0, 0))
    }

    @Test
    fun theTabSourceStillBranchesOnlyOnTheSetting() {
        // OFF must render the untouched pager: the old TabRow + HorizontalPager stay in
        // ChatsTabScreen, behind the one `merged` check.
        val src = SourceText.stripComments(
            SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatsTabScreen.kt"))
        assertTrue(src.contains("MergedChatsSetting.state("))
        assertTrue(src.contains("HorizontalPager("))
        assertTrue(src.contains("GroupsScreen("))
    }

    // __MERGED_GROUP_SUBFILTER_2026_11_18__ the Groups tab's type filter, under the Groups chip.
    @Test
    fun groupsChipOffersTheGroupsTabTypeFilter() {
        fun typed(id: String, name: String, type: com.oshi.messenger.data.model.GroupType) = GroupConversation(
            group = Group(id = id, name = name, creatorPublicKey = "me", memberPublicKeys = listOf("me", "a"),
                adminPublicKeys = listOf("me"), createdAt = 1L, updatedAt = 1L, groupType = type),
            lastMessage = null, unreadCount = 0, memberNames = emptyMap(),
        )
        val all = ChatListMerge.itemsOf(
            conversations = listOf(conv("alice", "Alice", at = 500), conv("lora-ch:0", "LongFast", at = 300)),
            groups = listOf(
                typed("G-A", "Announcements", com.oshi.messenger.data.model.GroupType.ADMIN_ONLY),
                typed("G-C", "Team", com.oshi.messenger.data.model.GroupType.COLLABORATIVE),
                typed("G-P", "Town square", com.oshi.messenger.data.model.GroupType.PUBLIC),
            ),
            isRadioChannel = isRadio,
        )
        fun groupsOf(t: com.oshi.messenger.data.model.GroupType?) =
            titles(ChatListMerge.arrange(all, emptySet(), Filter.GROUPS, "", t)).sorted()
        assertEquals(listOf("Announcements", "LongFast", "Team", "Town square"), groupsOf(null))
        assertEquals(listOf("Announcements"), groupsOf(com.oshi.messenger.data.model.GroupType.ADMIN_ONLY))
        assertEquals(listOf("Team"), groupsOf(com.oshi.messenger.data.model.GroupType.COLLABORATIVE))
        assertEquals(listOf("Town square"), groupsOf(com.oshi.messenger.data.model.GroupType.PUBLIC))
        // Ignored on every other chip: a stale sub-filter never hides people.
        assertEquals(5, ChatListMerge.arrange(all, emptySet(), Filter.ALL, "", com.oshi.messenger.data.model.GroupType.PUBLIC).size)
        assertEquals(listOf("Alice"), titles(ChatListMerge.arrange(all, emptySet(), Filter.PEOPLE, "", com.oshi.messenger.data.model.GroupType.PUBLIC)))
    }
}
