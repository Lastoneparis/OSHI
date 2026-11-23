package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.components.ReactionBarMetrics
import com.oshi.messenger.ui.components.ReactionWhoRow
import com.oshi.messenger.ui.components.reactionWhoRows
import com.oshi.messenger.ui.components.sameReactorKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.6.97: "who reacted" sheet rows, 48dp chip target, group reaction banner rule. */
class ReactionWhoAndGroupNotifyTest {

    private val me = "AbC+/def=="
    private val alice = "alice+key/1="
    private val bob = "bob_key-2"

    private val names = mapOf(alice to "Alice", bob to "bob")

    @Test
    fun `rows put the most used reaction first and You first in a row`() {
        val rows = reactionWhoRows(
            mapOf("👍" to listOf(alice), "🔥" to listOf(bob, "AbC-_def", alice)),
            myKey = me, youLabel = "You", nameFor = { names[it] ?: it.take(4) },
        )
        assertEquals(
            listOf(ReactionWhoRow("🔥", listOf("You", "Alice", "bob")), ReactionWhoRow("👍", listOf("Alice"))),
            rows,
        )
    }

    @Test
    fun `empty reactions give no rows`() {
        assertTrue(reactionWhoRows(mapOf("👍" to emptyList()), me, "You") { it }.isEmpty())
    }

    @Test
    fun `key identity ignores base64url and padding spelling`() {
        assertTrue(sameReactorKey("AbC+/def==", "AbC-_def"))
        assertFalse(sameReactorKey("AbC+/def==", "AbC+/deg"))
        assertFalse(sameReactorKey("", ""))
    }

    @Test
    fun `chip touch target is at least 48dp`() {
        assertTrue(ReactionBarMetrics.CHIP_TOUCH_DP >= 48)
    }

    private fun msg(fromMe: Boolean, sender: String) = GroupMessage(
        groupId = "g", senderPublicKey = sender, senderName = "x", content = "hi", isFromMe = fromMe,
    )

    @Test
    fun `someone reacting to my message notifies`() {
        assertTrue(GroupManager.shouldNotifyGroupReaction(me, alice, msg(true, me), "👍", false, null))
        // isFromMe false but my key as sender (row from another of my devices)
        assertTrue(GroupManager.shouldNotifyGroupReaction(me, alice, msg(false, "AbC-_def"), "👍", false, "🔥"))
    }

    @Test
    fun `no banner for my own reaction, others' messages, removals, repeats`() {
        assertFalse(GroupManager.shouldNotifyGroupReaction(me, "AbC-_def", msg(true, me), "👍", false, null))
        assertFalse(GroupManager.shouldNotifyGroupReaction(me, alice, msg(false, bob), "👍", false, null))
        assertFalse(GroupManager.shouldNotifyGroupReaction(me, alice, msg(true, me), "👍", true, "👍"))
        assertFalse(GroupManager.shouldNotifyGroupReaction(me, alice, msg(true, me), "👍", false, "👍"))
        assertFalse(GroupManager.shouldNotifyGroupReaction(me, alice, null, "👍", false, null))
    }
}
