package com.oshi.messenger.service

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/**
 * V1 lossless wrapper around an already encrypted, signed V2 request/response.
 *
 * This is intentionally not presented as steganography: image LSB, zero-width
 * text and cookie carriers were removed because they are fingerprintable.
 */
object CoverCodec {
    const val MAX = 4 * 1024 * 1024
    private const val MAX_BASE64 = ((MAX + 2) / 3) * 4
    /** Shared with iOS/macOS and `ServerVPS/v2/cover_transport.js`. */
    private val PADDING_BUCKETS = intArrayOf(4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024, MAX)
    private val PROFILES = arrayOf("event", "batch")
    /** Strict relay aliases, shared with iOS/macOS and `cover_transport.js`. */
    internal val ENDPOINT_PATHS = arrayOf("/v2/cover", "/v2/collect", "/v2/metrics")
    data class Encoded(val body: ByteArray, val type: String, val cookie: String? = null)
    /**
     * Probability, as 1-in-N, that a frame whose minimal bucket is small is padded
     * one bucket further. Every relay copy accepts any shared bucket that holds the
     * record (`unframe` checks `count <= size - 4`), so this needs no server change.
     * It stops "every poll is the 4 KiB class" from being a deterministic feature.
     */
    internal const val BUCKET_SPREAD_ONE_IN = 4
    /** Never spread into a bucket above this: the bandwidth cost stays bounded (<= 64 KiB). */
    internal const val BUCKET_SPREAD_MAX = 64 * 1024
    private val secureRandom = SecureRandom()

    fun minimalBucket(recordBytes: Int): Int =
        PADDING_BUCKETS.firstOrNull { recordBytes + 4 <= it } ?: error("Cover record too large")

    /** Deterministic minimal-bucket framing (fixtures, the relay's own reply size). */
    fun frame(value: JSONObject): ByteArray = frame(value, spread = false, random = secureRandom)

    fun frame(value: JSONObject, spread: Boolean, random: java.util.Random = secureRandom): ByteArray {
        val json = value.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX - 4)
        var bucket = minimalBucket(json.size)
        if (spread) {
            val next = PADDING_BUCKETS.firstOrNull { it > bucket }
            if (next != null && next <= BUCKET_SPREAD_MAX && random.nextInt(BUCKET_SPREAD_ONE_IN) == 0) bucket = next
        }
        return ByteArray(bucket).also {
            secureRandom.nextBytes(it)
            ByteBuffer.wrap(it).putInt(json.size).put(json)
        }
    }
    fun unframe(data: ByteArray): JSONObject {
        require(data.size in PADDING_BUCKETS) { "Invalid cover frame size" }
        val count = ByteBuffer.wrap(data).int
        require(count > 0 && count <= data.size - 4)
        return JSONObject(String(data, 4, count, Charsets.UTF_8)).also { require(it.getInt("v") == 1) }
    }
    fun encode(value: JSONObject, requestedMode: String): Encoded =
        encode(value, requestedMode, secureRandom)

    /**
     * The outer body is written byte for byte rather than by org.json, for two
     * reasons measured on 2026-11-13 (docs/COVER_TRANSPORT_STATUS_2026-11-13.md):
     *  - android.jar's org.json escapes every "/" as "\/", so on a DEVICE the body
     *    length was `constant + (number of '/' in the base64)` — an Android-only
     *    length signature that the JVM unit tests (real org.json, no escaping)
     *    never saw. Base64 is JSON-safe, so no escaping is needed at all.
     *  - the member order of the inner event object is now chosen per request,
     *    so the two closed schemas yield four byte-level shapes. Member order is
     *    not significant to any decoder (JSON.parse, org.json, JSONSerialization),
     *    so this stays inside the strict relay schema.
     */
    fun encode(value: JSONObject, requestedMode: String, random: java.util.Random): Encoded {
        require(requestedMode == "event") { "Unsupported cover carrier" }
        val payload = Base64.getEncoder().encodeToString(frame(value, spread = true, random = random))
        val swapped = random.nextBoolean()
        val body = when (PROFILES[random.nextInt(PROFILES.size)]) {
            "event" -> if (swapped) """{"events":[{"params":{"data":"$payload"},"name":"page_view"}]}"""
                       else """{"events":[{"name":"page_view","params":{"data":"$payload"}}]}"""
            "batch" -> if (swapped) """{"batch":[{"payload":"$payload","event":"screen_view"}]}"""
                       else """{"batch":[{"event":"screen_view","payload":"$payload"}]}"""
            else -> throw IllegalArgumentException("Unsupported cover profile")
        }
        return Encoded(body.toByteArray(Charsets.UTF_8), "application/json")
    }
    fun selectEndpointPath(random: java.util.Random = secureRandom): String = ENDPOINT_PATHS[random.nextInt(ENDPOINT_PATHS.size)]
    private fun hasExactKeys(value: JSONObject, vararg expected: String): Boolean {
        val actual = mutableSetOf<String>()
        val iterator = value.keys()
        while (iterator.hasNext()) actual += iterator.next()
        return actual == expected.toSet()
    }
    fun decode(body: ByteArray, type: String): JSONObject {
        require(body.size <= MAX * 4)
        require(type.startsWith("application/json", ignoreCase = true))
        val envelope = JSONObject(String(body, Charsets.UTF_8))
        // Closed, canonical schemas keep the carrier boundary reviewable. A
        // future extension must use a new versioned profile across platforms.
        val encoded = when {
            hasExactKeys(envelope, "events") && envelope.has("events") -> {
                val events = envelope.getJSONArray("events")
                require(events.length() == 1)
                val event = events.getJSONObject(0)
                require(hasExactKeys(event, "name", "params"))
                require(event.getString("name") == "page_view")
                val params = event.getJSONObject("params")
                require(hasExactKeys(params, "data"))
                params.getString("data")
            }
            hasExactKeys(envelope, "batch") && envelope.has("batch") -> {
                val batch = envelope.getJSONArray("batch")
                require(batch.length() == 1)
                val event = batch.getJSONObject(0)
                require(hasExactKeys(event, "event", "payload"))
                require(event.getString("event") == "screen_view")
                event.getString("payload")
            }
            else -> throw IllegalArgumentException("Unsupported cover profile")
        }
        require(encoded.length <= MAX_BASE64) { "Encoded cover frame too large" }
        return unframe(Base64.getDecoder().decode(encoded))
    }
}
