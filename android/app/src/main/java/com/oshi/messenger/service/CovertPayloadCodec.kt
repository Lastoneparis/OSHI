package com.oshi.messenger.service

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Carrier encoding only. Callers must authenticate/encrypt content before encoding it. */
internal object CovertPayloadCodec {
    fun validDnsName(name: String): Boolean = name.isNotEmpty() && name.length <= 253 &&
        name.split('.').all { label ->
            label.length in 1..63 && !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }

    fun dnsName(payload: String, baseDomain: String): String {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..31) { "DNS carrier supports 1–31 UTF-8 bytes; payload was not sent" }
        require(validDnsName(baseDomain)) { "Invalid DNS base domain" }
        val name = bytes.joinToString("") { "%02x".format(it.toInt() and 255) } + ".covert." + baseDomain
        require(validDnsName(name)) { "DNS name exceeds 253 characters" }
        return name
    }

    fun decodeHex(hex: String, maxBytes: Int): String? {
        if (hex.length > maxBytes * 2 || hex.length % 2 != 0 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return runCatching {
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    fun decodeDnsName(name: String): String? {
        if (!validDnsName(name)) return null
        val labels = name.split('.')
        if (labels.size < 3 || !labels[1].equals("covert", ignoreCase = true)) return null
        return decodeHex(labels[0], 31)
    }

    fun imagePayloadFits(pixelCount: Long, payloadBytes: Int): Boolean =
        payloadBytes in 1..1_000_000 && pixelCount >= 0 && pixelCount <= Int.MAX_VALUE &&
            32L + payloadBytes.toLong() * 8 <= pixelCount * 3
}
