package com.oshi.messenger.service.lora

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * LoRaNodeIdentityMap — which OSHI identity is sitting behind a Meshtastic node
 * number.
 *
 * Port of iOS `LoRaNodeIdentityMap.swift` (whole file), with the routing rule
 * exercised by `MeshtasticManager.handleInteropText` (iOS
 * `MeshtasticManager.swift:999-1011`).
 *
 * WHY THIS EXISTS
 * ---------------
 * Two kinds of packet arrive over LoRa and only one carries an identity:
 *
 *  • An OSHI envelope (PRIVATE_APP, portnum 256) is a `SecureMessage`. It
 *    carries the sender's public key, so it already lands in the right
 *    conversation.
 *  • A RAW Meshtastic text packet (TEXT_MESSAGE_APP, portnum 1) — what the stock
 *    Meshtastic app, or a bare node, sends — carries NOTHING but the node
 *    number. No key, no name that means anything to OSHI. So the receiver has to
 *    invent a synthetic sender id, `lora!<nodehex>`, and the message opens a
 *    conversation of its own next to the real one.
 *
 * The only way a raw text can reach the right conversation is a LEARNED
 * mapping: remember which public key a node has already PROVED it speaks for,
 * and route later raw text from that node to it. Nothing can be inferred from
 * the raw packet itself.
 *
 * WHAT COUNTS AS PROOF
 * --------------------
 * Only a LoRa OSHI envelope that WE DECRYPTED. Decryption runs the ratchet over
 * an X25519 secret computed from our private key and the claimed sender key, so
 * a message that decrypts could only have been produced by the holder of that
 * key's private half. A *claimed* sender field proves nothing and is
 * deliberately NOT enough to create a binding — anyone with a radio can put a
 * contact's public key (which is shared openly, by QR) into an envelope.
 * Call [bind] only from a path where the decryption already succeeded.
 *
 * WHEN IN DOUBT, DON'T
 * --------------------
 * Never proved an identity → raw text keeps going to `lora!<nodehex>`.
 * Proved a SECOND, different identity (one radio shared by two people, or a
 * rotated key) → the binding is marked ambiguous and routing falls back to
 * `lora!` for good. An extra conversation is a nuisance; putting a stranger's
 * message inside a friend's thread in an encrypted messenger is a betrayal of
 * the whole product.
 *
 * PERSISTENCE
 * -----------
 * JSON in SharedPreferences, keyed by the node number formatted `%08x`.
 * ⚠️ Deliberately NOT a hash code: `Object.hashCode()` is identity-based for
 * most types and JVM implementations are free to vary it, so a hash-derived key
 * silently addresses a different slot after a relaunch — which for this map
 * would mean the mapping evaporates at the exact moment it is supposed to be
 * useful (the next launch). Same reasoning as the Swift header, `:44-49`.
 *
 * NODE NUMBERS
 * ------------
 * Meshtastic node numbers are unsigned 32-bit. Kotlin's `Long` carries them
 * without the sign trap; everything here masks to 32 bits before formatting, so
 * a value that arrived as a sign-extended `Int` still keys the same slot.
 */
class LoRaNodeIdentityMap(private val store: LoRaIdentityStore) {

    /**
     * @param publicKey the OSHI public key, stored EXACTLY as it arrived so it
     *   matches the conversation/contact keys elsewhere byte for byte.
     *   Comparison is normalized; storage is not.
     * @param ambiguous set once a second, different identity has proved itself
     *   from this node. Routing is disabled while true — see the file header.
     */
    data class Binding(
        val publicKey: String,
        val learnedAtMillis: Long,
        val ambiguous: Boolean
    )

    enum class LearnResult {
        /** A new binding was created. */
        BOUND,
        /** Same identity as we already had; nothing changed. */
        UNCHANGED,
        /** A different identity proved itself — the node no longer routes. */
        CONFLICT,
        /** Not a usable identity key (empty, too short, or a synthetic `lora!` key). */
        REJECTED,
        /** Already ambiguous; further evidence cannot un-ambiguate it. */
        IGNORED_AMBIGUOUS
    }

    private val lock = Any()
    private val bindings: MutableMap<String, Binding> = decode(store.read()).toMutableMap()

    // ---------------------------------------------------------------- Read

