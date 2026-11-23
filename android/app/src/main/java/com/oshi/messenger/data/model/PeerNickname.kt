package com.oshi.messenger.data.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * __SHARED_NICKNAME_2026_09_22__ The nickname a peer chose for THEMSELVES, carried by
 * the `📸PROFILE_UPDATE📸` payload under [WIRE_KEY].
 *
 * Before this, the nickname typed in Settings never left the phone: the profile payload
 * (iOS `ProfileUpdatePayload`, Android `MessageRepository.sendProfileUpdate`) carried an
 * avatar, a last-seen and capabilities, and no name at all. Every contact therefore read
 * as a key prefix until the user typed an alias by hand.
 *
 * ONE key on every platform: `"displayName"` (iOS `ProfileUpdatePayload.displayName`,
 * Desktop `ProfileNickname`). Additive and optional: an older Android build reads this
 * payload with `JSONObject.opt*` and ignores the key; an older iOS build decodes with a
 * synthesized `Codable` that ignores it too.
 *
 * Precedence for display is fixed and lives in [resolve] only:
 *   a local alias the user TYPED  >  the peer's shared nickname  >  the old fallback.
 * The shared nickname is never written into `Contact.alias` — the alias stays the user's
 * own, private override, exactly as before.
 *
 * Deliberately pure (no Context) so the whole rule is a JVM unit test.
 */
object PeerNickname {

    /** JSON key in the profile payload. Same spelling on iOS and Desktop. */
    const val WIRE_KEY = "displayName"

    /** Same cap as the Settings field on both phones (`trim().take(48)`). */
    const val MAX_CODE_POINTS = 48

    /** Tri-state read: an older client says nothing, which is NOT "cleared". */
    sealed class Read {
        /** Key missing — older build. Keep whatever we had. */
        object Absent : Read()
        /** Key present but empty/unusable after sanitising — the peer cleared it. */
        object Clear : Read()
        data class Set(val name: String) : Read()
    }

    private fun isStripped(cp: Int): Boolean =
        Character.isISOControl(cp) ||          // C0/C1 incl. \n \r \t
            cp == 0x2028 || cp == 0x2029 ||    // line / paragraph separators
            cp in 0x202A..0x202E ||            // LRE RLE PDF LRO RLO
            cp in 0x2066..0x2069 ||            // LRI RLI FSI PDI
            cp == 0x200E || cp == 0x200F       // LRM RLM

    /**
     * A name that came off the wire is untrusted display text: strip controls, newlines
     * and bidi overrides (an RLO would let "Alice‮ecilа" render as something else
     * in the list and in notifications), trim, cap at [MAX_CODE_POINTS] code points
     * without ever splitting a surrogate pair. Empty ⇒ null.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (!isStripped(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        var s = sb.toString().trim()
        if (s.codePointCount(0, s.length) > MAX_CODE_POINTS) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_CODE_POINTS)).trim()
        }
        return s.ifEmpty { null }
    }

    /** Put our own nickname on an outgoing payload — omitted entirely when we have none. */
    fun stamp(json: JSONObject, ownName: String?): JSONObject {
        sanitize(ownName)?.let { json.put(WIRE_KEY, it) }
        return json
    }

    fun read(json: JSONObject): Read {
        if (!json.has(WIRE_KEY)) return Read.Absent
        val raw = if (json.isNull(WIRE_KEY)) null else json.optString(WIRE_KEY, "")
        return sanitize(raw)?.let { Read.Set(it) } ?: Read.Clear
    }

    /** Same folding as `MessageRepository.normalizeBase64Key` (and iOS `normalizeAliasKey`). */
    fun normalizeKey(key: String): String =
        key.trim().replace('-', '+').replace('_', '/').replace("=", "")

    /**
     * Android fills `Contact.alias` (non-null) with a placeholder when nobody named the
     * contact: `canonical.take(8) + "..."` in `ensureContactExists`, and `take(n)` +
     * `…`/`...` elsewhere. Such an alias is NOT a user choice and must lose to the shared
     * nickname. A blank alias, or one that is just a prefix of this contact's key
     * (any base64/base64url spelling) followed by an ellipsis or nothing, is automatic.
     */
    fun isAutoAlias(alias: String?, publicKey: String): Boolean {
        val a = alias?.trim().orEmpty()
        if (a.isEmpty()) return true
        val stem = a.removeSuffix("...").removeSuffix("…").trimEnd()
        if (stem.isEmpty()) return true
        // Placeholders are 6-12 key chars. Below 4, a typed alias ("Al") could collide
        // with a key prefix by chance and silently lose to the nickname.
        if (stem.length < 4 || stem.length > 16) return false
        val key = publicKey.trim()
        if (key.startsWith(stem)) return true
        val nk = normalizeKey(key)
        val ns = normalizeKey(stem)
        return ns.isNotEmpty() && nk.startsWith(ns)
    }

    /** The alias the user actually typed, or null. */
    fun userAlias(alias: String?, publicKey: String): String? =
        alias?.trim()?.takeIf { !isAutoAlias(it, publicKey) }

    /**
     * THE display rule: user alias > shared nickname > existing fallback (the stored
     * placeholder alias if any, else `key.take(8) + "..."`).
     */
    fun resolve(alias: String?, nickname: String?, publicKey: String): String {
        userAlias(alias, publicKey)?.let { return it }
        sanitize(nickname)?.let { return it }
        return alias?.trim()?.takeIf { it.isNotEmpty() } ?: (publicKey.take(8) + "...")
    }

    /** Lookup in a map keyed by [normalizeKey]. */
    fun lookup(map: Map<String, String>, publicKey: String): String? =
        map[normalizeKey(publicKey)]?.takeIf { it.isNotEmpty() }

