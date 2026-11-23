package com.oshi.messenger

import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 🔄 WHAT A `StateFlow` IS ALLOWED TO CONFLATE.
 *
 * `ChatViewModel.messages` and the contact flows are `StateFlow`s, and
 * `StateFlowImpl.updateState` drops an emission whose value is `==` to the last
 * one. `List.equals` is element-wise, so a data class that compares only its id
 * makes a list of CHANGED rows compare EQUAL to the previous list — the UI never
 * redraws.
 *
 * `Message` and `Contact` both did exactly that. The visible symptoms were:
 * delivery ticks and read receipts appearing late or never, an edit not showing
 * until the peer replied, a failed send still looking sent, and — the one that
 * cost the most time — a peer's avatar landing in the database and never being
 * drawn, which made a correct write look like a broken feature.
 *
 * The same defect was found and fixed for `GroupMessage` first (`Group.kt`); these
 * are the 1:1 halves that were missed.
 *
 * Every case below changes ONE field and asserts the rows differ. A test that
 * changed two fields at once would pass even if one of them were dropped from
 * `equals` — that is precisely how the group version was found to be vacuous.
 */
class StateFlowEqualityTest {

    private fun msg(
        id: String = "m-1",
        content: String = "hello",
        isRead: Boolean = false,
        isDelivered: Boolean = false,
        isDeleted: Boolean = false,
        isDeletedForEveryone: Boolean = false,
        isFailed: Boolean = false,
        editedContent: String? = null,
        editedAt: Long? = null,
        viewOnceOpened: Boolean = false,
        deliveryMethod: DeliveryMethod = DeliveryMethod.VPS
    ) = Message(
        id = id,
        senderPublicKey = "A",
        recipientPublicKey = "B",
        content = content,
        timestamp = 1_756_000_000_000L,
        isFromMe = true,
        isRead = isRead,
        isDelivered = isDelivered,
        deliveryMethod = deliveryMethod,
        isDeleted = isDeleted,
        editedAt = editedAt,
        isDeletedForEveryone = isDeletedForEveryone,
        editedContent = editedContent,
        viewOnceOpened = viewOnceOpened,
        isFailed = isFailed
    )

    // ------------------------------------------------------------------ Message

    @Test
    fun `the delivery tick must not be conflated away`() =
        assertNotEquals(msg(isDelivered = false), msg(isDelivered = true))

    @Test
    fun `a read receipt must not be conflated away`() =
        assertNotEquals(msg(isRead = false), msg(isRead = true))

    @Test
    fun `an edit must not be conflated away`() {
        assertNotEquals(msg(editedContent = null), msg(editedContent = "fixed"))
        assertNotEquals(msg(editedAt = null), msg(editedAt = 12L))
    }

    @Test
    fun `delete-for-everyone must not be conflated away`() {
        assertNotEquals(msg(isDeleted = false), msg(isDeleted = true))
        assertNotEquals(msg(isDeletedForEveryone = false), msg(isDeletedForEveryone = true))
    }

    @Test
    fun `a failed send must not keep looking sent`() =
        assertNotEquals(msg(isFailed = false), msg(isFailed = true))

    @Test
    fun `opening a view-once photo must not be conflated away`() =
        assertNotEquals(msg(viewOnceOpened = false), msg(viewOnceOpened = true))

    @Test
    fun `the transport badge must not be conflated away`() =
        assertNotEquals(msg(deliveryMethod = DeliveryMethod.VPS), msg(deliveryMethod = DeliveryMethod.MESH))

    @Test
    fun `two genuinely identical messages still compare equal`() =
        assertEquals(msg(), msg())

    @Test
    fun `hashCode stays keyed on the id so a list key never moves`() =
        assertEquals(msg(isDelivered = false).hashCode(), msg(isDelivered = true).hashCode())

    // ------------------------------------------------------------------ Contact

    private val png = byteArrayOf(1, 2, 3, 4)

    @Test
    fun `an avatar arriving must not be conflated away`() {
        // The one that made a correct DAO write look like a broken feature.
        assertNotEquals(
            Contact(publicKey = "K", alias = "Hugo", profilePicture = null),
            Contact(publicKey = "K", alias = "Hugo", profilePicture = png)
        )
    }

    @Test
    fun `a CHANGED avatar must not be conflated away`() =
        assertNotEquals(
            Contact(publicKey = "K", alias = "Hugo", profilePicture = png),
            Contact(publicKey = "K", alias = "Hugo", profilePicture = byteArrayOf(9, 9))
        )

    @Test
    fun `a rename must not be conflated away`() =
        assertNotEquals(
            Contact(publicKey = "K", alias = "Hugo"),
            Contact(publicKey = "K", alias = "Hugo Moriceau")
        )

    @Test
    fun `blocking someone must not be conflated away`() =
        assertNotEquals(
            Contact(publicKey = "K", alias = "Hugo", isBlocked = false),
            Contact(publicKey = "K", alias = "Hugo", isBlocked = true)
        )

    @Test
    fun `two identical contacts still compare equal, avatars included`() =
        assertEquals(
            Contact(publicKey = "K", alias = "Hugo", profilePicture = png),
            Contact(publicKey = "K", alias = "Hugo", profilePicture = png.copyOf())
        )

    @Test
    fun `contact hashCode stays keyed on the public key`() =
        assertEquals(
            Contact(publicKey = "K", alias = "Hugo").hashCode(),
            Contact(publicKey = "K", alias = "Someone Else", profilePicture = png).hashCode()
        )
}
