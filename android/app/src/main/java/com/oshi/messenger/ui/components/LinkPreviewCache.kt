package com.oshi.messenger.ui.components

import android.content.Context
import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.AtRestSubKeys
import com.oshi.messenger.data.local.atrest.SealedFile
import java.io.File
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Memory → disk → network, for link previews. Port of iOS `LinkPreviewCache`
 * (`OSHI/LinkPreview.swift:52-106`).
 *
 * ## Why this exists — it is a privacy fix, not a performance one
 *
 * `LinkPreviewCard` held its metadata and its image in `remember`, which dies with
 * the composition. A `LazyColumn` disposes a bubble the moment it scrolls off, so
 * scrolling the same link back into view re-ran the whole thing: an HTML GET of the
 * page, then a second GET for the OG image. In a messenger whose entire premise is
 * that nobody sees who talks to whom, that is **a fresh direct HTTP request from the
 * RECIPIENT's device to a third-party host, every single time the bubble scrolls into
 * view** — the recipient's IP address, their User-Agent and the exact times they were
 * reading that conversation, handed to whoever owns the link. Once per link is the
 * unavoidable cost of showing a preview at all; once per scroll is a leak.
 *
 * Three tiers, iOS's:
 *
 * | tier    | holds                    | bound                          | survives |
 * |---------|--------------------------|--------------------------------|----------|
 * | memory  | encoded entry blobs      | 200 entries **and** 24 MB, LRU | scrolls  |
 * | disk    | one JSON file per URL    | the OS may purge `cacheDir`    | relaunch |
 * | network | —                        | —                              | —        |
 *
 * Entries expire after [TTL_MS] = 7 days, iOS's `ttl` (`LinkPreview.swift:62`): a
 * preview is a snapshot of someone else's page, and a stale title is worse than a
 * slightly slower load. Expiry is checked **on read** and evicts both tiers, exactly
 * as `lookup` does on iOS (`:88-92`) — there is no sweeper, because a `cacheDir` the
 * system can purge at will does not deserve one.
 *
 * ## The one deliberate divergence from iOS
 *
 * iOS caches only SUCCESSES, so a host that is down, blocks the User-Agent, or serves
 * no OpenGraph tags is re-requested on every scroll forever — the leak, undiminished,
 * on exactly the hosts most likely to be logging. [storeFailure] records a negative
 * entry with its own much shorter [NEGATIVE_TTL_MS] = 1 hour: long enough that
 * scrolling cannot re-ask, short enough that a site that was merely down for a minute
 * gets another chance the next time the thread is opened. This is the addition the
 * 2026-08-24 photo/link report asked for ("cache *negative* results too, which iOS
 * does not do either and should").
 *
 * ## Not a wire format
 *
 * Nothing here crosses the wire — iOS and Android each scrape locally and neither
 * sends preview metadata (see `parity/verify/2026-08-24-photo-link.md` §F.6). The
 * on-disk shape is therefore private to this device and may change freely; a file
 * that fails to parse is simply a miss.
 *
 * ## At rest (__PLAINTEXT_LEFTOVERS_2026_09_24__)
 *
 * The disk tier used to be plaintext JSON named by base64(URL) — a directory listing
 * alone was the list of every link the user had received. Now each entry is sealed
 * (`OSHISEA1`, [SealedFile]) and named by `HMAC-SHA256(at-rest sub-key, url)` in hex:
 * neither the name nor the content says anything without this device's key. The URL
 * is also stored INSIDE the sealed entry and checked on read, so a (theoretical) name
 * collision is a miss, never someone else's title. Without a key (Keystore down, JVM
 * test) the disk tier is simply skipped — memory only, never plaintext.
 * [migrateLegacy] converts the old base64-named files (their name decodes to the URL).
 */