    /**
     * Sender-name table for a GROUP thread. Every contact under its stored key AND its
     * folded spelling (a base64url or unpadded sender key used to miss the row), plus the
     * shared nickname of peers who are not contacts at all, under their folded key. A
     * contact's entry always wins over a bare nickname of the same person.
     */
    fun senderDisplayMap(
        contacts: List<Pair<String, String?>>,
        nicknames: Map<String, String>,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in nicknames) sanitize(v)?.let { out[normalizeKey(k)] = it }
        for ((pk, alias) in contacts) {
            val name = resolve(alias, lookup(nicknames, pk), pk)
            out[normalizeKey(pk)] = name
            out[pk] = name
        }
        return out
    }

    /** Read [senderDisplayMap] with the raw wire key or any other spelling of it. */
    fun lookupSender(map: Map<String, String>, publicKey: String): String? =
        (map[publicKey] ?: map[normalizeKey(publicKey)])?.takeIf { it.isNotBlank() }

    /** Pure apply step of the store: returns the new map (same instance if unchanged). */
    fun apply(map: Map<String, String>, publicKey: String, read: Read): Map<String, String> {
        val k = normalizeKey(publicKey)
        if (k.isEmpty()) return map
        return when (read) {
            Read.Absent -> map
            Read.Clear -> if (map.containsKey(k)) map - k else map
            is Read.Set -> if (map[k] == read.name) map else map + (k to read.name)
        }
    }
}

/**
 * __SHARED_NICKNAME_2026_09_22__ Persisted per-peer shared nicknames, SEPARATE from the
 * `contacts.alias` column (no Room migration). Keyed by [PeerNickname.normalizeKey] so a
 * base64url / unpadded wire key finds the row a padded key wrote. [nicknames] is a
 * StateFlow so the conversation list and chat header redraw when a peer renames.
 */
object PeerNicknameStore {
    private const val PREFS = "peer_nicknames"
    /**
     * __LEAK_FIX_2026_09_25__ The map (contact public key → the name they go by) was a
     * cleartext `shared_prefs/peer_nicknames.xml`: the user's social graph with names,
     * readable off any file copy. It is now one sealed JSON object in
     * `files/peer_nicknames.sealed`; the prefs file is migrated on first load and then
     * deleted (only once the sealed copy has been read back).
     */
    const val SEALED_FILE = com.oshi.messenger.data.local.atrest.SealedStringStore.PEER_NICKNAMES_FILE
    private const val SEAL_LABEL = "peer-nicknames-v1"

    private val _nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var store: com.oshi.messenger.data.local.atrest.SealedStringStore? = null

    /** Idempotent; called from `OSHIApplication.onCreate` and defensively by writers. */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        val s = com.oshi.messenger.data.local.atrest.SealedStringStore.inFiles(
            app, SEALED_FILE, SEAL_LABEL, legacy = legacyPrefs(app),
        )
        store = s
        _nicknames.value = runCatching { s.read() }.getOrNull()?.let(::decode).orEmpty()
    }

    fun lookup(publicKey: String): String? = PeerNickname.lookup(_nicknames.value, publicKey)

    /** Display name for a contact row: user alias > shared nickname > fallback. */
    fun displayName(alias: String?, publicKey: String): String =
        PeerNickname.resolve(alias, lookup(publicKey), publicKey)

    /** Apply what a profile payload said. Returns true when something changed. */
    @Synchronized
    fun record(context: Context, publicKey: String, read: PeerNickname.Read): Boolean {
        init(context)
        val before = _nicknames.value
        val after = PeerNickname.apply(before, publicKey, read)
        if (after === before) return false
        runCatching { store?.write(encode(after)) }
        _nicknames.value = after
        return true
    }

    internal fun encode(map: Map<String, String>): String = JSONObject(map as Map<*, *>).toString()

    internal fun decode(json: String): Map<String, String> = try {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /** The whole legacy prefs file (one entry per peer) as a single JSON document. */
    private fun legacyPrefs(app: Context) = object : com.oshi.messenger.data.local.atrest.SealedStringStore.Legacy {
        override fun read(): String? {
            val all = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
                .mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it } }
                .toMap()
            return if (all.isEmpty()) null else encode(all)
        }

        override fun clear(): Boolean {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.all.isEmpty()) return true
            val ok = prefs.edit().clear().commit()
            if (ok) runCatching { app.deleteSharedPreferences(PREFS) }
            return ok
        }
    }
}

/**
 * __SHARED_NICKNAME_2026_09_22__ The keys of `📸PROFILE_UPDATE📸` that the iOS decoder
 * REQUIRES. iOS decodes the payload with a synthesized `Codable` (`ProfileUpdatePayload`,
 * `OSHI/ContactPresenceManager.swift`): `type`, `lastSeen` and `version` are non-optional,
 * so a payload missing any of them throws and the WHOLE update is dropped — nickname,
 * avatar and capabilities alike.
 *
 * Android used to omit `lastSeen` when the Last Seen setting was off, which silently made
 * every such user's profile unreadable on every iPhone. "Not shared" is now `0` (the
 * Android reader has always treated `lastSeen <= 0` as absent, and iOS overwrites the value
 * with its own receive time right after decoding, so 0 discloses nothing).
 */
object ProfileUpdateWire {
    const val TYPE = "profile_update"
    const val VERSION = 1

    /** Value for the required `lastSeen` key (Unix SECONDS, a Double like iOS). */
    fun lastSeenValue(shared: Boolean, nowMs: Long): Double =
        if (shared) nowMs / 1000.0 else 0.0
}