    /**
     * The identity this node has proved, or null when it has proved none — or has
     * proved more than one. iOS `:145-149`.
     *
     * null is the signal to keep using [fallbackConversationKey]. It must never
     * be papered over with a best guess.
     */
    fun publicKey(forNode: Long): String? = synchronized(lock) {
        val b = bindings[nodeKey(forNode)] ?: return null
        if (b.ambiguous || b.publicKey.isEmpty()) null else b.publicKey
    }

    /**
     * THE ROUTING DECISION for a raw Meshtastic text packet: the conversation key
     * it must be filed under. iOS `:178-183`.
     *
     * The proved identity when there is one, the historical `lora!<nodehex>` key
     * when there is not. There is no second copy of this rule anywhere — the
     * inbound text handler calls this and files the message under what it returns.
     */
    fun conversationKey(forNode: Long): String {
        if (!adoptRawTextIntoContactThread) return fallbackConversationKey(forNode)
        return publicKey(forNode) ?: fallbackConversationKey(forNode)
    }

    /** Instance form of the companion helper, for callers holding the map. */
    fun fallbackConversationKey(node: Long): String = Companion.fallbackConversationKey(node)

    /** The raw record, ambiguity included — for diagnostics and tests. iOS `:186-189`. */
    fun binding(forNode: Long): Binding? = synchronized(lock) { bindings[nodeKey(forNode)] }

    /**
     * Which node, if any, is bound to this identity. Used to keep one identity
     * from being spread over several nodes silently. iOS `:193-200`.
     */
    fun node(forPublicKey: String): Long? {
        val wanted = normalizeKey(forPublicKey)
        synchronized(lock) {
            for ((hex, b) in bindings) {
                if (!b.ambiguous && normalizeKey(b.publicKey) == wanted) {
                    return hex.toLongOrNull(16)
                }
            }
        }
        return null
    }

    val count: Int get() = synchronized(lock) { bindings.size }

    // --------------------------------------------------------------- Write

    /**
     * Record that [node] has CRYPTOGRAPHICALLY PROVED it speaks for [publicKey].
     * iOS `learn(node:publicKey:)`, `:216-266`.
     *
     * Call this only from a path where the proof exists — today that is a LoRa
     * OSHI envelope that decrypted. Calling it from anywhere that merely read a
     * claimed sender field would hand an attacker the ability to file their
     * messages under someone else's name.
     */
    fun bind(node: Long, publicKey: String): LearnResult {
        val trimmed = publicKey.trim()
        if (!looksLikeIdentityKey(trimmed)) return LearnResult.REJECTED

        val result: LearnResult
        val snapshot: Map<String, Binding>
        synchronized(lock) {
            val key = nodeKey(node)
            val existing = bindings[key]
            if (existing != null) {
                result = when {
                    existing.ambiguous -> LearnResult.IGNORED_AMBIGUOUS
                    normalizeKey(existing.publicKey) == normalizeKey(trimmed) -> LearnResult.UNCHANGED
                    else -> {
                        bindings[key] = existing.copy(ambiguous = true)
                        LearnResult.CONFLICT
                    }
                }
            } else {
                // 🔧 iOS FIX (2026-08-10), `:239-251`: the conflict rule only ran
                // one way — one node claiming two identities. The INVERSE was
                // explicitly allowed, and it is the direction an attacker uses:
                // bind their own node to a contact's already-bound identity and
                // every raw text they transmit is filed in that contact's
                // conversation.
                //
                // Mark BOTH ambiguous. Which of the two is the impostor is not
                // knowable here, and picking one would be a coin flip on whose
                // messages get attributed to a real contact. Both fall back to
                // their own `lora!` threads, recoverable via `forget`.
                val other = bindings.entries.firstOrNull {
                    it.key != key && !it.value.ambiguous &&
                        normalizeKey(it.value.publicKey) == normalizeKey(trimmed)
                }
                if (other != null) {
                    bindings[other.key] = other.value.copy(ambiguous = true)
                    bindings[key] = Binding(trimmed, now(), ambiguous = true)
                    result = LearnResult.CONFLICT
                } else {
                    bindings[key] = Binding(trimmed, now(), ambiguous = false)
                    result = LearnResult.BOUND
                }
            }
            snapshot = bindings.toMap()
        }

        if (result == LearnResult.BOUND || result == LearnResult.CONFLICT) persist(snapshot)
        return result
    }

    /**
     * Drop what we know about a node. The only way out of `ambiguous`, and the
     * honest response to a user saying "that isn't them". iOS `:270-276`.
     */
    fun forget(node: Long) {
        val snapshot: Map<String, Binding>
        synchronized(lock) {
            bindings.remove(nodeKey(node))
            snapshot = bindings.toMap()
        }
        persist(snapshot)
    }