class LinkPreviewCache(
    private val dir: File,
    /** At-rest key for the sealed disk tier and the HMAC names (injected by tests). */
    private val keys: AtRestKeyProvider = AtRestKey,
    /** Injected so the TTL is testable without sleeping for seven days. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** What a lookup hands back: the metadata, and the OG image bytes if we have them. */
    data class Hit(val data: LinkPreviewData?, val image: ByteArray?) {
        /** A remembered FAILURE — do not re-fetch, do not draw a card. */
        val isNegative: Boolean get() = data == null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Hit) return false
            return data == other.data && (image?.contentEquals(other.image) ?: (other.image == null))
        }

        override fun hashCode(): Int = 31 * (data?.hashCode() ?: 0) + (image?.contentHashCode() ?: 0)
    }

    // ── memory tier ──────────────────────────────────────────────────────────
    //
    // NSCache bounds BOTH a count and a total cost; Android's `LruCache` bounds one
    // size only, so the LRU is written out. Access-ordered LinkedHashMap = the same
    // eviction order NSCache approximates.
    private val memory = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {}
    private var memoryCost = 0L
    private val lock = Any()

    /** Bytes currently held in the memory tier. Test-visible; not used by production. */
    val memoryBytes: Long get() = synchronized(lock) { memoryCost }

    /** Entries currently held in the memory tier. Test-visible. */
    val memoryCount: Int get() = synchronized(lock) { memory.size }

    private fun memoryPut(k: String, blob: ByteArray) {
        synchronized(lock) {
            memory.remove(k)?.let { memoryCost -= it.size }
            // A single entry larger than the whole budget is not cacheable in memory.
            // Storing it would evict everything else and then evict itself.
            if (blob.size > MEMORY_COST_LIMIT) return
            memory[k] = blob
            memoryCost += blob.size
            val it = memory.entries.iterator()
            while (it.hasNext() && (memory.size > MEMORY_COUNT_LIMIT || memoryCost > MEMORY_COST_LIMIT)) {
                val eldest = it.next()
                memoryCost -= eldest.value.size
                it.remove()
            }
        }
    }

    private fun memoryGet(k: String): ByteArray? = synchronized(lock) { memory[k] }

    private fun memoryRemove(k: String) {
        synchronized(lock) { memory.remove(k)?.let { memoryCost -= it.size } }
    }

    // ── the three-tier read ──────────────────────────────────────────────────

    /**
     * Memory, then disk. `null` means "ask the network"; a [Hit] means do not.
     *
     * A [Hit] whose [Hit.isNegative] is true is a remembered failure: still do not
     * touch the network, and draw no card.
     */
    fun lookup(url: String): Hit? {
        val k = url                                      // memory tier: never on disk
        val blob = memoryGet(k) ?: readDisk(url) ?: return null
        val entry = Entry.parse(blob) ?: return null      // corrupt → a miss, like iOS
        if (entry.key != null && entry.key != url) return null   // name collision → a miss
        val ttl = if (entry.data == null) NEGATIVE_TTL_MS else TTL_MS
        if (now() - entry.storedAt >= ttl) {
            // Evict BOTH tiers on read. iOS `LinkPreview.swift:88-92`.
            memoryRemove(k)
            diskName(url)?.let { n -> runCatching { File(dir, n).delete() } }
            return null
        }
        // Promote: a disk hit becomes a memory hit for the rest of the session.
        memoryPut(k, blob)
        return Hit(entry.data, entry.image)
    }

    /**
     * Remember a successful preview. Called twice per fetch, as iOS does
     * (`LinkPreview.swift:189, 229`): once with `image = null` the instant the
     * metadata is parsed — so a failed image download still saves the expensive HTML
     * GET — and again with the image once it lands.
     *
     * Synchronous, and therefore callable from a unit test. Production calls it from
     * an IO dispatcher; iOS does the same with a `DispatchQueue.global` hop (`:104`).
     */
    fun store(url: String, data: LinkPreviewData, image: ByteArray?) {
        write(url, Entry(data, image, now(), url))
    }

    /** Remember a FAILURE for [NEGATIVE_TTL_MS]. See the class note. */
    fun storeFailure(url: String) {
        write(url, Entry(null, null, now(), url))
    }

    private fun write(url: String, entry: Entry) {
        val blob = entry.encode()
        memoryPut(url, blob)
        val name = diskName(url) ?: return               // no key ⇒ memory only
        val sealKey = AtRestSubKeys.sealed(keys) ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            // Write-then-rename: a process killed mid-write must never leave a
            // half-file that every later lookup pays to read and then discards.
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(SealedFile.seal(sealKey, SEAL_LABEL, blob))
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
            }
        }
    }

    private fun readDisk(url: String): ByteArray? = runCatching {
        val name = diskName(url) ?: return null
        val raw = File(dir, name).takeIf { it.isFile }?.readBytes() ?: return null
        val sealKey = AtRestSubKeys.sealed(keys) ?: return null
        SealedFile.open(sealKey, SEAL_LABEL, raw)
    }.getOrNull()

    /** `hex(HMAC-SHA256(name key, url))`, or null without an at-rest key. */
    internal fun diskName(url: String): String? {
        val master = runCatching { keys.masterKey() }.getOrNull() ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(AtRestSubKeys.derive(master, PURPOSE_NAME).encoded, "HmacSHA256"))
        return mac.doFinal(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * One-shot conversion of the pre-2026-09-24 plaintext files (base64(URL) names):
     * re-stored sealed under their HMAC name, then deleted. Without a key they are just
     * deleted — a cache is re-fetchable, a plaintext list of links is not acceptable.
     * @return how many legacy files were removed.
     */
    fun migrateLegacy(): Int {
        var removed = 0
        val hasKey = runCatching { keys.masterKey() }.getOrNull() != null
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || HEX_NAME.matches(f.name)) return@forEach
            if (f.name.endsWith(".tmp")) { if (f.delete()) removed++; return@forEach }
            if (hasKey) runCatching {
                val raw = f.readBytes()
                if (!SealedFile.isSealed(raw)) {
                    val url = legacyUrl(f.name)
                    val entry = Entry.parse(raw)
                    if (url != null && entry != null) write(url, entry.copy(key = url))
                }
            }
            if (f.delete()) removed++
        }
        return removed
    }

    private fun legacyUrl(name: String): String? = runCatching {
        val b64 = name.replace('_', '/').replace('-', '+')
        String(Base64.getDecoder().decode(b64 + "=".repeat((4 - b64.length % 4) % 4)), Charsets.UTF_8)
    }.getOrNull()

    // ── the entry ────────────────────────────────────────────────────────────

    private data class Entry(
        val data: LinkPreviewData?,
        val image: ByteArray?,
        val storedAt: Long,
        /** The URL this entry was stored for (absent in legacy files). */
        val key: String? = null,
    ) {
        fun encode(): ByteArray {
            val o = org.json.JSONObject()
            o.put("storedAt", storedAt)
            key?.let { o.put("key", it) }
            data?.let { d ->
                o.put("url", d.url)
                d.title?.let { o.put("title", it) }
                d.description?.let { o.put("description", it) }
                d.imageUrl?.let { o.put("imageUrl", it) }
                d.siteName?.let { o.put("siteName", it) }
            }
            image?.let { o.put("image", Base64.getEncoder().encodeToString(it)) }
            return o.toString().toByteArray(Charsets.UTF_8)
        }

        companion object {
            fun parse(blob: ByteArray): Entry? = runCatching {
                val o = org.json.JSONObject(String(blob, Charsets.UTF_8))
                val storedAt = o.getLong("storedAt")
                // No "url" key at all = a negative entry. A `LinkPreviewData` with no
                // url would render a card for nothing, so the distinction is the key's
                // presence, not an emptiness test on it.
                val data = if (o.has("url")) {
                    LinkPreviewData(
                        url = o.getString("url"),
                        title = o.optStringOrNull("title"),
                        description = o.optStringOrNull("description"),
                        imageUrl = o.optStringOrNull("imageUrl"),
                        siteName = o.optStringOrNull("siteName"),
                    )
                } else null
                val image = o.optStringOrNull("image")?.let { Base64.getDecoder().decode(it) }
                Entry(data, image, storedAt, o.optStringOrNull("key"))
            }.getOrNull()

            private fun org.json.JSONObject.optStringOrNull(key: String): String? =
                if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
        }
    }

    companion object {
        /** iOS `ttl` — `LinkPreview.swift:62`, `7 * 24 * 3600` seconds. */
        const val TTL_MS: Long = 7L * 24 * 3600 * 1000

        /** Negative entries only. See the class note; iOS has no equivalent. */
        const val NEGATIVE_TTL_MS: Long = 60L * 60 * 1000

        /** AAD label of the sealed disk entries. */
        const val SEAL_LABEL = "link-preview-v1"

        /** At-rest sub-key purpose for the HMAC file names. */
        const val PURPOSE_NAME = "link-preview-name"

        private val HEX_NAME = Regex("^[0-9a-f]{64}$")

        /** iOS `memory.countLimit` — `LinkPreview.swift:66`. */
        const val MEMORY_COUNT_LIMIT = 200

        /** iOS `memory.totalCostLimit` — `LinkPreview.swift:68`, 24 MB. */
        const val MEMORY_COST_LIMIT = 24 * 1024 * 1024

        /**
         * The PRE-2026-09-24 file name, byte-for-byte iOS's (`LinkPreview.swift:76-81`):
         * standard base64 of the URL's UTF-8, then `/`→`_`, `+`→`-`, `=` dropped.
         * Reversible — which is why it is no longer used on disk (see the class note);
         * kept for [migrateLegacy]'s tests.
         */
        fun key(url: String): String =
            Base64.getEncoder().encodeToString(url.toByteArray(Charsets.UTF_8))
                .replace('/', '_')
                .replace('+', '-')
                .replace("=", "")

        @Volatile
        private var instance: LinkPreviewCache? = null

        /**
         * The process-wide instance. It has to be shared — a per-composable cache
         * would have exactly the lifetime of the `remember` this replaces, and would
         * fix nothing.
         */
        fun shared(context: Context): LinkPreviewCache =
            instance ?: synchronized(this) {
                instance ?: LinkPreviewCache(
                    File(context.applicationContext.cacheDir, "LinkPreviews")
                ).also { instance = it }
            }
    }
}
