package com.oshi.messenger.data.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes ».
 *
 * An OPTION (Settings › Appearance) that replaces the two Chats tabs, "Messages"
 * and "Groups", by one chronological list with filter chips — All · Unread ·
 * Groups · People. With the option off, nothing about the two tabs changes.
 *
 * This file is the part worth testing, kept free of Compose and of the database
 * types' construction rules: the screen turns its `Conversation`s and
 * `GroupConversation`s into [ChatListMerge.Item]s with [ChatListMerge.itemsOf],
 * [ChatListMerge.arrange] decides what is shown and in which order, and the
 * screen maps each item back to the object it renders by [ChatListMerge.Item.key].
 */
object ChatListMerge {

    /** What a row IS. A LoRa channel is a room, so it files under Groups. */
    enum class Kind { PERSON, GROUP, RADIO_CHANNEL }

    /** The chips above the list, in display order. */
    enum class Filter { ALL, UNREAD, GROUPS, PEOPLE }

    data class Item(
        /** Stable list key: `c:<public key>` for a 1:1 / radio thread, `g:<group id>` for a group. */
        val key: String,
        val kind: Kind,
        /** Name shown on the row — what the search box matches. */
        val title: String,
        /** Last activity, epoch millis. */
        val timestamp: Long,
        val unread: Int,
        /** The id this row is pinned under (see [pinKeyForGroup]). */
        val pinKey: String,
        /**
         * __MERGED_GROUP_SUBFILTER_2026_11_18__ A group's type (Admin-only /
         * Collaborative / Public), for the sub-filter under the Groups chip — the
         * Groups tab's own filter row. null for a person and for a LoRa channel.
         */
        val groupType: GroupType? = null,
    ) {
        val isGroupLike: Boolean get() = kind != Kind.PERSON
    }

    /** Badge counts for the chips: the number of THREADS with something unread, per chip. */
    data class Counts(val unread: Int, val groups: Int, val people: Int)

    const val CONVERSATION_PREFIX = "c:"
    const val GROUP_PREFIX = "g:"

    /**
     * Groups are pinned in the same persisted set as one-to-one conversations
     * (`pinned_conversation_ids`), under a prefix no public key can start with
     * (base64 has no ':'), so a group id can never collide with a contact key and
     * the existing 1:1 pins keep working untouched.
     */
    fun pinKeyForGroup(groupId: String): String = "group:" + groupId.lowercase()

    /**
     * The rows for every conversation and every group.
     *
     * A group with no message yet sorts by its creation time — the same fallback
     * the Groups tab uses (`GroupsViewModel.filterGroups`).
     */
    fun itemsOf(
        conversations: List<Conversation>,
        groups: List<GroupConversation>,
        isRadioChannel: (String) -> Boolean,
    ): List<Item> {
        val people = conversations.map { c ->
            Item(
                key = CONVERSATION_PREFIX + c.participantPublicKey,
                kind = if (isRadioChannel(c.participantPublicKey)) Kind.RADIO_CHANNEL else Kind.PERSON,
                title = c.participantAlias,
                timestamp = c.lastMessage.timestamp,
                unread = c.unreadCount,
                pinKey = c.participantPublicKey,
            )
        }
        val rooms = groups.map { g ->
            Item(
                key = GROUP_PREFIX + g.group.id,
                kind = Kind.GROUP,
                title = g.group.name,
                timestamp = g.lastMessage?.timestamp ?: g.group.createdAt,
                unread = g.unreadCount,
                pinKey = pinKeyForGroup(g.group.id),
                groupType = g.group.effectiveGroupType,
            )
        }
        return people + rooms
    }

    fun matches(item: Item, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.UNREAD -> item.unread > 0
        Filter.GROUPS -> item.isGroupLike
        Filter.PEOPLE -> item.kind == Kind.PERSON
    }

    /**
     * What the merged list shows, in order.
     *
     *  • newest activity first, across both kinds; ties broken by key so two rows
     *    with the same second never swap places between two frames;
     *  • pinned rows first, each half still newest-first (the rule of
     *    `ConversationPinOrder`, which this reuses) — but NOT while searching:
     *    a search answers "where is X", and recency is the useful order there
     *    (the iOS Messages search already behaves that way);
     *  • the chip, then the search, which matches the shown name, case-insensitive.
     */
    fun arrange(
        items: List<Item>,
        pinned: Set<String>,
        filter: Filter,
        query: String,
        /** The sub-filter under the Groups chip; ignored on every other chip. null = all groups. */
        groupType: GroupType? = null,
    ): List<Item> {
        val q = query.trim()
        val chosen = items
            .filter { matches(it, filter) }
            .filter { filter != Filter.GROUPS || groupType == null || it.groupType == groupType }
            .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
            .sortedWith(compareByDescending<Item> { it.timestamp }.thenBy { it.key })
        return if (q.isEmpty()) ConversationPinOrder.apply(chosen, pinned) { it.pinKey } else chosen
    }

    fun counts(items: List<Item>): Counts = Counts(
        unread = items.count { it.unread > 0 },
        groups = items.count { it.isGroupLike && it.unread > 0 },
        people = items.count { it.kind == Kind.PERSON && it.unread > 0 },
    )
}

/**
 * The Chats tab's layout: which tabs it shows. OFF is exactly the two tabs the
 * app always had, in the same order — the option must not move anything for
 * someone who leaves it alone.
 */
object ChatsTabLayout {
    enum class Tab { MESSAGES, GROUPS, CHATS }

    fun tabs(merged: Boolean): List<Tab> =
        if (merged) listOf(Tab.CHATS) else listOf(Tab.MESSAGES, Tab.GROUPS)
}

/**
 * The persisted choice, per device.
 *
 * DEFAULT: OFF for everybody who already uses the app — an update must never
 * rearrange the screen they open twenty times a day. ON only for a FRESH install:
 * someone new has no habit to break, gets the layout every current messenger
 * uses, and sees "Discover public channels" on their very first screen instead of
 * behind a second tab. The decision is taken once, at the first read, and
 * written down, so it never flips later (an update after that first read keeps
 * whatever was decided).
 */
object MergedChatsSetting {
    const val PREFS = "oshi_chat_list"
    const val KEY = "merged_chats"

    /** A fresh install is one that was never updated: both package times are equal. */
    fun defaultFor(firstInstallTime: Long, lastUpdateTime: Long): Boolean =
        firstInstallTime > 0 && firstInstallTime == lastUpdateTime

    @Volatile private var flow: MutableStateFlow<Boolean>? = null

    fun state(context: Context): StateFlow<Boolean> = (flow ?: synchronized(this) {
        flow ?: MutableStateFlow(load(context)).also { flow = it }
    }).asStateFlow()

    fun set(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        (flow ?: synchronized(this) { flow ?: MutableStateFlow(enabled).also { flow = it } }).value = enabled
    }

    private fun load(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) return prefs.getBoolean(KEY, false)
        val decided = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            defaultFor(info.firstInstallTime, info.lastUpdateTime)
        }.getOrDefault(false)
        prefs.edit().putBoolean(KEY, decided).apply()
        return decided
    }
}