    fun removeAll() {
        val snapshot: Map<String, Binding>
        synchronized(lock) {
            bindings.clear()
            snapshot = bindings.toMap()
        }
        persist(snapshot)
    }

    // ----------------------------------------------------------- Internals

    /** Overridable in tests so `learnedAt` ordering can be made deterministic. */
    internal var now: () -> Long = { System.currentTimeMillis() }

    private fun persist(snapshot: Map<String, Binding>) {
        val root = JSONObject()
        for ((hex, b) in snapshot) {
            root.put(
                hex,
                JSONObject()
                    .put(FIELD_PUBLIC_KEY, b.publicKey)
                    .put(FIELD_LEARNED_AT, b.learnedAtMillis)
                    .put(FIELD_AMBIGUOUS, b.ambiguous)
            )
        }
        store.write(root.toString())
    }

    private fun decode(json: String?): Map<String, Binding> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            buildMap {
                for (hex in root.keys()) {
                    val o = root.optJSONObject(hex) ?: continue
                    val pk = o.optString(FIELD_PUBLIC_KEY, "")
                    if (pk.isEmpty()) continue
                    put(
                        hex,
                        Binding(
                            publicKey = pk,
                            learnedAtMillis = o.optLong(FIELD_LEARNED_AT, 0L),
                            ambiguous = o.optBoolean(FIELD_AMBIGUOUS, false)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // A corrupt blob is not worth crashing the app for; an empty map
            // means raw text lands in `lora!` threads, which is the safe side.
            emptyMap()
        }
    }

    companion object {

        /**
         * The app-wide map. Radio callbacks and the message layer both reach it.
         * Call [install] once from application start-up; until then it is a
         * memory-only map, so nothing crashes in unit tests or previews.
         */
        @Volatile
        var shared: LoRaNodeIdentityMap = LoRaNodeIdentityMap(InMemoryLoRaIdentityStore())
            private set

        /**
         * Wire the shared map to real storage.
         *
         * ⚠️ MUST run in `OSHIApplication.onCreate()`, BEFORE any radio traffic.
         * It replaces [shared] with a map re-read from disk, so anything learned
         * into the memory-only default beforehand is discarded — calling it late
         * would silently drop bindings a node had already proved this session.
         */
        @Synchronized
        fun install(context: Context) {
            val app = context.applicationContext
            shared = LoRaNodeIdentityMap(SharedPrefsLoRaIdentityStore(app))
            // The DEFAULT here must track the property's own default, or
            // `install()` would read `false` out of a preference nobody has set
            // and quietly undo it on every launch.
            adoptRawTextIntoContactThread = app
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ADOPT_RAW_TEXT, true)
        }

        /** Test seam: swap the shared map for one over an injected store. */
        internal fun installForTest(map: LoRaNodeIdentityMap) {
            shared = map
        }

        internal const val PREFS_NAME = "oshi_lora_node_identity_v1"
        internal const val KEY_BINDINGS = "bindings"
        internal const val KEY_ADOPT_RAW_TEXT = "adopt_raw_text"

        private const val FIELD_PUBLIC_KEY = "k"
        private const val FIELD_LEARNED_AT = "t"
        private const val FIELD_AMBIGUOUS = "a"

        /**
         * 🔧 iOS `:157-176` — DEFAULT OFF, and that is the honest setting today.
         *
         * Binding a node to an identity can be made sound, and has been. What
         * cannot be fixed at this layer is that a raw Meshtastic text packet is
         * UNAUTHENTICATED. Its `from` field is an unsigned integer on a channel
         * whose key is public, so anyone in radio range can transmit a packet
         * claiming any node number, including one this map has legitimately
         * bound. Filing that into a contact's end-to-end-encrypted conversation
         * renders unverified plaintext identically to a message the ratchet
         * authenticated.
         *
         * This does NOT affect OSHI-to-OSHI over LoRa, which is what the app
         * itself sends: those ride OSHI chunks (portnum 256) carrying a real
         * envelope and already route correctly without this map. Only interop
         * with the stock Meshtastic app depends on the switch.
         *
         * __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ DEFAULT ON, and
         * the condition this comment set has been met rather than waived.
         *
         * Raw interop text is now stored as [DeliveryMethod.LORA_PLAINTEXT],
         * distinct from the [DeliveryMethod.LORA] an OSHI envelope gets, and
         * the bubble draws an amber "LoRa · unverified" badge for it in all 34
         * languages. So the packet can be filed in the contact's thread without
         * the UI claiming anything the ratchet did not prove: the reader sees
         * the message where they expect it AND sees that it is unauthenticated.
         *
         * The owner's report is what this serves: a LoRa message from someone
         * you already talk to was landing under `lora!<nodehex>` instead of in
         * the conversation you already have with them. OSHI-to-OSHI over LoRa
         * never had that problem — it carries a real envelope and always routed
         * correctly. Only the stock-Meshtastic lane did.
         *
         * Still unchanged: binding itself must be proved before it is used.
         * `publicKey()` returns null for an ambiguous binding and this falls
         * back to the `lora!<nodehex>` key, because an unresolved identity is
         * not an excuse to guess one.
         */
        @Volatile
        @JvmStatic
        var adoptRawTextIntoContactThread: Boolean = true

        /**
         * The persisted key for a node number: a fixed 8-digit hex rendering,
         * stable across launches, devices and OS versions. iOS `:111-113`.
         */
        @JvmStatic
        fun nodeKey(num: Long): String =
            String.format(Locale.US, "%08x", num and 0xFFFFFFFFL)

        /**
         * The conversation key raw text has always used when the node is unknown,
         * and still uses. Existing `lora!…` conversations keep working precisely
         * because this never changed. iOS `:118-120`.
         *
         * No `@JvmStatic`: the instance method of the same name would collide
         * with the generated static on the outer class.
         */
        fun fallbackConversationKey(node: Long): String = FALLBACK_PREFIX + nodeKey(node)

        /** True for the synthetic node-keyed sender ids this file produces. iOS `:123-125`. */
        @JvmStatic
        fun isFallbackConversationKey(key: String): Boolean = key.startsWith(FALLBACK_PREFIX)

        /**
         * The reverse of [fallbackConversationKey]: the node number a
         * `lora!<nodehex>` key encodes, or null when [key] is not one.
         *
         * Needed to REPLY — a pseudo-identity has no key to encrypt to, only a
         * node number to address plaintext at, and the node number is not
         * stored anywhere separately: it IS the hex in the key, by construction
         * above. iOS `LoRaNodeIdentityMap.swift` mirror alongside
         * `fallbackConversationKey`.
         */
        @JvmStatic
        fun nodeNumFromFallbackKey(key: String): Long? {
            if (!isFallbackConversationKey(key)) return null
            return key.removePrefix(FALLBACK_PREFIX).toLongOrNull(16)
        }

        private const val FALLBACK_PREFIX = "lora!"

        /**
         * A cheap sanity filter so a synthetic key, an empty string or a
         * truncated fragment can never become a binding. Real OSHI identity keys
         * are base64 Curve25519 (44 chars); 16 is a floor, not a format check.
         * iOS `:130-136`.
         */
        @JvmStatic
        fun looksLikeIdentityKey(key: String): Boolean {
            val trimmed = key.trim()
            if (trimmed.length < 16) return false
            if (isFallbackConversationKey(trimmed)) return false
            if (trimmed.contains("!")) return false
            return true
        }

        /**
         * Fold base64url/padding variants before comparing, so the same key
         * spelled two ways does not read as a conflict and lock a node out of
         * routing. Mirrors `MeshtasticManager.normalizeKey` (iOS `:469-474`) and
         * the blocked/stale key normalisation used elsewhere in the app.
         */
        @JvmStatic
        fun normalizeKey(key: String): String =
            key.replace('-', '+').replace('_', '/').replace("=", "").trim()
    }
}

/**
 * Where the map's JSON lives. An interface so the rules above are testable on a
 * plain JVM — the same reason iOS injects a `UserDefaults` suite
 * (`LoRaNodeIdentityMap.swift:92-94`).
 */
interface LoRaIdentityStore {
    fun read(): String?
    fun write(json: String)
}

class InMemoryLoRaIdentityStore(initial: String? = null) : LoRaIdentityStore {
    @Volatile
    private var blob: String? = initial
    override fun read(): String? = blob
    override fun write(json: String) {
        blob = json
    }
}

class SharedPrefsLoRaIdentityStore(context: Context) : LoRaIdentityStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(LoRaNodeIdentityMap.PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(LoRaNodeIdentityMap.KEY_BINDINGS, null)

    override fun write(json: String) {
        prefs.edit().putString(LoRaNodeIdentityMap.KEY_BINDINGS, json).apply()
    }
}
