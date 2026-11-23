package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__
 *
 * What the owner's abuse reports of 2026-11-06 actually said, read off the
 * server (`/var/oshi/reports/reports-2026-11-06.jsonl`): four reports from ONE
 * reporter in 33 minutes, Android 1.6.75 (116), every single one
 * `"subjectType":"group"` — and three of the four carrying a PERSON'S NAME in
 * the free-text field: "Danielle Bespoke", "Sarah", "Detee now". Two of those
 * were the same group id, 14 seconds apart, followed 25 seconds later by a
 * `harassment` report on that same group with no details at all.
 *
 * That is not four complaints about three rooms. That is one person trying four
 * times to flag individuals, through the only subject the product offered.
 *
 * `MemberActionsDialog` had: reply in private, add to contacts, copy key,
 * promote, demote, and remove — the last admins-only. No block. No report. So a
 * non-admin being spammed by one member could do nothing to that member, and
 * typed their name into a comment box about the room instead. Moderation
 * received a group id where a public key should have been.
 *
 * The enforcement was never the missing part: group receive has dropped blocked
 * members' messages since __BLOCKED_MEMBER_DROP_2026_09_24__, an owner decision.
 * Enforced but unreachable is not shipped.
 */
class GroupMemberModerationTest {

    private val screen: String by lazy {
        SourceText.read("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt")
    }
    private val viewModel: String by lazy {
        SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/GroupChatViewModel.kt")
    }
    private val groupManager: String by lazy {
        SourceText.read("src/main/java/com/oshi/messenger/service/GroupManager.kt")
    }
    private val repository: String by lazy {
        SourceText.read("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
    }

    // MARK: the menu

    @Test
    fun the_member_dialog_offers_block_and_report() {
        val dialog = SourceText.between(screen, "private fun MemberActionsDialog(", "confirmButton = {}")
        assertTrue("Block is missing from the member dialog — a non-admin has no action against a spamming member",
            dialog.contains("R.string.group_block_member"))
        assertTrue("Unblock is missing — a block with no way back is a trap",
            dialog.contains("R.string.group_unblock_member"))
        assertTrue("Report is missing — the only reachable report would again be the whole room",
            dialog.contains("R.string.group_report_member"))
        // The pre-existing entries must survive the addition.
        assertTrue(dialog.contains("R.string.group_reply_in_private"))
        assertTrue(dialog.contains("R.string.group_remove_member"))
    }

    @Test
    fun block_and_report_are_available_to_non_admins() {
        val dialog = SourceText.between(screen, "private fun MemberActionsDialog(", "confirmButton = {}")
        // Remove sits behind `isMeAdmin` / `onRemoveMember != null`, deliberately.
        // Block and Report must NOT: the reporter on 06/11 was not an admin of
        // the groups they were being spammed in, which is the ordinary case.
        val blockAt = dialog.indexOf("R.string.group_block_member")
        val adminGateAt = dialog.indexOf("if (isMeAdmin && !isMemberCreator)")
        assertTrue("sanity: both markers present", blockAt >= 0 && adminGateAt >= 0)
        assertTrue("Block is inside the admin-only section — the people who need it are not admins",
            blockAt < adminGateAt)

        val reportAt = dialog.indexOf("R.string.group_report_member")
        assertTrue("Report is inside the admin-only section", reportAt in 0 until adminGateAt)
    }

    @Test
    fun the_new_callbacks_are_required_not_defaulted() {
        val signature = SourceText.between(screen, "private fun MemberActionsDialog(", ") {")
        for (cb in listOf("onBlockMember", "onUnblockMember", "onReportMember")) {
            assertTrue("$cb is not a parameter", signature.contains("$cb: () -> Unit"))
            assertFalse(
                "$cb has a no-op default; a menu entry wired to {} is a dead button that no " +
                    "runtime test can tell from a working one",
                signature.contains("$cb: () -> Unit = {}")
            )
        }
    }

    @Test
    fun the_call_site_wires_them_to_real_work() {
        val call = SourceText.window(screen, "MemberActionsDialog(", 3000)
        assertTrue("Block is not wired to the view model", call.contains("viewModel.blockMember(selKey)"))
        assertTrue("Unblock is not wired", call.contains("viewModel.unblockMember(selKey)"))
        assertTrue("the dialog must know which state to show", call.contains("isMemberBlocked = viewModel.isMemberBlocked(selKey)"))
        // Report defers to its own dialog, so the key has to outlive the
        // selection being cleared.
        assertTrue("the report key is not kept past the dialog closing", call.contains("reportMemberKey = selKey"))
    }

    // MARK: the subject

    @Test
    fun a_member_is_reported_as_a_contact_not_as_a_group() {
        val fn = SourceText.between(viewModel, "fun reportMember(", "fun hasReportedMember")
        assertTrue("reportMember must file a CONTACT: the point of the change is that moderation " +
            "receives a public key it can act on, not a room id",
            fn.contains("ReportSubject.CONTACT"))
        assertFalse("reportMember files a GROUP — that is the bug, not the fix",
            fn.contains("ReportSubject.GROUP"))
        assertTrue("the member's key must be the subject", fn.contains("subjectId = memberPublicKey"))

        // And the group report still exists: this adds a subject, it does not
        // replace one. A room really can be the problem.
        assertTrue(viewModel.contains("fun reportGroup("))
        assertTrue(SourceText.between(viewModel, "fun reportGroup(", "fun hasReportedGroup")
            .contains("ReportSubject.GROUP"))
    }

    @Test
    fun blocking_a_member_does_not_depend_on_them_being_a_contact() {
        // A group member usually is NOT in the contact list. `contactDao.setBlocked`
        // alone would silently do nothing for them, which is the dead-button shape
        // again — this time invisible, because the menu entry would respond.
        val fn = SourceText.between(viewModel, "fun blockMember(", "fun unblockMember(")
        assertTrue("blockMember must go through the repository, which also writes BlockedKeysStore",
            fn.contains("messageRepository.blockContact(memberPublicKey)"))

        val repoFn = SourceText.between(repository, "suspend fun blockContact(", "suspend fun unblockContact(")
        assertTrue("the durable store is what makes a non-contact block stick",
            repoFn.contains("BlockedKeysStore.get(context).block(publicKey, name)"))
        assertTrue(repoFn.contains("blockedKeyCache.add"))
    }

    // MARK: the premise — blocking a member actually silences them

    @Test
    fun a_blocked_members_group_messages_are_still_dropped_at_receive() {
        // If this gate ever goes, the menu entry added above becomes a button
        // that reports success and changes nothing the user can see.
        assertTrue("BlockedMemberGate is gone — then blocking a member no longer silences them in a group",
            groupManager.contains("fun drops("))
        val gate = SourceText.between(groupManager, "fun drops(", "\n    }")
        assertTrue("the gate must consult the block list", gate.contains("isBlocked(sender)"))
        assertTrue("and group receive must call it",
            groupManager.contains("BlockedMemberGate.drops(senderPublicKey"))
    }

    // MARK: the strings exist, in the default locale

    @Test
    fun the_new_strings_are_declared() {
        val strings = SourceText.read("src/main/res/values/strings.xml")
        for (name in listOf(
            "group_block_member", "group_unblock_member", "group_report_member",
            "group_member_blocked", "group_member_unblocked",
            "report_member_dialog_title", "report_member_already_filed",
        )) {
            assertTrue("missing string $name — Compose would render the resource id as a crash, " +
                "not as English", strings.contains("name=\"$name\""))
        }
    }

    @Test
    fun the_shared_source_reader_strips_prose_but_not_urls() {
        val stripped = SourceText.stripComments(
            """
            // a note mentioning group_block_member
            /* and a block mentioning ReportSubject.GROUP */
            viewModel.blockMember(selKey)
            val site = "https://oshi-messenger.com/report"
            """.trimIndent()
        )
        assertFalse("prose survived: $stripped", stripped.contains("group_block_member"))
        assertFalse(stripped.contains("ReportSubject.GROUP"))
        assertTrue("code was eaten: $stripped", stripped.contains("viewModel.blockMember(selKey)"))
        assertTrue("a URL is not a comment", stripped.contains("https://oshi-messenger.com/report"))
        assertEquals("line numbering must survive", 4, stripped.lines().size)
    }
}
