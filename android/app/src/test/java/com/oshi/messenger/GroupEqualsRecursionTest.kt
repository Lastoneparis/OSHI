package com.oshi.messenger

import com.oshi.messenger.data.model.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💥 THE CRASH THAT SHIPPED IN 1.6.9.
 *
 * `Group` carried a private extension `ByteArray?.contentEquals(ByteArray)`. A local
 * declaration shadows the stdlib for every call inside the class, and a smart-cast
 * `ByteArray` still matches a `ByteArray?` receiver — so the `else` branch called the
 * function itself. Infinite recursion, StackOverflowError, process gone.
 *
 * It could not fire while `groupImage` was null (the `if` returns first), so it sat
 * harmless from v1.0.3 until groups started carrying avatars. Then it killed the app
 * on a background dispatcher every time a group definition arrived over V2:
 *
 *   dispatchV2Inbound → handleGroupUpdate → handleGroupCreated
 *     → _groups.value = …  → StateFlowImpl.updateState → ArrayList.equals
 *     → Group.equals → Group.contentEquals → … → StackOverflowError
 *
 * Twelve FATAL EXCEPTIONs were recovered from the reporting device's crash buffer.
 *
 * Every case below compares groups that HAVE an image, because that is the only
 * shape that recursed. A test that only used null images passes on the broken code.
 */
class GroupEqualsRecursionTest {

    private fun group(
        id: String = "G-1",
        name: String = "Festival crew",
        image: ByteArray? = null,
        members: List<String> = listOf("A", "B"),
        updatedAt: Long = 1_756_000_000_000L
    ) = Group(
        id = id,
        name = name,
        description = "",
        creatorPublicKey = "A",
        memberPublicKeys = members,
        adminPublicKeys = listOf("A"),
        groupImage = image,
        createdAt = 1_756_000_000_000L,
        updatedAt = updatedAt
    )

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    @Test
    fun `two identical groups WITH an avatar compare equal instead of overflowing`() {
        assertEquals(group(image = png), group(image = png.copyOf()))
    }

    @Test
    fun `a group with an avatar differs from the same group with another avatar`() {
        assertNotEquals(group(image = png), group(image = byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `an avatar added to a group makes it unequal to its avatarless self`() {
        assertNotEquals(group(image = null), group(image = png))
    }

    @Test
    fun `a null avatar still equals a null avatar`() {
        assertEquals(group(image = null), group(image = null))
    }

    /**
     * The real trigger: `StateFlow` compares LISTS of groups, so the recursion was
     * reached through `ArrayList.equals` → `Objects.equals` → `Group.equals`, exactly
     * as `StateFlowImpl.updateState` does on every groups emission.
     */
    @Test
    fun `a list of avatar-carrying groups can be compared, as StateFlow does`() {
        val a = listOf(group(id = "G-1", image = png), group(id = "G-2", image = png))
        val b = listOf(group(id = "G-1", image = png.copyOf()), group(id = "G-2", image = png.copyOf()))
        assertEquals("this is the comparison StateFlowImpl.updateState performs", a, b)
        assertTrue(a == b)
    }

    @Test
    fun `hashCode agrees with equals for avatar-carrying groups`() {
        assertEquals(group(image = png).hashCode(), group(image = png.copyOf()).hashCode())
    }
}
