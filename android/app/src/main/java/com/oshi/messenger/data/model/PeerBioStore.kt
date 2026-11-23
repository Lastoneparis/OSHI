package com.oshi.messenger.data.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * What each contact published as their own bio and accent colour.
 *
 * __PROFILE_BIO_2026_11_02__ (N4) Stored SEALED, in `files/peer_bios.sealed`,
 * and never in SharedPreferences. A map of public key to personal text is the
 * user's social graph with descriptions attached, which is precisely the data
 * `__LEAK_FIX_2026_09_25__` moved out of a readable XML file. The new file is
 * registered in `SealedStringStore.ACCOUNT_FILES`, so "delete account" erases
 * it along with the rest — a sealed file nobody wipes is just a slower leak.
 */
object PeerBioStore {

    private const val SEAL_LABEL = "peer-bios-v1"

    data class Entry(val text: String, val until: Long?, val color: String?)

    private val _bios = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val bios: StateFlow<Map<String, Entry>> = _bios.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var store: com.oshi.messenger.data.local.atrest.SealedStringStore? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        val s = com.oshi.messenger.data.local.atrest.SealedStringStore.inFiles(
            app, com.oshi.messenger.data.local.atrest.SealedStringStore.PEER_BIOS_FILE, SEAL_LABEL,
        )
        store = s
        _bios.value = runCatching { s.read() }.getOrNull()?.let(::decode).orEmpty()
    }

    /**
     * The bio to SHOW for a contact, or null.
     *
     * Expiry is applied on read rather than by a timer: a device that was
     * asleep when the bio expired must not show it on waking, and nothing
     * should depend on a sweep having run.
     */
    fun visibleBio(publicKey: String, now: Long = System.currentTimeMillis()): String? =
        _bios.value[publicKey]?.takeIf { ProfileBioWire.isVisible(it.until, now) }?.text

    fun accentColor(publicKey: String): String? = _bios.value[publicKey]?.color

    /** Apply what a profile payload said. Returns true when something changed. */
    @Synchronized
    fun record(context: Context, publicKey: String, read: ProfileBioWire.Read, color: String?): Boolean {
        init(context)
        val before = _bios.value
        val existing = before[publicKey]
        val after = before.toMutableMap()

        when (read) {
            // Absent means the peer said nothing about their bio. Keep what we
            // have, and still take a colour if one came with the payload.
            is ProfileBioWire.Read.Absent ->
                if (color != null && existing?.color != color) {
                    after[publicKey] = (existing ?: Entry("", null, null)).copy(color = color)
                } else return false
            is ProfileBioWire.Read.Clear ->
                if (existing == null && color == null) return false
                else after[publicKey] = Entry("", null, color ?: existing?.color)
            is ProfileBioWire.Read.Set ->
                after[publicKey] = Entry(read.text, read.until, color ?: existing?.color)
        }
        if (after == before) return false
        _bios.value = after
        runCatching { store?.write(encode(after)) }
        return true
    }

    fun forget(context: Context, publicKey: String) {
        init(context)
        val after = _bios.value.toMutableMap()
        if (after.remove(publicKey) == null) return
        _bios.value = after
        runCatching { store?.write(encode(after)) }
    }

    internal fun encode(map: Map<String, Entry>): String {
        val root = JSONObject()
        for ((k, v) in map) {
            root.put(k, JSONObject().apply {
                put("t", v.text)
                v.until?.let { put("u", it) }
                v.color?.let { put("c", it) }
            })
        }
        return root.toString()
    }

    internal fun decode(json: String): Map<String, Entry> = try {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { k ->
            val e = o.optJSONObject(k) ?: return@mapNotNull null
            k to Entry(
                text = e.optString("t"),
                until = if (e.has("u")) e.optLong("u").takeIf { it > 0 } else null,
                color = ProfileBioWire.sanitizeColor(e.optString("c")),
            )
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}
