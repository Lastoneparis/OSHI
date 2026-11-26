package com.oshi.messenger

import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupSettings
import com.oshi.messenger.data.model.GroupSettingsMerge
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.network.v2.V2GroupHandleClient
import com.oshi.messenger.service.GroupAdminLog
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.GroupUpdateAuthorizer
import com.oshi.messenger.ui.DeepLinkParser
import com.oshi.messenger.ui.OshiDeepLink
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * __GROUP_SETTINGS_2026_11_20__ docs/GROUP_INFO_SETTINGS_SPEC.md — wire format, receive-side
 * authorization, join rules, admin log, deep links, registry client. The fixtures under
 * docs/fixtures/group_settings_v1 are the SAME JSON iOS and Desktop parse.
 */
class GroupSettingsTest {

    private val admin = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val member = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
    private val stranger = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="
    private val gid = "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"

    private fun fixture(name: String): JSONObject {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/fixtures/group_settings_v1/$name")
            if (f.isFile) return JSONObject(f.readText())
            dir = dir.parentFile
        }
        fail("fixture $name not found"); throw IllegalStateException()
    }

    private fun group(type: GroupType) = Group(
        id = gid, name = "Swiss Quant", creatorPublicKey = admin,
        memberPublicKeys = listOf(admin, member), adminPublicKeys = listOf(admin), groupType = type
    )

    /** What GroupManager does on receipt: authorizer verdict → settings merge. */
    private fun receive(type: GroupType, current: GroupSettings?, incoming: GroupSettings?, sender: String?): GroupSettings? {
        val g = group(type)
        val d = GroupUpdateAuthorizer.authorize(g.memberPublicKeys, g.adminPublicKeys, type, sender = sender)
        return GroupSettingsMerge.merge(current, incoming, GroupUpdateAuthorizer.mayChangeGroupType(d))
    }

    // ---- wire format -------------------------------------------------------

    @Test fun parsesTheFullFixture() {
        val s = GroupSettings.parseOrNull(fixture("definition_with_settings.json").optJSONObject("settings"))!!
        assertEquals(GroupSettings.Visibility.PUBLIC, s.visibility)
        assertEquals("swiss_quant", s.handle)
        assertTrue(s.isPublic)
        assertTrue(s.showPreview)
        assertEquals("q1w2e3r4t5y6u7i8o9p0aA", s.inviteToken)
        assertEquals(GroupSettings.Reactions.SOME, s.reactions)
        assertEquals(listOf("👍", "❤️", "🔥"), s.allowedReactions)
        assertEquals("Bienvenue ! Lisez le message épinglé.", s.welcome)
        assertEquals(GroupSettings.PostPolicy.ADMINS, s.postPolicy)
        assertFalse(s.adminDMs)
        assertFalse(s.dissolved)
    }

    @Test fun sanitisesTheBadFixture() {
        val s = GroupSettings.parse(fixture("definition_bad_settings.json").getJSONObject("settings"))
        assertEquals(GroupSettings.Visibility.PRIVATE, s.visibility)   // invalid handle ⇒ private
        assertNull(s.handle)
        assertFalse(s.showPreview)                                     // "yes" is not a bool
        assertEquals(GroupSettings.Reactions.ALL, s.reactions)
        assertEquals(GroupSettings.MAX_WELCOME, s.welcome.length)
        assertEquals(GroupSettings.PostPolicy.EVERYONE, s.postPolicy)
        assertTrue(s.adminDMs)                                         // 1 is not a bool ⇒ default
        assertFalse(s.dissolved)
    }

    @Test fun oldBuildDefinitionCarriesNoSettings() {
        assertNull(GroupSettings.parseOrNull(fixture("definition_without_settings.json").optJSONObject("settings")))
    }

    @Test fun roundTripAndDefaults() {
        val s = GroupSettings(GroupSettings.Visibility.PUBLIC, "abc_def", true, GroupSettings.newInviteToken(),
            GroupSettings.Reactions.SOME, listOf("👍"), "hi", GroupSettings.PostPolicy.ADMINS, false, false)
        assertEquals(s, GroupSettings.parse(JSONObject(s.toJson().toString())))
        assertEquals(GroupSettings.DEFAULT, GroupSettings.parse(JSONObject("{}")))
        // "some" with nothing allowed behaves as "none"
        assertEquals(GroupSettings.Reactions.NONE, GroupSettings.parse(JSONObject("""{"reactions":"some"}""")).reactions)
        // a handle on a private group is not kept
        assertNull(GroupSettings.parse(JSONObject("""{"visibility":"private","handle":"abcdef"}""")).handle)
    }

    @Test fun handleRules() {
        assertEquals("swiss_quant", GroupSettings.normalizeHandle(" Swiss_Quant "))
        assertNull(GroupSettings.normalizeHandle("abcd"))
        assertNull(GroupSettings.normalizeHandle("a".repeat(33)))
        assertNull(GroupSettings.normalizeHandle("bad-handle"))
        assertNull(GroupSettings.normalizeHandle("café_s"))
        assertEquals(22, GroupSettings.newInviteToken().length)
    }

    @Test fun groupRefMatchesTheServer() {
        // node -e "require('./group_handles.js').groupRefFor('5f1e2d3c-…')" on the server branch
        val expected = "4ff63d9a1b2fd469d04b5c38783b63db242039e8dbe9ebb2a07bad40ef730c65"
        assertEquals(expected, GroupSettings.groupRef(gid))
        assertEquals(expected, GroupSettings.groupRef(gid.lowercase()))
    }

    @Test fun definitionCarriesSettingsAndEmptyDescription() {
        val s = GroupSettings(visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant")
        val json = JSONObject(GroupManager.buildIosMessageGroupJson(
            groupId = gid, name = "n", typeRaw = "admin_only", adminPublicKey = admin,
            memberKeys = listOf(admin), adminKeys = listOf(admin), createdAtMs = 0, lastActivityMs = 0,
            description = "", settings = s, emitEmptyDescription = true
        ))
        assertEquals("", json.getString("description"))
        assertEquals("swiss_quant", json.getJSONObject("settings").getString("handle"))
        assertEquals("admin_only", json.getString("type"))            // never a new type value
        // Without settings and without a touched description, the definition is byte-for-byte the old one.
        val old = JSONObject(GroupManager.buildIosMessageGroupJson(
            groupId = gid, name = "n", typeRaw = "admin_only", adminPublicKey = admin,
            memberKeys = listOf(admin), adminKeys = listOf(admin), createdAtMs = 0, lastActivityMs = 0,
            description = ""
        ))
        assertFalse(old.has("settings")); assertFalse(old.has("description"))
    }

    // ---- receive-side authorization ---------------------------------------

    private val channel = GroupSettings(postPolicy = GroupSettings.PostPolicy.ADMINS, reactions = GroupSettings.Reactions.NONE, welcome = "w")
    private val hijack = GroupSettings(visibility = GroupSettings.Visibility.PUBLIC, handle = "takeover", welcome = "spam")

    @Test fun adminMayChangeSettings() {
        for (t in GroupType.entries) assertEquals(hijack, receive(t, channel, hijack, admin))
    }

    @Test fun nonAdminCannotChangeSettingsInAnyGroupType() {
        // collaborative and public types let members rename/add — still not settings.
        for (t in GroupType.entries) assertEquals("type=$t", channel, receive(t, channel, hijack, member))
    }

    @Test fun strangerAndUnauthenticatedCannotChangeSettings() {
        for (t in GroupType.entries) {
            assertEquals(channel, receive(t, channel, hijack, stranger))
            assertEquals(channel, receive(t, channel, hijack, null))
        }
    }

    @Test fun absentSettingsAreKept() {
        assertEquals(channel, receive(GroupType.ADMIN_ONLY, channel, null, admin))
    }

    @Test fun dissolvedIsSticky() {
        val dead = channel.copy(dissolved = true)
        assertTrue(receive(GroupType.ADMIN_ONLY, dead, channel, admin)!!.dissolved)
    }

    @Test fun nonAdminCannotChangeTheGroupTypeEither() {
        val g = group(GroupType.ADMIN_ONLY)
        assertFalse(GroupUpdateAuthorizer.mayChangeGroupType(
            GroupUpdateAuthorizer.authorize(g.memberPublicKeys, g.adminPublicKeys, GroupType.COLLABORATIVE, sender = member)))
    }

    @Test fun reactionAndPostPolicy() {
        val some = GroupSettings(reactions = GroupSettings.Reactions.SOME, allowedReactions = listOf("👍"))
        assertTrue(some.isReactionAllowed("👍")); assertFalse(some.isReactionAllowed("💩"))
        assertFalse(GroupSettings(reactions = GroupSettings.Reactions.NONE).isReactionAllowed("👍"))
        assertTrue(GroupSettings.DEFAULT.isReactionAllowed("💩"))
        assertFalse(channel.mayPost(senderIsAdmin = false)); assertTrue(channel.mayPost(senderIsAdmin = true))
        assertTrue(GroupSettings.DEFAULT.mayPost(false))
    }

    // ---- joins -------------------------------------------------------------

    private val pub = GroupSettings(visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant")

    private fun join(sender: String? = stranger, body: String? = null, handle: String? = "Swiss_Quant", settings: GroupSettings? = pub,
                     evicted: List<String> = emptyList(), weAreAdmin: Boolean = true, recent: Int = 0) =
        GroupUpdateAuthorizer.authorizeJoinRequest(sender, body, handle, settings, listOf(admin, member), evicted, weAreAdmin, recent)

    @Test fun publicJoinDecisionTable() {
        assertEquals("accepted", join().reason)
        assertEquals("unauthenticated", join(sender = null).reason)
        assertEquals("body-names-a-third-party", join(body = member).reason)
        assertEquals("unknown-handle", join(handle = "other_one").reason)
        assertEquals("unknown-handle", join(settings = GroupSettings.DEFAULT).reason)        // private group
        assertEquals("not-admin", join(weAreAdmin = false).reason)
        assertEquals("dissolved", join(settings = pub.copy(dissolved = true)).reason)
        assertEquals("banned", join(evicted = listOf(stranger)).reason)
        assertTrue(join(sender = member).alreadyMember)
        assertEquals("rate-limited", join(recent = 60).reason)
    }

    @Test fun inviteTokenGatesNonMembersOnceSet() {
        val legacyAdd = GroupUpdateAuthorizer.authorizeMemberSyncRequest(stranger, null, listOf(admin), emptyList(), true)
        assertTrue(legacyAdd.addRequester)
        // no token yet ⇒ legacy rule (every existing link keeps working)
        assertEquals(legacyAdd, GroupUpdateAuthorizer.authorizeInviteToken(legacyAdd, GroupSettings.DEFAULT, null))
        val s = GroupSettings(inviteToken = "q1w2e3r4t5y6u7i8o9p0aA")
        assertEquals("invite-revoked", GroupUpdateAuthorizer.authorizeInviteToken(legacyAdd, s, null).reason)
        assertEquals("invite-revoked", GroupUpdateAuthorizer.authorizeInviteToken(legacyAdd, s, "q1w2e3r4t5y6u7i8o9p0aB").reason)
        assertTrue(GroupUpdateAuthorizer.authorizeInviteToken(legacyAdd, s, "q1w2e3r4t5y6u7i8o9p0aA").addRequester)
        assertEquals("dissolved", GroupUpdateAuthorizer.authorizeInviteToken(legacyAdd, s.copy(dissolved = true), "q1w2e3r4t5y6u7i8o9p0aA").reason)
        // a member's request is never affected
        val memberReq = GroupUpdateAuthorizer.authorizeMemberSyncRequest(member, null, listOf(admin, member), emptyList(), true)
        assertEquals(memberReq, GroupUpdateAuthorizer.authorizeInviteToken(memberReq, s, null))
    }

    // ---- admin log ---------------------------------------------------------

    @Test fun adminLogDiffAndWindow() {
        val before = group(GroupType.ADMIN_ONLY)
        val after = before.copy(name = "New", description = "d", memberPublicKeys = listOf(admin, member, stranger),
            adminPublicKeys = listOf(admin, member))
        val ev = GroupAdminLog.diff(before, after, null, channel, admin, 1000L)
        val kinds = ev.map { it.kind }.toSet()
        assertTrue(kinds.containsAll(setOf(GroupAdminLog.RENAMED, GroupAdminLog.DESCRIPTION, GroupAdminLog.MEMBER_ADDED,
            GroupAdminLog.ADMIN_PROMOTED, GroupAdminLog.REACTIONS, GroupAdminLog.WELCOME, GroupAdminLog.POST_POLICY)))
        val left = GroupAdminLog.diff(before, before.copy(memberPublicKeys = listOf(admin)), null, null, member, 1L)
        assertEquals(GroupAdminLog.MEMBER_LEFT, left.single().kind)
        val now = 10 * GroupAdminLog.WINDOW_MS
        val pruned = GroupAdminLog.prune(listOf(GroupAdminLog.Event(now - GroupAdminLog.WINDOW_MS - 1, admin, "x"),
            GroupAdminLog.Event(now - 1, admin, "y")), now)
        assertEquals(listOf("y"), pruned.map { it.kind })
        val enc = GroupAdminLog.encode(mapOf(gid to ev))
        assertEquals(ev, GroupAdminLog.decode(enc)[gid])
    }

    // ---- links + registry client ------------------------------------------

    @Test fun publicLinksParse() {
        assertEquals(OshiDeepLink.PublicGroup("swiss_quant"), DeepLinkParser.parse("https://oshi-messenger.com/g/Swiss_Quant"))
        assertEquals(OshiDeepLink.PublicGroup("swiss_quant"), DeepLinkParser.parse("https://www.oshi-messenger.org/g/swiss_quant/"))
        assertEquals(OshiDeepLink.PublicGroup("swiss_quant"), DeepLinkParser.parse("oshi://g/swiss_quant"))
        assertEquals(OshiDeepLink.PublicGroup("swiss_quant"), DeepLinkParser.parse("oshi://group/join?h=swiss_quant"))
        assertTrue(DeepLinkParser.parse("https://oshi-messenger.com/g/ab") is OshiDeepLink.Unhandled)
        assertTrue(DeepLinkParser.parse("https://evil.example/g/swiss_quant") is OshiDeepLink.NotOurs)
        // the private invite link still routes to the existing flow
        assertEquals(OshiDeepLink.GroupInvite, DeepLinkParser.parse("https://oshi-messenger.com/group?gid=$gid&t=abc"))
    }

    @Test fun registryClientBodies() {
        val body = JSONObject(V2GroupHandleClient.buildRegisterBody("ab".repeat(32), listOf(admin), listOf(admin, member), "Name", null, 3))
        assertEquals(listOf(admin, member), (0 until 2).map { body.getJSONArray("controllers").getString(it) })
        assertEquals(3, body.getJSONObject("preview").getInt("memberCount"))
        assertFalse(JSONObject(V2GroupHandleClient.buildRegisterBody("ab".repeat(32), listOf(admin), listOf(admin), null, null, null)).has("preview"))
        val r = V2GroupHandleClient.parseResolved("""{"handle":"swiss_quant","joinKeys":["$admin"],"preview":null}""")!!
        assertEquals(listOf(admin), r.joinKeys); assertNull(r.previewName)
        assertNull(V2GroupHandleClient.parseResolved("""{"handle":"x","joinKeys":[]}"""))
        assertNull(V2GroupHandleClient.parseResolved("<html>"))
    }

    // __INTEROP_2026_11_21__ §2.3 sender side: what the reaction picker offers.
    @Test fun pickerOffersOnlyAllowedReactions() {
        assertNull(GroupSettings.DEFAULT.pickerReactions)
        assertEquals(emptyList<String>(), GroupSettings.DEFAULT.copy(reactions = GroupSettings.Reactions.NONE).pickerReactions)
        val some = GroupSettings.DEFAULT.copy(reactions = GroupSettings.Reactions.SOME, allowedReactions = listOf("👍", "❤️", "🔥"))
        assertEquals(listOf("👍", "❤️", "🔥"), some.pickerReactions)
        some.pickerReactions!!.forEach { assertTrue(some.isReactionAllowed(it)) }
    }
}
