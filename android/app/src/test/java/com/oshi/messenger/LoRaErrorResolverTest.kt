package com.oshi.messenger

import com.oshi.messenger.ui.screens.LoRaErrorResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The LoRa driver publishes string-RESOURCE KEYS on `lastError` / `status.attention`
 * rather than English prose, so the radio layer stays translatable. Nothing resolved
 * them, so a user who failed to set a region saw the literal text
 * `lora.error.not_connected` in a red card.
 *
 * These tests pin every key the driver can actually emit. Grep for the emitters:
 *   MeshtasticManager.kt  — scan_failed, service_missing, characteristics_missing,
 *                           write_failed, bluetooth_denied (via status.attention)
 *   LoRaMeshNode.kt       — LoRaSendRefusal.stringKey
 *   MeshtasticNodeAdapter.kt — raw_frames
 * A key added there without a case here silently regresses to raw text on screen.
 */
class LoRaErrorResolverTest {

    /** Every key the driver emits must resolve. This is the regression that bit us. */
    @Test
    fun `every key the driver emits resolves to a resource`() {
        val emitted = listOf(
            "lora.error.bluetooth_off",
            "lora.error.bluetooth_denied",
            "lora.error.not_connected",
            "lora.error.timeout",
            "lora.error.too_large",
            "lora.error.unsupported",
            "lora.error.raw_frames",
            "lora.error.scan_failed",
            "lora.error.service_missing",
            "lora.error.characteristics_missing",
            "lora.error.write_failed",
        )
        for (key in emitted) {
            assertNotNull("driver key `$key` renders as raw text on screen", LoRaErrorResolver.resIdFor(key))
        }
    }

    @Test
    fun `parameterised key splits its argument`() {
        val r = LoRaErrorResolver.resolve("lora.error.scan_failed|2")
        assertNotNull(r)
        assertEquals("2", r!!.arg)
        assertEquals(LoRaErrorResolver.resIdFor("lora.error.scan_failed"), r.resId)
    }

    @Test
    fun `plain key has no argument`() {
        assertNull(LoRaErrorResolver.resolve("lora.error.not_connected")!!.arg)
    }

    /**
     * Free text from the OS (`Throwable.message`) must pass through, not be swallowed:
     * showing "GATT error 133" beats showing nothing at all.
     */
    @Test
    fun `unknown text is not a key and passes through`() {
        assertNull(LoRaErrorResolver.resolve("GATT failure 133"))
        assertNull(LoRaErrorResolver.resolve("lora.error.does_not_exist"))
    }

    /** An empty arg after the separator must not become the string "". */
    @Test
    fun `trailing separator yields no argument`() {
        assertNull(LoRaErrorResolver.resolve("lora.error.write_failed|")!!.arg)
    }

    /** Negative BLE status codes are real (e.g. -1); the split must not mangle them. */
    @Test
    fun `negative status code survives the split`() {
        assertEquals("-1", LoRaErrorResolver.resolve("lora.error.write_failed|-1")!!.arg)
    }
}
