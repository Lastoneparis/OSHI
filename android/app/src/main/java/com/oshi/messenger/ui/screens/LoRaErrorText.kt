package com.oshi.messenger.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.oshi.messenger.R

/**
 * Resolve a LoRa error carried on `MeshtasticManager.lastError` into display text.
 *
 * The radio layer deliberately publishes **string-resource keys** rather than English
 * prose (a driver that hard-codes English ships an untranslatable app). Nothing was
 * resolving them, so a user who failed to set a region saw the literal text
 * `lora.error.not_connected` in a red card.
 *
 * iOS does the same resolution in `LoRaNodeError.errorDescription`
 * (OSHI/LoRaNodeProtocol.swift:210-223), which maps each case through `.localized`.
 *
 * Format: a dotted key, optionally with one argument after a `|`
 * (e.g. `lora.error.scan_failed|2`). Anything unrecognised is returned verbatim —
 * some errors are genuine free text from the OS (`Throwable.message`) and showing
 * them beats swallowing them.
 */
@Composable
fun loRaErrorText(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    val context = LocalContext.current
    val resolved = LoRaErrorResolver.resolve(value) ?: return value
    return if (resolved.arg != null) {
        context.getString(resolved.resId, resolved.arg)
    } else {
        context.getString(resolved.resId)
    }
}

/**
 * The key→resource mapping, split out of the composable so it is unit-testable on
 * the JVM. Today's lesson the hard way: a screen that compiles is not a screen that
 * renders, and a resolver that is never exercised is not a resolver that works.
 */
object LoRaErrorResolver {

    data class Resolved(val resId: Int, val arg: String?)

    /** Null when [value] is not a known key — callers show it verbatim. */
    fun resolve(value: String): Resolved? {
        val key = value.substringBefore('|')
        val arg = value.substringAfter('|', "").takeIf { it.isNotEmpty() }
        val resId = resIdFor(key) ?: return null
        return Resolved(resId, arg)
    }

    fun resIdFor(key: String): Int? = when (key) {
        "lora.error.bluetooth_off" -> R.string.lora_error_bluetooth_off
        "lora.error.bluetooth_denied" -> R.string.lora_error_bluetooth_denied
        "lora.error.not_connected" -> R.string.lora_error_not_connected
        "lora.error.timeout" -> R.string.lora_error_timeout
        "lora.error.too_large" -> R.string.lora_error_too_large
        "lora.error.unsupported" -> R.string.lora_error_unsupported
        "lora.error.raw_frames" -> R.string.lora_error_unsupported
        "lora.error.scan_failed" -> R.string.lora_error_scan_failed
        "lora.error.service_missing" -> R.string.lora_error_service_missing
        "lora.error.characteristics_missing" -> R.string.lora_error_characteristics_missing
        "lora.error.write_failed" -> R.string.lora_error_write_failed
        else -> null                  // free text from the OS — show it, don't hide it
    }
}
