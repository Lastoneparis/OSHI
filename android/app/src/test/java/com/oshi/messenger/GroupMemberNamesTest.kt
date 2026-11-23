package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMemberNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** __GROUP_MEMBER_NAMES_2026_09_23__ group info shows a member's NAME, not their address. */
class GroupMemberNamesTest {

    private val desk = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="
    private val deskUrl = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s"

    @Test fun userAlias_beats_nickname_beats_definitionAlias() {
        val defs = mapOf(deskUrl to "Desk (from iPhone)")
        assertEquals("Boss", GroupMemberNames.resolve(desk, mapOf(desk to "Boss"), mapOf(deskUrl to "Hugo Desktop"), defs))
        // Automatic placeholder alias (key prefix + "...") is the address: the nickname wins.
        assertEquals("Hugo Desktop", GroupMemberNames.resolve(desk, mapOf(desk to desk.take(8) + "..."), mapOf(deskUrl to "Hugo Desktop"), defs))
        // Not a contact, no nickname: the group definition's alias.
        assertEquals("Desk (from iPhone)", GroupMemberNames.resolve(desk, emptyMap(), emptyMap(), defs))
        // Nothing known: null, the caller prints the address.
        assertNull(GroupMemberNames.resolve(desk, emptyMap(), emptyMap(), emptyMap()))
    }

    @Test fun nickname_is_found_whatever_the_key_spelling() {
        val urlSpelled = desk.replace('+', '-').replace('/', '_').trimEnd('=')
        assertEquals("Hugo Desktop", GroupMemberNames.resolve(urlSpelled, emptyMap(), mapOf(deskUrl to "Hugo Desktop"), emptyMap()))
    }

    @Test fun definition_aliases_are_read_from_the_spec_members_array() {
        val json = """[{"publicKey":"$desk","alias":"Hugo","joinedAt":"2026-09-21T14:13:20Z","isAdmin":true},""" +
            """{"publicKey":"IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI=","joinedAt":"2026-09-21T14:13:20Z","isAdmin":false},""" +
            """{"publicKey":"MzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzM=","alias":"MzMzMzMz...","isAdmin":false}]"""
        val m = GroupMemberNames.aliasesFromMembersJson(json)
        assertEquals(mapOf(deskUrl to "Hugo"), m)   // no alias ⇒ absent; placeholder alias ⇒ dropped
        assertEquals(emptyMap<String, String>(), GroupMemberNames.aliasesFromMembersJson("not json"))
        assertEquals(emptyMap<String, String>(), GroupMemberNames.aliasesFromMembersJson(null))
    }
}
