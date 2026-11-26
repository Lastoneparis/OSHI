package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.GroupSettings
import com.oshi.messenger.service.GroupAdminLog
import com.oshi.messenger.service.GroupComments
import com.oshi.messenger.service.GroupComments.Outcome
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.GroupUpdateAuthorizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __GROUP_COMMENTS_2026_11_21__ docs/GROUP_INFO_SETTINGS_SPEC.md §2.12 — wire format and the
 * receive decision, run on the SAME vectors iOS and Desktop use
 * (docs/fixtures/group_settings_v1/comment_*.json, §2.12.9 table).
 */
class GroupCommentsTest {

    private val admin = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val member = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
    private val gid = "5F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"
    private val postId = "0A1B2C3D-0000-4000-8000-000000000001"
    private val c1Id = "0A1B2C3D-0000-4000-8000-000000000002"

    private fun fixture(name: String): JSONObject {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/fixtures/group_settings_v1/$name")
            if (f.isFile) return JSONObject(f.readText())
            dir = dir.parentFile
        }
        fail("fixture $name not found"); throw IllegalStateException()
    }

    private val channelOn: GroupSettings =
        GroupSettings.parse(fixture("definition_with_comments.json").getJSONObject("settings"))
    private val channelOff = channelOn.copy(comments = false)

    /** Body the way GroupManager sees it: base64 → UTF-8 → reply envelope unwrapped. */
    private fun body(json: JSONObject): Pair<String, String?> {
        val raw = String(Base64.getDecoder().decode(json.getString("encryptedContent")), Charsets.UTF_8)
        val reply = GroupManager.parseReplyEnvelope(raw)
        return (reply?.content ?: raw) to reply?.replyToId
    }

    private fun row(json: JSONObject): GroupMessage = GroupMessage(
        id = json.getString("id"), groupId = gid, senderPublicKey = json.getString("senderPublicKey"),
        senderName = "", content = body(json).first, isFromMe = false, replyToId = body(json).second,
    )

    private val c = GroupUpdateAuthorizer::canonicalIdentity
    private fun isAdmin(k: String) = c(k) == c(admin)

    /** Exactly the inputs GroupManager.handleIncomingGroupMessage passes. */
    private fun verdict(
        name: String,
        settings: GroupSettings? = channelOn,
        stored: List<GroupMessage> = listOf(row(fixture("comment_post.json"))),
        comments: Set<String> = emptySet(),
        evicted: Set<String> = emptySet(),
        recent: Int = 0,
        adminCheck: (String) -> Boolean = ::isAdmin,
    ): GroupComments.Decision {
        val json = fixture(name)
        return GroupComments.evaluate(
            json = json, body = body(json).first, sender = json.getString("senderPublicKey"),
            settings = settings, stored = stored, isAdmin = adminCheck,
            isEvicted = { k -> evicted.any { c(it) == c(k) } },
            isComment = { id -> comments.any { it.equals(id, ignoreCase = true) } },
            recentFromSender = recent,
        ).first
    }

    // ---- settings subkey -----------------------------------------------------

    @Test fun commentsSubkeyParsesAndRoundTrips() {
        assertTrue(channelOn.comments)
        assertTrue(channelOn.commentsActive)
        assertTrue(GroupSettings.parse(channelOn.toJson()).comments)
        assertFalse("absent ⇒ default false", GroupSettings.parse(fixture("definition_with_settings.json").getJSONObject("settings")).comments)
        assertFalse("not a bool ⇒ default", GroupSettings.parse(JSONObject("""{"comments":"yes","postPolicy":"admins"}""")).comments)
        assertFalse("everyone group ⇒ inactive", channelOn.copy(postPolicy = GroupSettings.PostPolicy.EVERYONE).commentsActive)
        assertFalse("dissolved ⇒ inactive", channelOn.copy(dissolved = true).commentsActive)
    }

    @Test fun adminLogRecordsTheCommentsSwitch() {
        val g = com.oshi.messenger.data.model.Group(id = gid, name = "n", creatorPublicKey = admin,
            memberPublicKeys = listOf(admin, member), adminPublicKeys = listOf(admin))
        val ev = GroupAdminLog.diff(g, g, channelOff, channelOn, admin, 1L)
        assertEquals(listOf(GroupAdminLog.COMMENTS), ev.map { it.kind })
    }

    // ---- wire format ---------------------------------------------------------

    @Test fun fixturesDecode() {
        assertEquals(postId, GroupComments.read(fixture("comment_top_level.json")))
        assertEquals(postId, GroupComments.read(fixture("comment_reply_to_comment.json")))
        assertNull(GroupComments.read(fixture("comment_post.json")))
        val (b1, r1) = body(fixture("comment_top_level.json"))
        assertEquals("Why 40 and not 50?", b1)
        assertEquals(postId, r1)
        assertNull("top-level: no quote inside the thread", GroupComments.quoteTarget(r1, postId))
        val (b2, r2) = body(fixture("comment_reply_to_comment.json"))
        assertEquals("Vol targeting caps it at 40.", b2)
        assertEquals(c1Id, GroupComments.quoteTarget(r2, postId))
    }

    @Test fun readRejectsJunk() {
        assertNull(GroupComments.read(JSONObject("""{"commentOn":""}""")))
        assertNull(GroupComments.read(JSONObject("""{"commentOn":42}""")))
        assertNull(GroupComments.read(JSONObject("""{"commentOn":"${"x".repeat(65)}"}""")))
    }

    @Test fun encodeCarriesCommentOnAndAReplyEnvelope() {
        val env = GroupManager.buildReplyEnvelope("Why 40 and not 50?", postId, admin, "Daily note", 1_000L)
        val wire = JSONObject(GroupManager.wireGroupMessageJson(
            id = c1Id, wireGroupId = gid, senderPublicKey = member, appleTimestamp = 816861660.0,
            encryptedContentB64 = Base64.getEncoder().encodeToString(env.toByteArray()),
            iosMediaType = null, mediaFileName = null, body = env, includeBodyCompat = true,
            commentOn = postId,
        ))
        assertEquals(postId, wire.getString("commentOn"))
        val decoded = String(Base64.getDecoder().decode(wire.getString("encryptedContent")))
        assertTrue(decoded.startsWith(GroupManager.REPLY_PREFIX))
        assertEquals(postId, GroupManager.parseReplyEnvelope(decoded)!!.replyToId)
        // a plain message carries no key at all (byte-identical to before)
        val plain = JSONObject(GroupManager.wireGroupMessageJson(
            id = postId, wireGroupId = gid, senderPublicKey = admin, appleTimestamp = 1.0,
            encryptedContentB64 = "", iosMediaType = null, mediaFileName = null, body = "x", includeBodyCompat = true,
        ))
        assertFalse(plain.has("commentOn"))
    }

    // ---- receive decision (§2.12.9 table) --------------------------------------

    @Test fun memberPlainPostInChannelIsStillDropped() {
        assertEquals(Outcome.ORDINARY, verdict("comment_member_plain_post.json").outcome)
        assertFalse("ORDINARY ⇒ post policy applies", channelOn.mayPost(senderIsAdmin = false))
    }

    @Test fun validMemberCommentIsAccepted() {
        assertEquals(Outcome.ACCEPT, verdict("comment_top_level.json").outcome)
    }

    @Test fun adminReplyToACommentIsAccepted() {
        val stored = listOf(row(fixture("comment_post.json")), row(fixture("comment_top_level.json")))
        assertEquals(Outcome.ACCEPT, verdict("comment_reply_to_comment.json", stored = stored, comments = setOf(c1Id)).outcome)
    }

    @Test fun commentWhenDisabledIsDropped() {
        val d = verdict("comment_top_level.json", settings = channelOff)
        assertEquals(Outcome.DROP, d.outcome); assertEquals("comments-off", d.reason)
        // ... but an admin's commentOn message is just an ordinary (allowed) post then
        assertEquals(Outcome.ORDINARY, verdict("comment_reply_to_comment.json", settings = channelOff).outcome)
        // no settings at all (never configured) ⇒ not a channel ⇒ ordinary rules
        assertEquals(Outcome.ORDINARY, verdict("comment_top_level.json", settings = null).outcome)
    }

    @Test fun commentInAnEveryoneGroupIsAnOrdinaryMessage() {
        val d = verdict("comment_top_level.json", settings = channelOn.copy(postPolicy = GroupSettings.PostPolicy.EVERYONE))
        assertEquals(Outcome.ORDINARY, d.outcome)
    }

    @Test fun commentOnANonAdminParentIsDropped() {
        val memberPost = row(fixture("comment_post.json")).copy(senderPublicKey = member)
        val d = verdict("comment_top_level.json", stored = listOf(memberPost))
        assertEquals(Outcome.DROP, d.outcome); assertEquals("parent-not-admin", d.reason)
    }

    @Test fun commentOnACommentIsDropped() {
        val stored = listOf(row(fixture("comment_post.json")), row(fixture("comment_top_level.json")))
        val d = verdict("comment_on_a_comment.json", stored = stored, comments = setOf(c1Id))
        assertEquals(Outcome.DROP, d.outcome); assertEquals("bad-parent", d.reason)
    }

    @Test fun commentOnADeletedPostIsDropped() {
        val stored = listOf(row(fixture("comment_post.json")).copy(isDeleted = true))
        assertEquals("bad-parent", verdict("comment_top_level.json", stored = stored).reason)
    }

    @Test fun mediaCommentIsDropped() {
        val d = verdict("comment_with_media.json")
        assertEquals(Outcome.DROP, d.outcome); assertEquals("comment-kind", d.reason)
        val loc = JSONObject().put("mediaType", "")
        assertFalse(GroupComments.isTextKind(loc, GroupManager.LOCATION_PREFIX + "{}"))
        assertFalse(GroupComments.isTextKind(JSONObject(), """{"type":"media"}"""))
        assertTrue(GroupComments.isTextKind(JSONObject(), "hello"))
    }

    @Test fun bannedMembersCommentIsDropped() {
        val d = verdict("comment_top_level.json", evicted = setOf(member))
        assertEquals(Outcome.DROP, d.outcome); assertEquals("banned", d.reason)
    }

    @Test fun orphanIsHeldThenReplayed() {
        val d = verdict("comment_top_level.json", stored = emptyList())
        assertEquals(Outcome.HOLD, d.outcome)
        val buf = GroupComments.OrphanBuffer()
        buf.hold(GroupComments.Orphan(gid, postId, "raw", member, 1_000L))
        assertTrue(buf.release(gid, "0A1B2C3D-0000-4000-8000-0000000000FF", 2_000L).isEmpty())
        val out = buf.release(gid.lowercase(), postId.lowercase(), 2_000L)
        assertEquals(1, out.size)
        assertEquals(0, buf.size(gid))
        // after the post arrived, the same comment is accepted
        assertEquals(Outcome.ACCEPT, verdict("comment_top_level.json").outcome)
    }

    @Test fun orphanBufferIsBoundedAndExpires() {
        val buf = GroupComments.OrphanBuffer(perGroup = 3, ttlMs = 100L)
        repeat(5) { i -> buf.hold(GroupComments.Orphan(gid, "p$i", "r", member, 10L + i)) }
        assertEquals(3, buf.size(gid))
        assertTrue("oldest dropped first", buf.release(gid, "p0", 20L).isEmpty())
        assertTrue("expired after ttl", buf.release(gid, "p4", 1_000L).isEmpty())
    }

    @Test fun receiverFloodLimit() {
        assertEquals(Outcome.ACCEPT, verdict("comment_top_level.json", recent = GroupComments.RECEIVE_PER_MINUTE - 1).outcome)
        assertEquals("flood", verdict("comment_top_level.json", recent = GroupComments.RECEIVE_PER_MINUTE).reason)
    }

    @Test fun senderRateWindow() {
        val w = GroupComments.RateWindow(windowMs = 60_000L)
        repeat(GroupComments.SEND_PER_MINUTE) { assertTrue(w.tryAcquire("g", GroupComments.SEND_PER_MINUTE, 1_000L + it)) }
        assertFalse(w.tryAcquire("g", GroupComments.SEND_PER_MINUTE, 2_000L))
        assertTrue("window slides", w.tryAcquire("g", GroupComments.SEND_PER_MINUTE, 62_000L))
    }

    @Test fun clampKeepsSurrogatesWhole() {
        val s = "a".repeat(GroupComments.MAX_LENGTH - 1) + "😀"
        assertEquals(GroupComments.MAX_LENGTH - 1, GroupComments.clamp(s).length)
    }

    // ---- delete (§2.12.4 rule D) --------------------------------------------

    @Test fun adminMayDeleteAMembersCommentButNotAMembersPost() {
        val comment = row(fixture("comment_top_level.json"))
        val list = listOf(row(fixture("comment_post.json")), comment)
        val byAdmin = GroupManager.applyDeletionToMessages(list, c1Id, actor = admin, actorIsAdminOnComment = true)
        assertTrue(byAdmin!!.first { it.id == c1Id }.isDeleted)
        // without the comment exception (not a comment / not admin) the author rule holds
        assertNull(GroupManager.applyDeletionToMessages(list, c1Id, actor = admin, actorIsAdminOnComment = false))
        // a member can never delete the admin's post
        assertNull(GroupManager.applyDeletionToMessages(list, postId, actor = member, actorIsAdminOnComment = false))
        // the author may delete their own comment
        assertNotNull(GroupManager.applyDeletionToMessages(list, c1Id, actor = member))
        assertFalse(GroupComments.mayDelete(actorIsAuthor = false, targetIsComment = false, actorIsAdmin = true))
        assertTrue(GroupComments.mayDelete(actorIsAuthor = false, targetIsComment = true, actorIsAdmin = true))
        assertFalse(GroupComments.mayDelete(actorIsAuthor = false, targetIsComment = true, actorIsAdmin = false))
    }

    // ---- thread rows (interop run 2026-11-21) ---------------------------------

    @Test fun threadLeavesOutDeletedCommentsAndTopLevelQuotesNothing() {
        val post = row(fixture("comment_post.json"))
        val top = row(fixture("comment_top_level.json"))
        val deleted = top.copy(id = "0A1B2C3D-0000-4000-8000-0000000000FF", isDeleted = true, content = "")
        val rows = GroupComments.threadRows(listOf(top, deleted), post.id)
        assertEquals(listOf(top.id), rows.map { it.id })
        assertNull("a top-level comment quotes nothing in the thread", rows.single().replyToId)
        assertTrue(GroupComments.threadRows(listOf(deleted), post.id).isEmpty())
    }
}
