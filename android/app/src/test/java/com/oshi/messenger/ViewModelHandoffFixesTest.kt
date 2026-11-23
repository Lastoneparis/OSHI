package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMember
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.ui.viewmodel.CreateGroupRequest
import com.oshi.messenger.ui.viewmodel.CreateGroupState
import com.oshi.messenger.ui.viewmodel.GroupChatViewModel
import com.oshi.messenger.util.PrefsWipePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three ViewModel hand-overs from the 2026-08-23 fix pass
 * (`parity/verify/2026-08-23-messaging.md` "FIXED (groups)",
 * `parity/verify/2026-08-23-crypto.md` §3c).
 *
 * Every input below is a hand-written literal; nothing is built by the code
 * under test.
 */
class ViewModelHandoffFixesTest {

    // ─────────────────────────────────────────────────────────────────────
    // G-4 — the emoji avatar the picker wrote and nobody read
    // ─────────────────────────────────────────────────────────────────────

    private fun state(
        avatar: String = "🎉",
        type: GroupType = GroupType.COLLABORATIVE
    ) = CreateGroupState(
        groupName = "Team",
        selectedGroupType = type,
        selectedAvatar = avatar,
        addedMembers = listOf(
            GroupMember(publicKey = "AAAAkeyone", name = "Alice"),
            GroupMember(publicKey = "BBBBkeytwo", name = "Bob")
        )
    )

    /**
     * The whole of G-4: `selectAvatar` updated the state and `createGroup` did not
     * pass it, so every group Android created went out with `avatar = null` and
     * every iPhone member saw the default glyph.
     */
    @Test
    fun `the picked avatar reaches the create-group arguments`() {
        assertEquals("🎉", CreateGroupRequest.of(state()).avatar)
    }

    /** Blank is not an avatar — an empty `avatar` clears it on iOS. */
    @Test
    fun `a blank avatar is sent as absent, not as an empty string`() {
        assertNull(CreateGroupRequest.of(state(avatar = "")).avatar)
        assertNull(CreateGroupRequest.of(state(avatar = "   ")).avatar)
    }

    /** The rest of the mapping, so a future edit cannot quietly drop another field. */
    @Test
    fun `name, roster and type all come from the sheet`() {
        val request = CreateGroupRequest.of(state())
        assertEquals("Team", request.name)
        assertEquals(listOf("AAAAkeyone", "BBBBkeytwo"), request.memberPublicKeys)
        assertEquals(GroupType.COLLABORATIVE, request.groupType)
        assertEquals(false, request.isPublic)
    }

    @Test
    fun `a public group sets the public flag as well as the type`() {
        val request = CreateGroupRequest.of(state(type = GroupType.PUBLIC))
        assertEquals(GroupType.PUBLIC, request.groupType)
        assertEquals(true, request.isPublic)
    }

    // ─────────────────────────────────────────────────────────────────────
    // G-7 — the caption on group media
    // ─────────────────────────────────────────────────────────────────────

    /**
     * `content` on a group media message is the caption: it becomes the media
     * JSON's `caption` and the message's `plaintextContent`, the only key iOS
     * reads a caption from (`GroupViews.swift:2617`).
     */
    @Test
    fun `a caption survives normalization`() {
        assertEquals("look at this", GroupChatViewModel.normalizeCaption("  look at this  "))
    }

    /**
     * `GroupManager` emits `plaintextContent` on `content.isNotEmpty()` alone, so a
     * whitespace-only caption would ship an empty caption row on both platforms.
     */
    @Test
    fun `a whitespace-only caption is no caption`() {
        assertEquals("", GroupChatViewModel.normalizeCaption("   "))
        assertEquals("", GroupChatViewModel.normalizeCaption("\n\t"))
        assertEquals("", GroupChatViewModel.normalizeCaption(""))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Crypto §3c — account deletion must take the key material with it
    // ─────────────────────────────────────────────────────────────────────

    /**
     * What `SettingsViewModel.wipeOwnedSharedPrefs()` does, over a hand-written
     * `shared_prefs` listing: enumerate the directory, strip `.xml`, keep only what
     * we own. Composing the two public [PrefsWipePolicy] entry points is the whole
     * of the selection, so this fails the moment a keep-prefix is widened over a
     * store that holds key material.
     */
    private fun wipeTargets(dirListing: List<String>): Set<String> =
        dirListing.mapNotNull { PrefsWipePolicy.prefsNameOf(it) }
            .filter { PrefsWipePolicy.shouldWipe(it) }
            .toSet()

    /**
     * The six stores the crypto audit found surviving "Delete account" — root keys,
     * chain keys, skipped message keys, X3DH prekey privates and the verification
     * records. Each one is a real file name taken from its declaring class.
     */
    @Test
    fun `an account wipe erases every store that holds key material`() {
        val listing = listOf(
            "v2_sessions.xml",          // network/v2/V2SessionStore.kt:45  — RK, CKs, CKr, DHs, MKSKIPPED
            "v2_sessions_plain.xml",    // network/v2/V2SessionStore.kt:52  — same, unencrypted fallback
            "v2_prekeys.xml",           // network/v2/V2PrekeyStore.kt:26   — SPK private + every OPK private
            "v2_router.xml",            // network/v2/V2MessageRouter.kt:96 — relay cursor + retry map
            "v2_config.xml",            // network/v2/V2Config.kt:37        — rollout gate
            "oshi_ratchet.xml",         // network/encryption/DoubleRatchet.kt:612
            "oshi_ratchet_encrypted.xml", // network/encryption/DoubleRatchet.kt:605
            "safety_numbers.xml",       // service/SafetyNumberManager.kt:27
            "oshi_secure_keys.xml",
            "oshi_identity_backup.xml",
            "com.google.firebase.messaging.xml",
            "osmdroid.xml",
            "some.dir"
        )
        val wiped = wipeTargets(listing)
        val mustGo = listOf(
            "v2_sessions", "v2_sessions_plain", "v2_prekeys", "v2_router", "v2_config",
            "oshi_ratchet", "oshi_ratchet_encrypted", "safety_numbers",
            "oshi_secure_keys", "oshi_identity_backup"
        )
        for (name in mustGo) {
            assertTrue("'$name' survived the account wipe", wiped.contains(name))
        }
        assertEquals(mustGo.size, wiped.size)
    }

    /** Clearing Firebase's own bookkeeping is how the NEXT identity loses push. */
    @Test
    fun `an account wipe leaves stores we do not own alone`() {
        val wiped = wipeTargets(
            listOf(
                "com.google.android.gms.appid.xml",
                "com.google.firebase.messaging.xml",
                "androidx.security.crypto.xml",
                "WebViewChromiumPrefs.xml",
                "osmdroid.xml"
            )
        )
        assertTrue("nothing third-party may be cleared, got $wiped", wiped.isEmpty())
    }

    /** Only `.xml` files are preference stores; a directory or a backup is not. */
    @Test
    fun `only xml entries in shared_prefs are treated as preference files`() {
        assertEquals(
            setOf("oshi_ratchet"),
            wipeTargets(listOf("oshi_ratchet.xml", "oshi_ratchet.bak", "cache", "some.dir"))
        )
    }
}
