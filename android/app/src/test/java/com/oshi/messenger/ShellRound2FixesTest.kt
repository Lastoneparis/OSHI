package com.oshi.messenger

import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupConversation
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.screens.OnboardingPage
import com.oshi.messenger.ui.screens.groupDocumentLabel
import com.oshi.messenger.ui.viewmodel.GroupsViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 2, shell team: the group media `fileName`, its Room edge, the localized group
 * search and the onboarding page order.
 *
 * Every expectation here is written by hand. Nothing is produced by the function it
 * checks — the media JSON is asserted key by key against iOS's own builder
 * (`GroupMessaging.swift:2534-2546`), and the inbound half is a literal iOS payload.
 */
class ShellRound2FixesTest {

    private val photoB64 = "AAECAwQ="

    // =====================================================================
    // The media JSON — what an iPhone decodes
    // =====================================================================

    @Test
    fun `a document carries its name as fileName, exactly as iOS emits it`() {
        val json = JSONObject(
            GroupManager.buildGroupMediaJson(
                iosMediaType = "document",
                contentBase64 = photoB64,
                size = 5,
                caption = "",
                fileName = "quarterly report.pdf"
            )
        )
        assertEquals("media", json.getString("type"))
        assertEquals("document", json.getString("mediaType"))
        assertEquals(photoB64, json.getString("content"))
        assertEquals(5, json.getInt("size"))
        assertEquals("quarterly report.pdf", json.getString("fileName"))
        // No caption was typed, so no caption row is shipped. iOS draws `plaintextContent`
        // under the attachment and an empty one is a blank line.
        assertFalse("an empty caption must not be emitted", json.has("caption"))
    }

    @Test
    fun `a photo with no file name omits the key entirely`() {
        val json = JSONObject(
            GroupManager.buildGroupMediaJson(
                iosMediaType = "photo",
                contentBase64 = photoB64,
                size = 5
            )
        )
        assertFalse(
            "fileName present with no name makes iOS render an empty document label",
            json.has("fileName")
        )
        assertEquals(setOf("type", "mediaType", "content", "size"), json.keys().asSequence().toSet())
    }

    @Test
    fun `a blank file name is no file name`() {
        val json = JSONObject(
            GroupManager.buildGroupMediaJson(
                iosMediaType = "document",
                contentBase64 = photoB64,
                size = 5,
                fileName = ""
            )
        )
        assertFalse(json.has("fileName"))
    }

    @Test
    fun `a caption and a file name coexist`() {
        val json = JSONObject(
            GroupManager.buildGroupMediaJson(
                iosMediaType = "photo",
                contentBase64 = photoB64,
                size = 5,
                caption = "at the beach",
                fileName = "IMG_0042.jpg"
            )
        )
        assertEquals("at the beach", json.getString("caption"))
        assertEquals("IMG_0042.jpg", json.getString("fileName"))
    }

    @Test
    fun `the media blob carries no date field`() {
        // iOS reads several dates through a bare `decodeIfPresent(Date.self)` which throws
        // on a number and sinks the whole strict decode. This payload must stay date-free.
        val json = JSONObject(
            GroupManager.buildGroupMediaJson("video", photoB64, 5, "hi", "clip.mp4")
        )
        val dateish = json.keys().asSequence().filter {
            it.contains("At", ignoreCase = false) || it.contains("date", ignoreCase = true) ||
                it.contains("timestamp", ignoreCase = true)
        }.toList()
        assertEquals(emptyList<String>(), dateish)
    }

    /** The inbound half: a literal iOS media blob, read the way the group receiver reads it. */
    @Test
    fun `an iOS media blob exposes fileName under that exact key`() {
        val fromIphone = """
            {"type":"media","mediaType":"document","content":"$photoB64","size":5,"fileName":"contract.pdf"}
        """.trimIndent()
        val json = JSONObject(fromIphone)
        assertEquals("contract.pdf", json.optString("fileName", "").takeIf { it.isNotEmpty() })
        assertNull(JSONObject("""{"type":"media","mediaType":"photo","content":"$photoB64","size":5}""")
            .optString("fileName", "").takeIf { it.isNotEmpty() })
    }

    // =====================================================================
    // The document bubble's label
    // =====================================================================

    @Test
    fun `the bubble prefers mediaFileName`() {
        assertEquals("notes.txt", groupDocumentLabel("notes.txt", ""))
        assertEquals("notes.txt", groupDocumentLabel("notes.txt", "[DOCUMENT] stale.txt"))
    }

    @Test
    fun `the legacy body still labels rows already on disk`() {
        assertEquals("old.pdf", groupDocumentLabel(null, "[DOCUMENT] old.pdf"))
        assertEquals("old.pdf", groupDocumentLabel("  ", "[DOCUMENT] old.pdf"))
    }

    @Test
    fun `nothing to show yields null, not an empty label`() {
        assertNull(groupDocumentLabel(null, ""))
        assertNull(groupDocumentLabel(null, "[DOCUMENT]"))
        assertNull(groupDocumentLabel("", "   "))
    }

    // =====================================================================
    // The Room edge
    // =====================================================================

    private fun sourceOf(path: String): String {
        val f = File(path)
        assertTrue("not found: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `the mediaFileName column has its own migration edge`() {
        val db = sourceOf("src/main/java/com/oshi/messenger/data/local/AppDatabase.kt")
        val model = sourceOf("src/main/java/com/oshi/messenger/data/model/Group.kt")

        assertTrue(
            "GroupMessage must declare mediaFileName",
            Regex("""val mediaFileName: String\? = null""").containsMatchIn(model)
        )
        // The column must be added to `group_messages`, not to `groups` — the two tables are
        // one word apart and Room would only complain at runtime, on the user's device.
        val edge = Regex("""Migration\(13, 14\) \{([\s\S]*?)\n        \}""")
            .find(db)?.groupValues?.get(1)
            ?: error("no v13 → v14 edge in AppDatabase.kt")
        assertTrue("the v13 → v14 edge must add mediaFileName", edge.contains("mediaFileName"))
        assertTrue("…to the group_messages table", edge.contains("group_messages"))
        assertFalse("…and not to groups", Regex("""["'`]groups["'`]""").containsMatchIn(edge))
    }

    // =====================================================================
    // Group search, in the user's language
    // =====================================================================

    private fun group(name: String, type: GroupType) = GroupConversation(
        group = Group(
            id = name,
            name = name,
            creatorPublicKey = "AAAA",
            memberPublicKeys = listOf("AAAA"),
            adminPublicKeys = listOf("AAAA"),
            createdAt = 1_786_622_400_000L,
            groupType = type
        ),
        lastMessage = null,
        unreadCount = 0,
        memberNames = emptyMap()
    )

    /** Stands in for `R.string.group_type_*` resolved in a non-English locale. */
    private val french: (GroupType) -> String = { type ->
        when (type) {
            GroupType.ADMIN_ONLY -> "Admins uniquement"
            GroupType.COLLABORATIVE -> "Collaboratif"
            GroupType.PUBLIC -> "Public ouvert"
        }
    }

    @Test
    fun `searching a group type uses the localized label`() {
        val all = listOf(group("Randonnée", GroupType.PUBLIC), group("Bureau", GroupType.ADMIN_ONLY))
        val hits = GroupsViewModel.filterGroups(all, "uniquement", null, french)
        assertEquals(listOf("Bureau"), hits.map { it.group.name })
    }

    @Test
    fun `the group name still matches`() {
        val all = listOf(group("Randonnée", GroupType.PUBLIC), group("Bureau", GroupType.ADMIN_ONLY))
        assertEquals(
            listOf("Randonnée"),
            GroupsViewModel.filterGroups(all, "rando", null, french).map { it.group.name }
        )
    }

    @Test
    fun `the type filter is independent of the label`() {
        val all = listOf(group("Randonnée", GroupType.PUBLIC), group("Bureau", GroupType.ADMIN_ONLY))
        assertEquals(
            listOf("Randonnée"),
            GroupsViewModel.filterGroups(all, "", GroupType.PUBLIC, french).map { it.group.name }
        )
    }

    @Test
    fun `a legacy row with no stored type is searched by its derived type`() {
        val legacy = GroupConversation(
            group = Group(
                id = "legacy",
                name = "Ancien",
                creatorPublicKey = "AAAA",
                memberPublicKeys = listOf("AAAA"),
                adminPublicKeys = listOf("AAAA"),
                isPublic = true,
                groupType = null
            ),
            lastMessage = null,
            unreadCount = 0,
            memberNames = emptyMap()
        )
        assertEquals(
            listOf("Ancien"),
            GroupsViewModel.filterGroups(listOf(legacy), "ouvert", null, french).map { it.group.name }
        )
    }

    @Test
    fun `newest conversation first`() {
        fun withMessage(name: String, ts: Long) = group(name, GroupType.PUBLIC).let {
            it.copy(
                lastMessage = GroupMessage(
                    id = name,
                    groupId = name,
                    senderPublicKey = "AAAA",
                    senderName = "A",
                    content = "hi",
                    timestamp = ts,
                    isFromMe = false
                )
            )
        }
        val ordered = GroupsViewModel.filterGroups(
            listOf(withMessage("older", 1_000L), withMessage("newer", 2_000L)),
            "", null, french
        )
        assertEquals(listOf("newer", "older"), ordered.map { it.group.name })
    }

    // =====================================================================
    // Onboarding
    // =====================================================================

    @Test
    fun `onboarding shows every page iOS shows, in iOS's order`() {
        assertEquals(
            listOf(
                OnboardingPage.WELCOME,
                OnboardingPage.SEND_MESSAGE,
                OnboardingPage.CONNECTIVITY,
                OnboardingPage.FEATURES,
                OnboardingPage.GOOD_CONDUCT,
                OnboardingPage.DISCLAIMER,
                OnboardingPage.IDENTITY
            ),
            OnboardingPage.ORDER
        )
    }

    /**
     * The shape that broke four screens: a non-scrolling `Column(fillMaxSize)` with
     * `SpaceBetween` holding more fixed dp than a landscape phone has, which pushes the
     * primary action off the bottom edge with no way to reach it.
     */
    @Test
    fun `the onboarding page region scrolls and the buttons sit outside it`() {
        val src = sourceOf("src/main/java/com/oshi/messenger/ui/screens/OnboardingScreen.kt")
        assertTrue("the page region must scroll", src.contains("verticalScroll(rememberScrollState())"))
        assertTrue("…in a weighted region", src.contains(".weight(1f)"))
        assertFalse(
            "a vertically SpaceBetween column strands the primary action on a short window",
            src.contains("verticalArrangement = Arrangement.SpaceBetween")
        )
    }
}
