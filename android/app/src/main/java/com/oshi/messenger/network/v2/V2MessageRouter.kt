package com.oshi.messenger.network.v2

import android.content.Context
import android.util.Base64
import android.util.Log
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.OSHICryptoV2.X25519Pair
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One decrypted inbound V2 message, mirroring iOS `enum V2IncomingMessage`
 * (V2MessageRouter.swift:79-83). `groupId` is non-null for group traffic and MUST
 * be routed to the group funnel — iOS sends group payloads as bare
 * base64(JSON GroupMessage) with no `👥GROUP_MESSAGE👥` prefix
 * (V2MessageRouter.swift:574-603, delivered MessageManager+V2.swift:819-830), so a
 * dropped `groupId` lands them as base64 garbage in the 1:1 thread.
 */
/**
 * The responder could not derive the X3DH shared secret for an inbound first message.
 *
 * These are TRANSIENT, and that is the whole point of them being exceptions: the peer
 * may republish, our SPK may be the one the NEXT envelope names, and the one-time
 * prekey may be findable after a store reload. Returning null instead told [poll] the
 * envelope was undecryptable-forever, which acks it — and the very first message from
 * a new contact was then gone with no error, no placeholder and no way back.
 * iOS throws in both cases (`OSHI/V2MessageRouter.swift:461-465`, `:533`) and lets the
 * bounded budget hold the seq for 8 pulls.
 */
class V2X3DHException(message: String) : Exception(message)

sealed interface V2Inbound {
    val from: String
    val msgId: String
    val ts: Long
    val groupId: String?

    data class Text(
        override val from: String,
        override val msgId: String,
        override val ts: Long,
        override val groupId: String?,
        val text: String,
    ) : V2Inbound

    /** A `kind == "v2file"` payload whose blob has already been fetched + opened. */
    data class File(
        override val from: String,
        override val msgId: String,
        override val ts: Long,
        override val groupId: String?,
        /**
         * The decrypted plaintext, ON DISK — never a `ByteArray`.
         *
         * The download opens one 2 MiB chunk at a time
         * ([OSHICryptoV2Streaming.decryptToStream]); carrying the result as a byte
         * array here forced the router to read the whole file back and made peak
         * heap one WHOLE FILE again, whatever the streaming saved (M-2's last mile).
         *
         * OWNERSHIP: the file belongs to the router and is deleted the moment
         * `onMessageDelivered` returns — that callback is awaited, so a consumer must
         * finish with it inside the call. A consumer that wants to KEEP the bytes
         * moves the file (`MediaAttachmentStore.save(id, file, moveSource = true)`);
         * the router's delete is then a no-op.
         */
        val payload: java.io.File,
        /** Plaintext size as the manifest declared it — no `length()` stat needed. */
        val size: Long,
        val filename: String,
        val mime: String,
        /** iOS `MediaManager.MediaType` raw value, or null → infer from [mime]. */
        val mediaType: String?,
        val isViewOnce: Boolean,
        /** The four validated `reveal*` wire scalars, or null when none was armed. */
        val reveal: V2RevealPlan?,
        /** Group media only: base64 of the GroupMessage JSON with media stripped. */
        val groupMessageB64: String?,
        /**
         * __VIDEO_NOTE_2026_09_24__ Round video message (docs/VIDEO_NOTE_SPEC.md §1): the
         * wire flag AND a video media type. Defaults keep every other constructor valid.
         */
        val videoNote: Boolean = false,
        /** Recorded duration in ms (already clamped to 15000), null when unknown. */
        val durationMs: Int? = null,
        /** __V2_CAPTION_2026_09_27__ Android-extension caption; null from an iPhone. */
        val caption: String? = null,
    ) : V2Inbound {
        /**
         * Whole-file read. For consumers that genuinely need one `ByteArray` —
         * inline-in-the-row media, and the group funnel, which base64s the bytes into
         * a `GroupMessage`. Anything that may be large should move [payload] instead.
         */
        fun readBytes(): ByteArray = payload.readBytes()
    }
}

/**
 * Orchestrates the OSHI V2 transport on top of the verified crypto + the relay
 * clients, matching iOS V2MessageRouter/MessageManager+V2.
 *
 * Everything here is GATED by [V2Config]. Delivery is a callback so this stays
 * decoupled from MessageRepository.
 */
@Singleton
class V2MessageRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: V2Config,
    private val cryptoManager: CryptoManager,
    private val keysClient: V2KeysClient,
    private val messagesClient: V2MessagesClient,
    private val blobClient: V2BlobClient,
    private val sessionStore: V2SessionStore,
    private val prekeyStore: V2PrekeyStore,
    // __PER_DEVICE_MAILBOX_2026_09_23__ dormant unless /v2/config.device_mailbox_enabled.
    private val deviceMailbox: DeviceMailboxManager,
    private val deviceClient: V2DeviceClient,
) {
    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ A message this account sent from ANOTHER of its devices
     * (`kind:"sent-copy"`, CLIENT_SPEC §3.6). Insert it as an OUTGOING message, status sent: no
     * notification, no receipt, no control processing; dedupe by msgId. Awaited before the ack.
     */
    var onSentCopyDelivered: (suspend (com.oshi.messenger.network.v2.devicemailbox.SentCopy, Long) -> Unit)? = null

    /**
     * Invoked for each decrypted inbound message. SUSPENDING and AWAITED inside
     * [poll] before the relay is ACKed — so a message is durably handled (persisted)
     * before we tell the relay to drop it. A fire-and-forget callback would let poll
     * ack (and the relay discard) the envelope before it was ever written to the DB.
     */
    var onMessageDelivered: (suspend (V2Inbound) -> Unit)? = null

    /**
     * __RESEND_LIST_ANDROID_2026_10_12__ A peer named the messages its ratchet could not
     * open and is asking for them back. The router has no access to the message store, so
     * serving the request belongs to `MessageRepository` — see its handler for the ownership
     * guard, which is a SECURITY guard: the ids are a value the peer chooses.
     *
     * NOT suspending, on purpose: the handler launches its own job (iOS does the same —
     * `Task { await self?.v2ResendRequested(…) }`, MessageManager+V2.swift:1975). Awaiting up
     * to 32 sends here would hold [poll] — and therefore the ack for every envelope behind
     * this one in the same batch — on traffic that the PEER asked for.
     */
    var onResendRequested: ((String, List<String>) -> Unit)? = null

    private val EMPTY = ByteArray(0)
    private val pollMutex = Mutex()
    // Serializes the compound load→ratchet→save on the session store across BOTH the
    // send path and inbound handling. @Synchronized on the store only makes each single
    // load/save atomic; without this a concurrent send + poll on the same peer could
    // interleave their read-modify-write and clobber the ratchet counters → AES-GCM
    // (key,nonce) reuse on send, or a dropped receive-chain advance → permanent decrypt
    // failure. Held ONLY around the crypto, never around network I/O.
    private val ratchetMutex = Mutex()
    // Insertion-ordered dedup of delivered msgIds (bounded), PERSISTED so a crash
    // between deliver and ack cannot resurface the same message as a duplicate bubble.
    // iOS keeps the same set in its V2SyncState (MessageManager+V2.swift:190,209-218).
    private val seenMsgIds: LinkedHashSet<String> by lazy { loadSeenMsgIds() }
    // Files whose ratchet payload decrypted but whose BLOB download failed. Keyed by
    // msgId so the next poll retries the DOWNLOAD only: the ratchet already advanced,
    // so re-decrypting the same envelope can never succeed (iOS un-marks it as seen
    // and re-decrypts — MessageManager+V2.swift:735 — which always lands in `.failed`).
    //
    // PERSISTED, and it has to be: [seenMsgIds] is persisted, so after a process death
    // mid-download the re-pulled envelope hit `if (!seenMsgIds.add(…)) continue` BEFORE
    // anything could hold the seq — the ack then advanced past it and the photo was gone
    // for good. That is the download half of audit M-5 ("transfers die with the
    // process"): the relay was still holding the envelope, and the only thing missing
    // was the key material to ask for the blob again.
    private val pendingFiles: LinkedHashMap<String, V2FileKeyMessage> by lazy { loadPendingFiles() }
    private val seqPrefs get() = context.getSharedPreferences("v2_router", Context.MODE_PRIVATE)
    private val pendingEnvelopes by lazy { V2PendingEnvelopeStore(context.filesDir) }
    private val outboundMutex = Mutex()

    /**
     * Ledger of in-flight blob SENDS, so an interrupted upload resumes into the same
     * reservation instead of restarting from byte 0 (audit M-4). Pruned once on first
     * use: expired records name blobs the server has already dropped, and every one of
     * them holds a file key on disk for nothing.
     */
    private val resumeStore: com.oshi.messenger.service.media.V2SendResumeStore by lazy {
        com.oshi.messenger.service.media.V2SendResumeStore(context.filesDir).also {
            try { it.prune() } catch (e: Throwable) { Log.w(TAG, "resume prune skipped: ${e.message}") }
        }
    }

    /** Bounded hold-the-ack budget, persisted across process restarts (iOS parity). */
    private val retryBudget = V2RetryBudget(object : V2RetryBudget.Store {
        override fun read(): Map<String, V2RetryBudget.Entry> {
            val raw = seqPrefs.getString(RETRY_KEY, null) ?: return emptyMap()
            return try {
                val o = org.json.JSONObject(raw)
                val out = LinkedHashMap<String, V2RetryBudget.Entry>(o.length())
                val now = System.currentTimeMillis()
                for (k in o.keys()) {
                    // __BUDGET_REFUND_DEADLOCK_2026_10_20__ The old shape was a bare
                    // attempt count. Read it as "first failed now" rather than
                    // discarding it: an install upgrading mid-backlog keeps its
                    // attempts and starts the twenty-minute ceiling from the upgrade,
                    // which releases its stuck ack one hold later instead of never.
                    val v = o.opt(k)
                    out[k] = when (v) {
                        is org.json.JSONObject ->
                            V2RetryBudget.Entry(v.optInt("n", 0), v.optLong("t", now))
                        is Number -> V2RetryBudget.Entry(v.toInt(), now)
                        else -> continue
                    }
                }
                out
            } catch (_: Exception) { emptyMap() }
        }

        override fun write(entries: Map<String, V2RetryBudget.Entry>) {
            val o = org.json.JSONObject()
            for ((k, e) in entries) {
                o.put(k, org.json.JSONObject().put("n", e.attempts).put("t", e.firstFailedAt))
            }
            seqPrefs.edit().putString(RETRY_KEY, o.toString()).apply()
        }
    }, object : V2RetryBudget.Stall {
        // __BUDGET_REFUND_DEADLOCK_2026_10_20__ Its own key, never touched by the
        // eviction above — that separation IS the guarantee.
        override fun since(): Long = seqPrefs.getLong(V2RetryBudget.STALL_KEY, 0L)
        override fun set(millis: Long) {
            seqPrefs.edit().putLong(V2RetryBudget.STALL_KEY, millis).apply()
        }
    })

    /**
     * __RESEND_LIST_ANDROID_2026_10_12__ Envelopes this device gave up on, so the heal that
     * follows can name them. Written where the retry budget is exhausted, cleared wherever
     * [V2RetryBudget.clear] is called.
     */
    private val unreadable = V2UnreadableStore(prefsStore(UNREADABLE_KEY))

    /** Sender-side brakes on serving a resend request (attempts per id, hourly per peer). */
    internal val resendBudget = V2ResendBudget(prefsStore(RESEND_BUDGET_KEY))

    private fun prefsStore(key: String) = object : V2ResendState.Store {
        override fun read(): String? = seqPrefs.getString(key, null)
        override fun write(json: String) { seqPrefs.edit().putString(key, json).apply() }
    }

    init {
        // __V2_PADDING_2026_11_09__ per-session padding capability, persisted next to the
        // other router ledgers (V2KeysClient / V2DeviceClient write into it on bundle fetch).
        V2PaddingPeers.attach(object : V2PaddingPeers.Store {
            override fun read(): String? = seqPrefs.getString(PADDING_SLOTS_KEY, null)
            override fun write(json: String) { seqPrefs.edit().putString(PADDING_SLOTS_KEY, json).apply() }
        })
    }

    /**
     * Peers proven able to read a resend list, by the CAPABLE spelling of an acknowledgement
     * id they sent us. The bundle capability ([GroupLegacyCompat.CAP_RESEND_LIST]) is the
     * other, better source; this one is kept because it stays true for a peer whose bundle
     * predates its own update (published without `caps`, therefore read as 0).
     */
    private fun notePeerResendCapable(peer: String) {
        // Folded through the SAME funnel `GroupLegacyCompat.capsOf` uses. Standard base64 and
        // base64url land in different map entries otherwise, and the two sources read in
        // [peerUnderstandsResendList] would disagree about the same contact — the defect
        // already found in `PeerVersionStore` and in the mesh's "is this frame for me?" test.
        val c = com.oshi.messenger.service.GroupUpdateAuthorizer.canonicalIdentity(peer)
        val cur = seqPrefs.getStringSet(RESEND_CAPABLE_KEY, emptySet()) ?: emptySet()
        if (cur.contains(c)) return
        val next = LinkedHashSet(cur).apply { add(c) }
        val bounded = if (next.size > SEEN_CAP) LinkedHashSet(next.toList().takeLast(SEEN_CAP)) else next
        seqPrefs.edit().putStringSet(RESEND_CAPABLE_KEY, bounded).apply()
    }

    /** iOS `peerUnderstandsResendList` — two sources, bundle caps first. */
    private fun peerUnderstandsResendList(peer: String): Boolean {
        if (GroupLegacyCompat.hasResendList(GroupLegacyCompat.capsOf(peer))) return true
        val c = com.oshi.messenger.service.GroupUpdateAuthorizer.canonicalIdentity(peer)
        return seqPrefs.getStringSet(RESEND_CAPABLE_KEY, emptySet())?.contains(c) == true
    }

    private fun myIdentity(): X25519Pair =
        cryptoManager.getOrCreateKeyPair().let { X25519Pair(it.first, it.second) }

    /**
     * Bundles fetched by [ensureCapable], waiting for the send that follows.
     *
     * `GET /v2/keys/:peer` POPS a one-time prekey server-side on every call, so the
     * capability probe and the first send have to share ONE fetch or every new
     * conversation drains the peer's pool twice as fast. iOS keeps the same cache
     * (`OSHI/V2MessageRouter.swift:148,336,358-363`) and evicts it on use, because the
     * bundle is single-use: its OPK is gone from the server.
     */
    private val bundleCache = java.util.concurrent.ConcurrentHashMap<String, FetchedBundle>()

    private suspend fun fetchBundleCached(peerUserKey: String): FetchedBundle? =
        bundleCache[peerUserKey] ?: keysClient.fetchBundle(peerUserKey)?.also { bundleCache[peerUserKey] = it }

    /** Consume the cached bundle, or fetch a fresh one. Never reuses a bundle twice. */
    private suspend fun takeBundle(peerUserKey: String): FetchedBundle? =
        bundleCache.remove(peerUserKey) ?: keysClient.fetchBundle(peerUserKey)

    /** True if V2 is enabled AND we can reach the peer over V2 (existing session or a fetchable bundle). */
    suspend fun ensureCapable(peerUserKey: String): Boolean {
        if (!config.isEnabledCached()) return false
        if (sessionStore.has(peerUserKey)) return true
        return fetchBundleCached(peerUserKey) != null
    }

    /** Refresh the V2 rollout gate (TTL-bounded network call). Driven by the poll loop. */
    suspend fun refreshConfig() = config.refresh(cryptoManager.getPublicKeyString())

    /**
     * Send [plaintext] to [peerUserKey] over V2. Returns true if it was sent via V2;
     * false means the caller should fall back to the legacy path (V2 disabled, peer
     * has no bundle, or the relay rejected it).
     *
     * [msgId] carries the APP's stable message id onto the envelope so one logical
     * message keeps ONE id across every transport (mesh / IPFS / v2) and the
     * receiver's cross-transport dedup works — iOS does the same
     * (V2MessageRouter.swift:539-561).
     */
    /**
     * @param originalTimestampMs the time the message is DATED — i.e. the moment the
     *   user wrote it, in Unix millis — not the moment this send happens. It becomes
     *   the envelope's `ts`, which is the only clock the recipient has: iOS reads it
     *   straight into the bubble's date (`MessageManager+V2.swift:1256`,
     *   `Date(timeIntervalSince1970: Double(env.ts) / 1000)`).
     *
     *   __RESEND_TIMESTAMP_2026_09_17__ Without it the envelope was stamped
     *   `System.currentTimeMillis()` HERE, inside the encrypt — after the contact
     *   upsert, the local insert, the off-grid probe and, on a first message to a new
     *   peer, a network round-trip for their prekey bundle. The sender's own row keeps
     *   the compose time, so the two clients disagree about when the same message
     *   happened, and the recipient sorts it by the later one: a text written before
     *   the ones around it lands at the BOTTOM of the thread as the newest. A retry
     *   made it days, not seconds — the point of a resend is to deliver the message
     *   that was written, not to write a new one. iOS took the same parameter on
     *   2026-08-28 (`V2MessageRouter.swift:805-822`); this is Android catching up.
     *
     *   Null keeps the old behaviour (stamp now), which is right for control traffic
     *   that has no user-facing date — key rotation, ratchet-heal — and is what the
     *   internal callers below rely on.
     */
    suspend fun sendText(
        peerUserKey: String,
        plaintext: ByteArray,
        msgId: String? = null,
        originalTimestampMs: Long? = null,
    ): Boolean = outboundMutex.withLock {
        sendTextLocked(peerUserKey, plaintext, msgId, originalTimestampMs)
    }

    // ==================================================================
    // __GROUP_E2E_V2_2026_09_23__ groups over v2 ONLY (docs/GROUP_E2E_V2_SPEC.md)
    // ==================================================================

    /** Outcome of one group fan-out: who got it, and who has no v2 bundle at all (spec §6). */
    data class GroupSendResult(val handled: Set<String>, val unreachable: Set<String>)

    /**
     * Whether [peerUserKey] can receive v2 at all: a session, a per-device list, or an account
     * bundle.
     *
     * __NO_BUNDLE_IS_NOT_A_TIMEOUT_2026_11_02__ Three states, not two. This used to be a
     * `Boolean`, and `false` fed straight into [GroupSendResult.unreachable], which the group
     * screen renders as **"<name> must update OSHI to receive this group's messages"** — a
     * sentence naming a real person. A socket timeout, a 502 and a phone with no signal all
     * produced that `false`, and every `/v2` route was measured at 7-20 s with ~23 % timeouts,
     * so the accusation landed on innocent members constantly. [Unknown] now says "ask again
     * next send" and is shown to nobody.
     */
    enum class V2Reach { REACHABLE, NO_BUNDLE, UNKNOWN }

    suspend fun v2Reach(peerUserKey: String): V2Reach {
        // The gate being shut is a fact about THIS phone, never about the peer.
        if (!config.isEnabledCached()) return V2Reach.UNKNOWN
        if (sessionStore.has(peerUserKey)) return V2Reach.REACHABLE
        if (deviceMailbox.deviceModeActive()) {
            val devices = try { deviceMailbox.devicesOf(peerUserKey) } catch (_: Exception) { null }
            if (!devices.isNullOrEmpty()) return V2Reach.REACHABLE
        }
        bundleCache[peerUserKey]?.let { return V2Reach.REACHABLE }
        return when (val r = keysClient.fetchBundleOutcome(peerUserKey)) {
            is V2KeysClient.BundleFetch.Found -> {
                bundleCache[peerUserKey] = r.bundle
                V2Reach.REACHABLE
            }
            V2KeysClient.BundleFetch.Missing -> V2Reach.NO_BUNDLE
            V2KeysClient.BundleFetch.Unknown -> V2Reach.UNKNOWN
        }
    }

    /** Kept for the callers that only need "can I send over v2 right now". */
    suspend fun isV2Reachable(peerUserKey: String): Boolean =
        v2Reach(peerUserKey) == V2Reach.REACHABLE

    /**
     * Group CONTENT fan-out (spec §2): one ratchet envelope per member (per member device in device
     * mode), `type:"group"` + [groupId], the SAME [msgId] for every member (the relay keys
     * `(to, msgId)`; receivers dedup on the inner GroupMessage id). [plaintext] is the exact
     * content-channel plaintext: base64(JSON GroupMessage) or a `v2file` key JSON.
     *
     * [selfCopy] = one `sent-copy` to each of our OTHER devices, once per message (spec §7.2).
     */
    suspend fun sendGroupText(
        groupId: String,
        members: List<String>,
        plaintext: ByteArray,
        msgId: String,
        originalTimestampMs: Long? = null,
        selfCopy: Boolean = false,
    ): GroupSendResult {
        // __NO_BUNDLE_IS_NOT_A_TIMEOUT_2026_11_02__ `unreachable` is NOT "we did
        // not send". It is the list the group screen turns into "<name> must update
        // OSHI", so it may only ever hold members the SERVER said it has no bundle
        // for. Refusing to use the internet — the rollout gate being shut, or this
        // conversation being mesh-only — is a fact about this phone, and naming
        // every member of the group because of it was the whole of the bug report.
        if (!config.isEnabledCached()) return GroupSendResult(emptySet(), emptySet())
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ and for THIS conversation.
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context, groupId)) {
            // See __NO_BUNDLE_IS_NOT_A_TIMEOUT_2026_11_02__ above: not sending is
            // not the same as the member being unable to receive.
            return GroupSendResult(emptySet(), emptySet())
        }
        val me = cryptoManager.getPublicKeyString()
        val meCanon = com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(me)
        val handled = LinkedHashSet<String>()
        val unreachable = LinkedHashSet<String>()
        // __V2_PADDING_2026_11_09__ one member that cannot strip padding ⇒ no member's copy is padded.
        val padGate = V2PaddingPeers.groupAllows(members, me)
        for (member in members.distinct()) {
            if (member.isBlank() ||
                com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(member) == meCanon) continue
            val ok = try {
                outboundMutex.withLock { sendTextLocked(member, plaintext, msgId, originalTimestampMs, groupId, padGate) }
            } catch (e: Exception) {
                Log.w(TAG, "group send to ${member.take(8)} failed: ${e.message}"); false
            }
            if (ok) handled += member
            else if (v2Reach(member) == V2Reach.NO_BUNDLE) unreachable += member
        }
        if (selfCopy && handled.isNotEmpty() && deviceMailbox.deviceModeActive()) {
            try {
                val ts = originalTimestampMs ?: System.currentTimeMillis()
                val iso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                    java.time.Instant.ofEpochMilli(ts).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                val copy = com.oshi.messenger.network.v2.devicemailbox.SentCopy(
                    null, groupId, String(plaintext, Charsets.UTF_8), msgId, iso).encode()
                outboundMutex.withLock { sendFannedOut(me, copy, msgId, ts, me) }
            } catch (e: Exception) {
                Log.w(TAG, "group self copy failed: ${e.message}")
            }
        }
        Log.i(TAG, "DIAG_V2_GROUP_TX gid=${groupId.take(8)} ok=${handled.size} unreachable=${unreachable.size} of=${members.size}")
        return GroupSendResult(handled, unreachable)
    }

    /**
     * Group MEDIA fan-out (spec §4): the bytes are sealed ONCE (one file key, one nonce, identical
     * chunks) and PUT into one blob reservation PER MEMBER (the blob store authorises exactly one
     * recipient); each member then gets a `type:"group"` envelope whose plaintext is the `v2file`
     * key JSON carrying [groupMessageB64] (the GroupMessage with its media stripped).
     * Over the cap ⇒ nobody (never inline, never IPFS).
     */
    suspend fun sendGroupFile(
        groupId: String,
        members: List<String>,
        data: ByteArray,
        filename: String,
        mime: String,
        mediaType: String?,
        groupMessageB64: String,
        msgId: String,
        // __VIDEO_NOTE_2026_09_24__ docs/VIDEO_NOTE_SPEC.md §2.B (top level of the v2file JSON).
        videoNote: Boolean = false,
        durationMs: Int? = null,
    ): GroupSendResult {
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ and for THIS conversation.
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context, groupId)) {
            // See __NO_BUNDLE_IS_NOT_A_TIMEOUT_2026_11_02__ above: not sending is
            // not the same as the member being unable to receive.
            return GroupSendResult(emptySet(), emptySet())
        }
        if (!config.isEnabledCached() || data.isEmpty() || data.size > V2BlobClient.MAX_PLAINTEXT_BYTES) {
            return GroupSendResult(emptySet(), emptySet())
        }
        val me = cryptoManager.getPublicKeyString()
        val meCanon = com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(me)
        // __MEDIA_PADDING_2026_11_10__ ONE encryption is shared by every member's blob (same key,
        // same nonce), so it is padded only when EVERY other member's devices read padded media.
        val padMedia = V2PaddingPeers.groupMediaAllows(
            members.distinct().filter { it.isNotBlank() }.associateWith { mediaPaddingAllowedFor(it) }, me)
        val enc = try { OSHICryptoV2.encryptFile(data, filename, mime, pad = padMedia) } catch (e: Exception) {
            Log.w(TAG, "group file encrypt failed: ${e.message}"); return GroupSendResult(emptySet(), emptySet())
        }
        val handled = LinkedHashSet<String>()
        val unreachable = LinkedHashSet<String>()
        // __V2_PADDING_2026_11_09__ one member that cannot strip padding ⇒ no member's key message is padded.
        val padGate = V2PaddingPeers.groupAllows(members, me)
        for (member in members.distinct()) {
            if (member.isBlank() ||
                com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(member) == meCanon) continue
            // __NO_BUNDLE_IS_NOT_A_TIMEOUT_2026_11_02__ only a SERVER-confirmed
            // absence of a bundle may be reported as "this member must update";
            // UNKNOWN skips the member for this send and says nothing about them.
            when (v2Reach(member)) {
                V2Reach.NO_BUNDLE -> { unreachable += member; continue }
                V2Reach.UNKNOWN -> continue
                V2Reach.REACHABLE -> Unit
            }
            try {
                val blobId = blobClient.uploadBlob(recipientKey = member, chunks = enc.chunks) ?: continue
                val key = V2FileKeyMessage(
                    blobId = blobId,
                    fileKey = enc.fileKey,
                    fileNonce = enc.fileNonce,
                    chunkCount = enc.chunks.size,
                    manifest = enc.manifest,
                    filename = filename,
                    mime = mime,
                    mediaType = mediaType,
                    isViewOnce = null,
                    revealId = null, revealLayerIndex = null, revealLayerCount = null, revealTotalMs = null,
                    videoNote = if (videoNote) true else null,
                    durationMs = if (videoNote) durationMs else null,
                    groupMessage = groupMessageB64,
                )
                val ok = outboundMutex.withLock { sendTextLocked(member, key.toBytes(), msgId, null, groupId, padGate) }
                if (ok) handled += member
            } catch (e: Exception) {
                Log.w(TAG, "group blob for ${member.take(8)} failed: ${e.message}")
            }
        }
        Log.i(TAG, "DIAG_V2_GROUP_FILE_TX gid=${groupId.take(8)} ok=${handled.size} unreachable=${unreachable.size} of=${members.size}")
        return GroupSendResult(handled, unreachable)
    }

    private suspend fun sendTextLocked(
        peerUserKey: String,
        plaintext: ByteArray,
        msgId: String?,
        originalTimestampMs: Long? = null,
        // __GROUP_E2E_V2_2026_09_23__ non-null ⇒ a group CONTENT envelope (docs/GROUP_E2E_V2_SPEC.md §2.1):
        // type "group" + groupId, no per-member self copy (one per message, see [sendGroupText]).
        groupId: String? = null,
        // __V2_PADDING_2026_11_09__ the caller's group rule (false = a member cannot strip
        // padding); the per-session capability is checked at the encrypt, where the slot is known.
        padGate: Boolean = true,
    ): Boolean {
        if (!config.isEnabledCached()) return false      // hot path: no network on the gate
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ and for THIS conversation.
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context, (groupId ?: peerUserKey))) return false
        val myUserKey = cryptoManager.getPublicKeyString()
        // __PER_DEVICE_MAILBOX_2026_09_23__ Updated sender (CLIENT_SPEC §3.5): one copy per recipient
        // device + self copies, as ONE batch. null = could not use the device path → today's path.
        if (deviceMailbox.deviceModeActive()) {
            try {
                sendFannedOut(peerUserKey, plaintext, msgId, originalTimestampMs, myUserKey, groupId, padGate)?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "device fan-out failed, using the account path: ${e.message}")
            }
        }
        try {
            // An ambiguous network failure retains the EXACT original ciphertext,
            // including the first-message X3DH header, across process restarts.
            if (msgId != null) {
                pendingEnvelopes.load(myUserKey, peerUserKey, msgId, plaintext)?.let { saved ->
                    val accepted = messagesClient.send(V2Envelope.fromJson(saved)).contains(msgId)
                    if (accepted) pendingEnvelopes.remove(myUserKey, peerUserKey, msgId)
                    return accepted
                }
            }
            // Bundle fetch (network) happens OUTSIDE the ratchet lock so a slow fetch
            // can't block an in-flight receive on another peer.
            val existing = sessionStore.load(peerUserKey)
            val bundle = if (existing == null) {
                takeBundle(peerUserKey) ?: return false // unavailable → legacy
            } else null

            // Compound ratchet op (load→encrypt→save) under ratchetMutex so it can't
            // interleave with poll()'s inbound handling for the same session.
            val env = ratchetMutex.withLock {
                // Re-load inside the lock: a concurrent inbound may have just established
                // or advanced this session while we were fetching the bundle.
                val current = sessionStore.load(peerUserKey)
                val session = when {
                    current != null -> current
                    bundle != null -> {
                        // Establish as initiator EXACTLY ONCE — each initiator() call mints
                        // fresh ephemeral keys, so state and x3dh must come from one call.
                        val init = V2Session.initiator(myIdentity(), bundle)
                        V2SessionStore.Record(
                            peerUserKey = peerUserKey,
                            role = V2SessionStore.Role.INITIATOR,
                            ratchet = init.state,
                            peerIdentityKey = bundle.identityKey,
                            peerSigningKey = bundle.signingKey,
                            pendingInitiatorHeader = init.x3dh,
                        )
                    }
                    else -> return@sendTextLocked false        // no session and no bundle → legacy
                }
                // The X3DH header rides message #1 ONLY, then is dropped — iOS clears it
                // inside the same locked read-modify-write (V2MessageRouter.swift:588-593).
                // Re-attaching it on #2..#n re-names an ephemeral + a one-time prekey that
                // iOS BURNED on #1, so its [RATCHET-H1] recovery — the thing that exists to
                // heal a desync — throws `unknownOneTimePreKey` and the conversation can
                // never recover.
                val x3dh = session.pendingInitiatorHeader
                session.pendingInitiatorHeader = null
                // __V2_PADDING_2026_11_09__ relay envelope only — see V2Padding.kt.
                val sealed = if (V2Padding.shouldPad(padGate, V2PaddingPeers.slotCapable(peerUserKey, null)))
                    V2Padding.pad(plaintext) else plaintext
                val (header, ct) = OSHIRatchetV2.encrypt(session.ratchet, sealed, EMPTY)
                // Persist the ADVANCED ratchet BEFORE the network send (matches iOS
                // saveSession-before-send). If we saved only on success, a failed send
                // would rewind cks/ns; the next send would re-derive the SAME message
                // key + SAME 12-byte GCM nonce for a DIFFERENT plaintext — a catastrophic
                // AES-256-GCM (key,nonce) reuse. A failed send just means this seq goes
                // out via the legacy path instead; the peer harmlessly skips it.
                sessionStore.save(session)
                V2Envelope(
                    msgId = msgId ?: UUID.randomUUID().toString(),
                    from = myUserKey,
                    to = peerUserKey,
                    type = if (groupId != null) TYPE_GROUP else "1to1",
                    groupId = groupId,
                    x3dh = x3dh,                          // msg #1 only
                    header = OSHICryptoV2.encodeHeader(header),
                    ciphertext = ct,
                    // __RESEND_TIMESTAMP_2026_09_17__ The message's OWN date, not the
                    // instant we happened to reach the encrypt. See `sendText`.
                    ts = originalTimestampMs ?: System.currentTimeMillis(),
                )
            }
            // Network send is OUTSIDE the lock (slow I/O).
            if (msgId != null) pendingEnvelopes.save(myUserKey, peerUserKey, msgId, plaintext, env.toJson())
            val accepted = messagesClient.send(env).contains(env.msgId)
            if (accepted && msgId != null) pendingEnvelopes.remove(myUserKey, peerUserKey, msgId)
            return accepted
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed, falling back to legacy: ${e.message}")
            return false
        }
    }

    // ==================================================================
    // __PER_DEVICE_MAILBOX_2026_09_23__ sender fan-out (CLIENT_SPEC §3.5, §3.6)
    // ==================================================================

    @Volatile private var lastDeviceMaintainAt = 0L

    /** Registration / bundle top-up / device-list watch, at most every 10 min (1 min while unregistered). */
    private suspend fun maybeMaintainDeviceMailbox() {
        if (!deviceMailbox.flagOn()) return
        val every = if (deviceMailbox.deviceModeActive()) 10 * 60_000L else 60_000L
        val now = System.currentTimeMillis()
        if (now - lastDeviceMaintainAt < every) return
        lastDeviceMaintainAt = now
        // __MAILBOX_BOOTSTRAP_2026_09_23__ A brand-new identity whose first account publish has
        // not landed yet (the one-shot publish at startup failed or has not run) has no TOFU
        // binding: every /v2/devices route answers 401 unbound-grace. Publish (which then
        // registers) instead of retrying a registration that cannot succeed.
        if (!prekeyStore.hasPublished()) {
            try { publishBundleIfEnabled() } catch (e: Exception) { Log.w(TAG, "bootstrap publish failed: ${e.message}") }
            return
        }
        try { deviceMailbox.maintain() } catch (e: Exception) { Log.w(TAG, "device mailbox maintain failed: ${e.message}") }
    }

    /**
     * __LEGACY_READER_2026_09_28__ Remember whether [peer]'s live install writes (and therefore
     * reads) the account view — see [com.oshi.messenger.network.v2.devicemailbox.PeerReadView].
     * Persisted: after a restart the first message to that peer must still take the account path.
     */
    private fun notePeerWriteMode(peer: String, toDevice: String?, fromDevice: String?, me: String) {
        val p = com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(peer)
        if (p == com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(me)) return
        val legacy = com.oshi.messenger.network.v2.devicemailbox.PeerReadView.isLegacyWriter(toDevice, fromDevice)
        val key = LEGACY_READER_KEY + p
        val prefs = seqPrefs
        if (prefs.getBoolean(key, false) == legacy) return
        if (legacy) prefs.edit().putBoolean(key, true).apply() else prefs.edit().remove(key).apply()
        Log.i(TAG, "DIAG_PEER_VIEW ${p.take(8)} ${if (legacy) "account (legacy) view — account envelopes" else "device mode — per-device copies"}")
    }

    private fun peerReadsLegacy(peer: String): Boolean = seqPrefs.getBoolean(
        LEGACY_READER_KEY + com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(peer), false)

    /**
     * The self-copy plaintext for a message just sent to [peer], or null when none is due: control
     * traffic (receipts, typing, calls signalling, heal), file-key messages (the blob is deleted after
     * the peer's download; devsync brings media) and notes to self (those ARE the self copies).
     */
    private fun selfCopyFor(peer: String, me: String, plaintext: ByteArray, msgId: String, tsMs: Long): ByteArray? {
        if (com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(peer) ==
            com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(me)) return null
        if (V2FileKeyMessage.parse(plaintext) != null) return null
        val text = String(plaintext, Charsets.UTF_8)
        if (RatchetHealSignal.parse(text) != null || RatchetHealSignal.Resent.parse(text) != null) return null
        if (com.oshi.messenger.data.model.OSHIControlPayload.isSilent(text)) return null
        val iso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(tsMs))
        return com.oshi.messenger.network.v2.devicemailbox.SentCopy(peer, null, text, msgId, iso).encode()
    }

    /**
     * Encrypt [plaintext] under session `(target, device)`, establishing it by X3DH from [bundle] when
     * there is none. Same discipline as the legacy path: the X3DH header rides message #1 only, and
     * the ADVANCED ratchet is saved BEFORE any network send (no AES-GCM key/nonce reuse on retry).
     * Returns null when there is no session and no bundle.
     */
    private suspend fun encryptFor(
        target: String, device: String?, bundle: FetchedBundle?, plaintext: ByteArray,
        msgId: String, tsMs: Long, me: String, fromDevice: String?,
        groupId: String? = null,
        padGate: Boolean = true,
    ): V2Envelope? = ratchetMutex.withLock {
        val session = sessionStore.load(target, device) ?: bundle?.let {
            val init = V2Session.initiator(myIdentity(), it)
            V2SessionStore.Record(target, V2SessionStore.Role.INITIATOR, init.state, it.identityKey, it.signingKey, init.x3dh, device)
        } ?: return@withLock null
        val x3dh = session.pendingInitiatorHeader
        session.pendingInitiatorHeader = null
        // __V2_PADDING_2026_11_09__ per SESSION slot (target, device): each device may run a different build.
        val sealed = if (V2Padding.shouldPad(padGate, V2PaddingPeers.slotCapable(target, device)))
            V2Padding.pad(plaintext) else plaintext
        val (header, ct) = OSHIRatchetV2.encrypt(session.ratchet, sealed, EMPTY)
        sessionStore.save(session)
        V2Envelope(
            msgId = msgId, from = me, to = target, x3dh = x3dh,
            type = if (groupId != null) TYPE_GROUP else "1to1", groupId = groupId,
            header = OSHICryptoV2.encodeHeader(header), ciphertext = ct, ts = tsMs,
            toDevice = device, fromDevice = if (device != null) fromDevice else null,
        )
    }

    /** One per-device copy: session `(target, d)`, bootstrapped from `GET /v2/keys/<target>?device=d`. */
    private suspend fun deviceCopy(target: String, d: String, plaintext: ByteArray, msgId: String, tsMs: Long, me: String, myDeviceId: String, groupId: String? = null, padGate: Boolean = true): V2Envelope? {
        val bundle = if (sessionStore.has(target, d)) null else (deviceClient.fetchDeviceBundle(target, d) ?: return null)
        return encryptFor(target, d, bundle, plaintext, msgId, tsMs, me, myDeviceId, groupId, padGate)
    }

    /**
     * Send one message as per-device copies + self copies in ONE batch. Returns the send verdict, or
     * null when the device path cannot be used for this message (the caller then takes today's
     * account path). A `409 device-list-mismatch` replaces the cached list, drops the stale sessions,
     * rebuilds only the missing copies and retries — at most [FanoutPlanner.MAX_MISMATCH_RETRIES]
     * times, then the peer gets the account envelope (§3.5 step 5).
     */
    private suspend fun sendFannedOut(peer: String, plaintext: ByteArray, msgId: String?, originalTimestampMs: Long?, me: String, groupId: String? = null, padGate: Boolean = true): Boolean? {
        val keys = deviceMailbox.keys() ?: return null
        val myDeviceId = keys.deviceId
        val id = msgId ?: UUID.randomUUID().toString()
        val ts = originalTimestampMs ?: System.currentTimeMillis()
        val pendingKey = "$peer#fanout"

        // A crash/ambiguous failure after the encrypt resends the SAME batch (same ciphertexts).
        if (msgId != null) {
            pendingEnvelopes.load(me, pendingKey, msgId, plaintext)?.let { saved ->
                val envs = saved.optJSONArray("envelopes")?.let { a -> (0 until a.length()).map { V2Envelope.fromJson(a.getJSONObject(it)) } }.orEmpty()
                when (val r = deviceClient.sendBatch(envs)) {
                    is V2DeviceClient.BatchResult.Accepted -> { pendingEnvelopes.remove(me, pendingKey, msgId); return r.msgIds.contains(msgId) }
                    else -> pendingEnvelopes.remove(me, pendingKey, msgId)   // re-plan with fresh copies below
                }
            }
        }

        val isSelfWrite = com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(peer) ==
            com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(me)
        var peerDevices = deviceMailbox.devicesOf(peer) ?: return null
        var ownDevices = if (isSelfWrite) peerDevices else (deviceMailbox.devicesOf(me) ?: emptyList())
        // Group content: the self copy is sent ONCE per message by [sendGroupText], not per member.
        var selfPayload = if (groupId != null) null else selfCopyFor(peer, me, plaintext, id, ts)
        // __LEGACY_READER_2026_09_28__ The peer's live install reads the account view: per-device
        // copies would only reach devices it does not use. Account envelope (+ our self copies).
        var accountFallback = !isSelfWrite && peerReadsLegacy(peer)
        var peerRetries = 0
        var selfRetries = 0
        val envs = LinkedHashMap<String, V2Envelope>()

        repeat(2 * com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner.MAX_MISMATCH_RETRIES + 2) {
            val plan = com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner.plan(
                peer, me, myDeviceId, if (accountFallback) emptyList() else peerDevices,
                if (isSelfWrite || selfPayload != null) ownDevices else emptyList())
            val wanted = HashSet<String>()
            for (d in plan.peerDevices) {
                wanted += "p:$d"
                if (!envs.containsKey("p:$d")) envs["p:$d"] = deviceCopy(peer, d, plaintext, id, ts, me, myDeviceId, groupId, padGate) ?: run {
                    // No bundle for a device the list names: the list is stale. Re-ask once, else legacy.
                    deviceMailbox.replaceDevicesOf(peer, emptyList()); return null
                }
            }
            if (plan.peerDevices.isEmpty() && !isSelfWrite) {
                wanted += "account"
                if (!envs.containsKey("account")) {
                    val bundle = if (sessionStore.has(peer)) null else (takeBundle(peer) ?: return null)
                    envs["account"] = encryptFor(peer, null, bundle, plaintext, id, ts, me, null, groupId, padGate) ?: return null
                }
            }
            val sp = selfPayload
            if (sp != null) for (o in plan.selfDevices) {
                wanted += "s:$o"
                if (!envs.containsKey("s:$o")) {
                    val e = deviceCopy(me, o, sp, id, ts, me, myDeviceId)
                    if (e != null) envs["s:$o"] = e else { selfPayload = null; envs.keys.removeAll { it.startsWith("s:") }; break }
                }
            }
            envs.keys.retainAll { it in wanted }
            if (envs.isEmpty()) return null

            val batch = envs.values.toList()
            if (msgId != null) pendingEnvelopes.save(me, pendingKey, msgId, plaintext,
                org.json.JSONObject().put("envelopes", org.json.JSONArray().apply { batch.forEach { put(it.toJson()) } }))
            when (val r = deviceClient.sendBatch(batch)) {
                is V2DeviceClient.BatchResult.Accepted -> {
                    if (msgId != null) pendingEnvelopes.remove(me, pendingKey, msgId)
                    return r.msgIds.contains(id)
                }
                is V2DeviceClient.BatchResult.Refused -> {
                    if (msgId != null) pendingEnvelopes.remove(me, pendingKey, msgId)
                    if (r.status == 409 && r.error == "device-mailbox-disabled") deviceMailbox.handleOutcome(
                        com.oshi.messenger.network.v2.devicemailbox.DeviceHttpOutcome.MailboxDisabled)
                    Log.w(TAG, "device batch refused (${r.status} ${r.error}) — account path")
                    return null
                }
                is V2DeviceClient.BatchResult.Mismatch -> {
                    if (msgId != null) pendingEnvelopes.remove(me, pendingKey, msgId)
                    val m = r.info
                    val toMe = com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(m.to) ==
                        com.oshi.messenger.network.v2.devicemailbox.DeviceAuth.normalizeIdentity(me)
                    Log.i(TAG, "DIAG_DEVICE_MISMATCH to=${m.to.take(8)} missing=${m.missing.size} stale=${m.stale.size}")
                    deviceMailbox.replaceDevicesOf(if (toMe) me else peer, m.devices)
                    val current = if (toMe && !isSelfWrite) ownDevices else peerDevices
                    val (next, dropped) = com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner.applyMismatch(current, m, toMe, myDeviceId)
                    val prefix = if (toMe && !isSelfWrite) "s:" else "p:"
                    ratchetMutex.withLock { for (d in dropped) sessionStore.delete(if (toMe) me else peer, d) }
                    for (d in dropped) envs.remove(prefix + d)
                    if (toMe && !isSelfWrite) {
                        ownDevices = next
                        if (++selfRetries > com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner.MAX_MISMATCH_RETRIES) {
                            selfPayload = null; envs.keys.removeAll { it.startsWith("s:") }
                        }
                    } else {
                        peerDevices = next
                        if (isSelfWrite) ownDevices = next
                        if (++peerRetries > com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner.MAX_MISMATCH_RETRIES) {
                            if (isSelfWrite) return null
                            accountFallback = true; envs.keys.removeAll { it.startsWith("p:") }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Send a file the way iOS does (V2MessageRouter.swift:610-776 `sendFile`):
     * AEAD-seal the bytes into 2 MiB chunks with a per-file key, upload the opaque
     * chunks to `/v2/blobs`, then send the KEY MATERIAL as an ordinary ratchet
     * message (`{"kind":"v2file",…}`). iOS's receive path recognises that payload
     * and renders a real media bubble; the previous Android behaviour — an inline
     * `{"type":"IMAGE","data":…}` — is a shape iOS's V2 receiver never parses
     * (MessageManager+V2.swift:947-956), so it rendered as a raw-JSON text bubble.
     *
     * Returns false on ANY failure so the caller falls back to the legacy path.
     *
     * RESUME (iOS V2MessageRouter.swift:775-802). Every attempt used to draw a fresh
     * file key AND a fresh reservation, so a 40 MB video interrupted at 95 % restarted
     * from byte 0 and orphaned the half-uploaded blob server-side. [resumeStore] keys a
     * persisted record of {blobId, fileKey, fileNonce} on the send id: a retry
     * re-encrypts with the SAME key material — byte-identical chunks, since the chunk
     * nonces derive deterministically from the file nonce — and hands the old blobId to
     * the uploader, which tops up only what `/status` still reports missing. The record
     * is digest-bound, so key material can never meet a different plaintext; no record,
     * no match or an expired blob simply means "start fresh", the previous behaviour.
     *
     * @param reveal the progressive-reveal position of THIS layer, or null (the normal
     *   case) — all four scalars are then omitted and the payload is byte-identical.
     */
    suspend fun sendFile(
        peerUserKey: String,
        data: ByteArray,
        filename: String,
        mime: String,
        mediaType: String? = null,
        isViewOnce: Boolean = false,
        msgId: String? = null,
        reveal: V2RevealPlan? = null,
        // __VIDEO_NOTE_2026_09_24__ docs/VIDEO_NOTE_SPEC.md §2.A — both omitted unless a note.
        videoNote: Boolean = false,
        durationMs: Int? = null,
        /** __V2_CAPTION_2026_09_27__ see [V2FileKeyMessage.caption]. */
        caption: String? = null,
    ): Boolean {
        if (!config.isEnabledCached()) return false
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ and for THIS conversation.
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context, peerUserKey)) return false
        // User-facing PLAINTEXT limit, checked before encryption (iOS :618).
        if (data.isEmpty() || data.size > V2BlobClient.MAX_PLAINTEXT_BYTES) return false
        // The send id must be PINNED before the ledger is consulted: it is the ledger
        // key, and letting sendText mint one per attempt would make every retry a
        // different send and no resume could ever match (iOS pins it the same way,
        // `let sendId = msgId ?? UUID().uuidString`, :776).
        val sendId = msgId ?: UUID.randomUUID().toString()
        return try {
            // Open the session FIRST. Uploading to a peer we then discover has no
            // bundle would burn the uplink for nothing — and doing it via sendText's
            // own fetch would pop a second server-side one-time prekey.
            if (!ensureSession(peerUserKey)) return false
            // __MEDIA_PADDING_2026_11_10__ Pad only when EVERY device the key message reaches
            // reads padded media; decided BEFORE the ledger, because it is part of the binding.
            val padMedia = mediaPaddingAllowedFor(peerUserKey)
            val digest = com.oshi.messenger.service.media.V2SendResumeStore.digest(data)
            val prior = resumeStore.record(sendId, peerUserKey, digest, padded = padMedia)
            // A record whose chunk count no longer matches (a chunkSize change between
            // builds) cannot address the same blob; "no resume" is always a valid answer.
            // __MEDIA_PADDING_2026_11_10__ counted on the PADDED size when padding.
            val expectedChunks = OSHICryptoV2.mediaChunkCount(data.size.toLong(), padMedia)
            val resume = prior?.takeIf { it.chunkCount == expectedChunks && it.padded == padMedia }
            if (resume != null) {
                Log.d(TAG, "↩️ resuming send ${sendId.take(8)}… into blob ${resume.blobId.take(12)}…")
            }
            val enc = OSHICryptoV2.encryptFile(
                data, filename, mime,
                reuseFileKey = resume?.fileKey,
                reuseFileNonce = resume?.fileNonce,
                pad = padMedia,
            )
            val blobId = blobClient.uploadBlob(
                recipientKey = peerUserKey,
                chunks = enc.chunks,
                resumeBlobId = resume?.blobId,
                // Recorded the instant `reserve` returns, BEFORE the first chunk goes
                // out — the point is to survive a process that dies mid-transfer.
                onReserved = { newBlobId ->
                    resumeStore.put(
                        msgId = sendId,
                        recipientKey = peerUserKey,
                        plaintextSha256 = digest,
                        blobId = newBlobId,
                        fileKey = enc.fileKey,
                        fileNonce = enc.fileNonce,
                        chunkCount = enc.chunks.size,
                        padded = padMedia,
                    )
                },
            ) ?: return false
            val key = V2FileKeyMessage(
                blobId = blobId,
                fileKey = enc.fileKey,
                fileNonce = enc.fileNonce,
                chunkCount = enc.chunks.size,
                manifest = enc.manifest,
                filename = filename,
                mime = mime,
                mediaType = mediaType,
                // iOS writes `true` or omits the key entirely (V2MessageRouter.swift:755).
                isViewOnce = if (isViewOnce) true else null,
                revealId = reveal?.revealId,
                revealLayerIndex = reveal?.layerIndex,
                revealLayerCount = reveal?.layerCount,
                revealTotalMs = reveal?.totalMs,
                videoNote = if (videoNote) true else null,
                durationMs = if (videoNote) durationMs else null,
                groupMessage = null,
                caption = caption?.takeIf { it.isNotBlank() },
            )
            // __ATTACHMENT_DATED_AT_UPLOAD_2026_09_17__ No timestamp: the key
            // message is deliberately dated NOW, i.e. when the upload finished, which
            // is what iOS does (its `sendFile` takes no `originalTimestamp` either). A
            // ten-minute video should arrive as the newest thing in the thread, where
            // the recipient will actually see it.
            val sent = sendText(peerUserKey, key.toBytes(), sendId)
            // Only NOW is the send durable: blob committed AND the key message accepted.
            // Dropping the record earlier would leave a committed blob no retry could
            // find its way back to (iOS :915-918).
            if (sent) resumeStore.remove(sendId, peerUserKey)
            sent
        } catch (e: Exception) {
            Log.w(TAG, "sendFile failed, falling back to legacy: ${e.message}")
            false
        }
    }

    /**
     * __MEDIA_PADDING_2026_11_10__ Whether a blob for [peer] may be padded. Not "the path routing
     * picks today": [sendTextLocked] can fall back from per-device copies to the account envelope
     * (stale list, missing device bundle, mismatch retries exhausted) after the blob is sealed.
     * So the peer's ACCOUNT slot AND every device on its server-side device list must advertise
     * [V2Padding.CAP_MEDIA_PADDING] — asked whatever OUR device mode is, because the peer's
     * installs read the account mailbox either way. A list that cannot be fetched, or any
     * unknown/old slot ⇒ false (unpadded, readable by every build). Relay blob path ONLY — the
     * mesh, LoRa and legacy IPFS paths never call [OSHICryptoV2.encryptFile] with `pad = true`.
     */
    private suspend fun mediaPaddingAllowedFor(peer: String): Boolean = try {
        if (!V2PaddingPeers.mediaSlotCapable(peer, null)) false      // no network for a known no
        else V2PaddingPeers.mediaAllows(peer, deviceMailbox.devicesOf(peer))
    } catch (e: Exception) {
        false
    }

    /**
     * Load-or-establish an initiator session WITHOUT sending. Mirrors the establish
     * half of iOS `loadOrEstablish` (V2MessageRouter.swift:469): the pending X3DH
     * header is persisted and rides whichever message goes out first.
     */
    private suspend fun ensureSession(peerUserKey: String): Boolean {
        if (sessionStore.has(peerUserKey)) return true
        val bundle = takeBundle(peerUserKey) ?: return false
        return ratchetMutex.withLock {
            if (sessionStore.has(peerUserKey)) return@withLock true
            val init = V2Session.initiator(myIdentity(), bundle)
            sessionStore.save(
                V2SessionStore.Record(
                    peerUserKey = peerUserKey,
                    role = V2SessionStore.Role.INITIATOR,
                    ratchet = init.state,
                    peerIdentityKey = bundle.identityKey,
                    peerSigningKey = bundle.signingKey,
                    pendingInitiatorHeader = init.x3dh,
                )
            )
            true
        }
    }

    /**
     * Publish our prekey bundle, or top it up — but ONLY when V2 is enabled, and only
     * when the pool actually needs it.
     *
     * `POST /v2/keys/publish` APPENDS one-time prekeys to the surviving pool while
     * REPLACING the long-lived material, and `GET /v2/keys/:userKey` pops the OLDEST
     * first. This used to mint 20 fresh OPKs on EVERY launch, unconditionally: the
     * server pool grew without bound, the head of it was always a dead generation, and
     * the plaintext `opk_map` blob grew by 20 privates per launch and was re-serialised
     * in full on every write. iOS measured the damage on its own store — 5302 entries,
     * 40 distinct ids, current generation starting at index 4362 — and answered with
     * `refillOneTimePreKeysIfNeeded` (`OSHI/V2MessageRouter.swift:288-325`): ask the
     * SERVER for the authoritative count, return early if it is healthy, and publish
     * exactly the shortfall.
     */
    suspend fun publishBundleIfEnabled(): Boolean {
        val myUserKey = cryptoManager.getPublicKeyString()
        if (!config.isEnabled(myUserKey)) return false
        // __PER_DEVICE_MAILBOX_2026_09_23__ register + per-device bundle (no-op with the flag off),
        // then the ACCOUNT bundle only if this device is the one that holds it (§3.2): every other
        // registered device must stop republishing it, or it resets the holder's pool.
        try { deviceMailbox.maintain() } catch (e: Exception) { Log.w(TAG, "device mailbox maintain failed: ${e.message}") }
        lastDeviceMaintainAt = System.currentTimeMillis()
        if (deviceMailbox.deviceModeActive() && !deviceMailbox.holdsAccountBundle()) return true
        return try {
            // Never published from this install (or the prekey store was wiped): the
            // server has no long-lived material of ours, so publish the full set.
            if (!prekeyStore.hasPublished()) {
                val ok = keysClient.publish(OPK_TARGET) != null
                if (ok) {
                    notePublishedCaps()
                    prekeyStore.markPublished()
                    // __MAILBOX_BOOTSTRAP_2026_09_23__ On a brand-new identity the maintain() above
                    // was refused (401 unbound-grace): this first signed publish is what binds the
                    // signing key. Register now instead of a minute later.
                    if (deviceMailbox.flagOn() && !deviceMailbox.deviceModeActive()) {
                        try { deviceMailbox.maintain() } catch (e: Exception) { Log.w(TAG, "device mailbox maintain failed: ${e.message}") }
                        lastDeviceMaintainAt = System.currentTimeMillis()
                    }
                }
                return ok
            }
            val remaining = keysClient.remainingCount()
            when {
                // Count unavailable → do NOT mint. Guessing here is exactly what grew
                // the pool by 20 per launch; the bundle on the server is still valid.
                remaining == null -> true
                remaining >= OPK_LOW_WATER -> republishIfCapsDrifted()
                else -> (keysClient.publish(OPK_TARGET - remaining) != null).also { if (it) notePublishedCaps() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "publishBundle failed: ${e.message}")
            false
        }
    }

    /**
     * __V2_PADDING_2026_11_09__ Caps drift (iOS `republishIfCapabilitiesDrifted`). The account
     * bundle's `caps` used to change only when the one-time pool ran low, so a NEW capability
     * (here [V2Padding.CAP_PADDING]) stayed unannounced for as long as nobody new wrote to us —
     * and peers kept treating this build as an old one. Republish ONCE with zero new one-time
     * pre-keys: same generation (same SPK), so the server keeps the pool and only `caps`
     * changes. Never fails the caller: the bundle on the server is valid either way.
     */
    private suspend fun republishIfCapsDrifted(): Boolean {
        if (seqPrefs.getInt(PUBLISHED_CAPS_KEY, -1) == GroupLegacyCompat.MY_CAPS) return true
        if (keysClient.publish(0) != null) {
            notePublishedCaps()
            Log.i(TAG, "DIAG_V2_CAPS republished caps=${GroupLegacyCompat.MY_CAPS}")
        }
        return true
    }

    private fun notePublishedCaps() {
        seqPrefs.edit().putInt(PUBLISHED_CAPS_KEY, GroupLegacyCompat.MY_CAPS).apply()
    }

    /** Poll the relay once: pull → decrypt → DELIVER (awaited) → ack. Safe to call repeatedly. */
    suspend fun poll() = pollMutex.withLock {
        if (!config.isEnabledCached()) return            // hot path: no network on the gate
        val myUserKey = cryptoManager.getPublicKeyString()
        // __PER_DEVICE_MAILBOX_2026_09_23__ device view (own cursor, device headers) when registered
        // and the flag is on; the legacy view otherwise — byte-for-byte today's request.
        maybeMaintainDeviceMailbox()
        val deviceKeys = if (deviceMailbox.deviceModeActive()) deviceMailbox.keys() else null
        val myDeviceId = deviceKeys?.deviceId
        val holdsAccount = deviceMailbox.holdsAccountBundle()
        val pull = if (deviceKeys != null) {
            when (val r = deviceClient.pull(myUserKey, deviceMailbox.deviceCursor(), deviceKeys)) {
                is V2DeviceClient.PullResult.Ok -> r.pull
                is V2DeviceClient.PullResult.Refused -> {
                    // 410 → removed (alert raised), 409 → flag off: the NEXT tick polls legacy.
                    deviceMailbox.handleOutcome(r.outcome); return
                }
            }
        } else messagesClient.pull(myUserKey, lastSeq()) ?: return
        // __COVER_RETRY_KICK_2026_11_18__ A pull went through, i.e. the (disguised)
        // V2 path works again: parked 1:1 messages need not wait out their backoff.
        com.oshi.messenger.service.CoverOutbox.kick()
        // Hold the ack BEFORE the earliest failed seq so the relay keeps any envelope
        // we couldn't decrypt (iOS MessageManager+V2: ackSeq = lowestFailed-1 ?? maxSeq)
        // — but only while the retry budget lasts, or one poisoned envelope pins the
        // cursor forever and every message behind it is re-pulled and never delivered.
        var lowestFailedSeq = Long.MAX_VALUE
        fun hold(seq: Long) { if (seq in 0 until lowestFailedSeq) lowestFailedSeq = seq }
        // Peers whose envelope exhausted the retry budget in THIS batch, with the
        // exception that condemned it. Consumed by the heal pass after the loop.
        // Keyed by (peer, peerDeviceId?) — a broken per-device session heals that session only.
        val brokenPeers = LinkedHashMap<Pair<String, String?>, Exception>()

        for (env in pull.messages.sortedBy { it.seq }) {
            // __PER_DEVICE_MAILBOX_2026_09_23__ decrypt rules (CLIENT_SPEC §3.4).
            val route = com.oshi.messenger.network.v2.devicemailbox.DeviceRouting.classify(
                env.toDevice, myDeviceId, deviceKeys != null, holdsAccount)
            if (route == com.oshi.messenger.network.v2.devicemailbox.DeviceRouting.Action.DROP_FOREIGN) {
                Log.w(TAG, "DIAG_DEVICE_FOREIGN ${env.msgId.take(8)} addressed to another device — acked, dropped")
                continue
            }
            // Account envelope on a device that does not hold the account bundle: it was encrypted
            // for the holder's session. Try quietly; on failure ack and drop — NO retry hold, NO heal,
            // NO row. The holder has it and devsync brings it over.
            val quiet = route == com.oshi.messenger.network.v2.devicemailbox.DeviceRouting.Action.ACCOUNT_QUIET
            val sessionDevice = if (route == com.oshi.messenger.network.v2.devicemailbox.DeviceRouting.Action.DEVICE_COPY) env.fromDevice else null
            // (A) A file we already decrypted but whose blob download failed. Retry the
            //     DOWNLOAD ONLY — the ratchet advanced when we opened the envelope, so
            //     it can never be decrypted a second time.
            val pending = pendingFiles[env.msgId]
            if (pending != null) {
                if (!deliverFile(env, pending)) {
                    // Connectivity loss is not a failed cryptographic message.
                    // Keep the persisted file key and envelope until download succeeds.
                    hold(env.seq)
                }
                continue
            }
            if (!seenMsgIds.add(env.msgId)) continue  // already delivered this session

            // (B) DECRYPT under the ratchet lock (mutually exclusive with sendText).
            // __V2_PADDING_2026_11_09__ padding stripped right after the ratchet (never inside
            // decryptInbound: a throw there is taken for a broken session and re-runs X3DH).
            // [unpadInbound] never throws; a malformed tail comes back null = ack and drop.
            val pt: ByteArray? = try {
                ratchetMutex.withLock { decryptInbound(env, sessionDevice) }?.let { unpadInbound(env, sessionDevice, it) }
            } catch (e: Exception) {
                if (quiet) {
                    seenMsgIds.remove(env.msgId)
                    Log.i(TAG, "DIAG_DEVICE_QUIET_DROP ${env.msgId.take(8)} account envelope not for this device's sessions")
                    continue
                }
                // Could not decrypt (and no x3dh recovery) → the ratchet was NOT advanced;
                // hold the seq so the relay keeps the envelope for a later retry, BOUNDED
                // at 8 attempts exactly like iOS (MessageManager+V2.swift:747-755).
                seenMsgIds.remove(env.msgId)
                if (retryBudget.shouldRetry(env.msgId)) {
                    hold(env.seq)
                    Log.w(TAG, "inbound ${env.msgId.take(8)} from ${env.from.take(8)} decrypt failed, holding: ${e.message}")
                } else {
                    Log.w(TAG, "giving up on ${env.msgId.take(8)} after ${V2RetryBudget.MAX_RETRIES} attempts: ${e.message}")
                    // __RESEND_LIST_ANDROID_2026_10_12__ The ack is about to pass this seq,
                    // so the relay drops the envelope and the PEER becomes the only holder
                    // of that message. Name it, or it is gone for good — with its
                    // notification already rung.
                    unreadable.mark(env.from, env.msgId, env.ts)
                    // __RATCHET_HEAL_ANDROID__ Giving up used to be the END: the session
                    // stayed, the peer kept writing under it, and every later message
                    // was announced then destroyed. Remember WHO and WHY; the single
                    // heal pass after the loop decides what to do — never in here, or
                    // item N's repair would erase the fresh session item N-1 just
                    // adopted (iOS MessageManager+V2.swift, "L'effacement doit rester
                    // ICI, dans la passe unique, et JAMAIS dans la boucle").
                    brokenPeers.putIfAbsent(env.from to sessionDevice, e)
                }
                continue
            }
            if (pt == null) continue   // undecryptable-forever / no-op / malformed padding → let it be acked

            // __LEGACY_READER_2026_09_28__ Only an envelope that DECRYPTED counts (a forged `from`
            // cannot flip a peer's view), and only while we are in device mode: that is when a
            // device-mode peer always writes to us with `fromDevice`. See [PeerReadView].
            if (deviceKeys != null) notePeerWriteMode(env.from, env.toDevice, env.fromDevice, myUserKey)

            // __PER_DEVICE_MAILBOX_2026_09_23__ "sent from another of my devices" (§3.6): only ever on a
            // copy addressed to THIS device by another device of my own account.
            if (sessionDevice != null && env.from == myUserKey) {
                val copy = com.oshi.messenger.network.v2.devicemailbox.SentCopy.parse(pt)
                if (copy != null) {
                    retryBudget.clear(env.msgId)
                    try { onSentCopyDelivered?.invoke(copy, env.ts) }
                    catch (e: Exception) { Log.e(TAG, "sent-copy insert failed for ${copy.msgId.take(8)}: ${e.message}") }
                    continue
                }
            }
            // It decoded — drop any failure count it accumulated while the ratchet was
            // out of step (iOS `clearRetries`, MessageManager+V2.swift:682).
            retryBudget.clear(env.msgId)
            // …and never re-ask for a message that finally arrived.
            unreadable.clear(env.msgId)

            // (C) v2file? Fetch + open the blob, then deliver as media.
            val fileKey = V2FileKeyMessage.parse(pt)
            if (fileKey != null) {
                // __MEDIA_PADDING_2026_11_10__ a padded manifest proves this slot reads padded media.
                if (runCatching { OSHICryptoV2Streaming.openManifest(fileKey.fileKey, fileKey.fileNonce, fileKey.manifest).paddedSize != null }.getOrDefault(false)) {
                    V2PaddingPeers.noteInboundPaddedMedia(env.from, sessionDevice)
                }
                pendingFiles[env.msgId] = fileKey
                // Bounded: an entry only survives while its blob keeps failing, but a
                // long offline stretch must not let the map grow without limit.
                while (pendingFiles.size > PENDING_FILE_CAP) {
                    val it = pendingFiles.keys.iterator(); it.next(); it.remove()
                }
                if (!deliverFile(env, fileKey)) hold(env.seq)
                continue
            }

            // (D) Plain text (or a base64 GroupMessage when env.groupId is set).
            val text = String(pt, Charsets.UTF_8)

            // __RATCHET_HEAL_ANDROID__ The peer's acknowledgement. Its VALUE was the
            // X3DH header its envelope carried, which decryptInbound has already
            // adopted; the text itself must never reach a chat. Until this branch
            // existed it did: an iPhone's cure showed up as a `__RATCHET_HEAL__v1`
            // bubble with a notification. A peer whose header we just adopted is
            // proven healthy, so any repair planned for it this batch is void.
            if (env.groupId == null) {
                val heal = RatchetHealSignal.parse(text)
                if (heal != null) {
                    retryBudget.clear(env.msgId)
                    brokenPeers.remove(env.from to sessionDevice)
                    // __RESEND_LIST_ANDROID_2026_10_12__ An acknowledgement whose id carries
                    // the announcement PROVES its sender can read a list. That is the only
                    // thing that lets us attach one to ours later.
                    if (RatchetHealSignal.announcesListSupport(env.msgId)) notePeerResendCapable(env.from)
                    Log.i(TAG, "DIAG_V2_HEAL_RX from=${env.from.take(12)} resend=${heal.resend.size}")
                    // …and the ids it names are messages WE sent that it could not open.
                    // Until this call existed the list was logged and thrown away, so an
                    // iPhone asking Android for its lost messages was answered with silence.
                    if (heal.resend.isNotEmpty()) {
                        val ids = heal.resend
                        val from = env.from
                        try { onResendRequested?.invoke(from, ids) }
                        catch (e: Exception) { Log.w(TAG, "resend request failed for ${from.take(8)}: ${e.message}") }
                    }
                    continue
                }
                // A message the peer re-sent at an iOS-style request: deliver under its
                // ORIGINAL id so a twice-requested message cannot form two bubbles.
                val resent = RatchetHealSignal.Resent.parse(text)
                if (resent != null) {
                    val (originalId, body) = resent
                    if (!seenMsgIds.add(originalId)) continue
                    retryBudget.clear(originalId)
                    try {
                        onMessageDelivered?.invoke(V2Inbound.Text(env.from, originalId, env.ts, null, body))
                    } catch (e: Exception) {
                        Log.e(TAG, "deliver failed for resent ${originalId.take(8)}: ${e.message}")
                    }
                    continue
                }
            }
            try {
                onMessageDelivered?.invoke(
                    V2Inbound.Text(env.from, env.msgId, env.ts, env.groupId, text)
                )
            } catch (e: Exception) {
                // The ratchet is ALREADY advanced (crypto consumed) — this envelope can
                // never be re-decrypted, so holding the seq would poison the whole queue.
                Log.e(TAG, "deliver failed after decrypt; message ${env.msgId.take(8)} dropped locally: ${e.message}")
            }
        }
        // __RATCHET_HEAL_ANDROID__ One heal pass per batch, AFTER the loop.
        for ((key, cause) in brokenPeers) healBrokenSession(key.first, cause, myUserKey, key.second)

        // Bound the dedup set (insertion-ordered) so it can't grow without limit.
        if (seenMsgIds.size > SEEN_CAP) {
            val excess = seenMsgIds.size - SEEN_CAP
            val iter = seenMsgIds.iterator()
            repeat(excess) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        // Only the loop above can change either ledger, so an EMPTY pull — which is
        // what almost every tick of a quiet conversation returns — has nothing to
        // persist. Unguarded this ran two JSON serialisations and two
        // `SharedPreferences.apply()` per poll for the whole life of the process,
        // allocating a JSONArray of up to SEEN_PERSIST_CAP ids and the encoded
        // pending-file ledger each time and handing all of it to the GC. Handed over
        // by the battery-drain team.
        if (pull.messages.isNotEmpty()) {
            saveSeenMsgIds()
            savePendingFiles()
        }
        val ackSeq = if (lowestFailedSeq == Long.MAX_VALUE) pull.maxSeq else lowestFailedSeq - 1
        if (deviceKeys != null) {
            // Device ack: releases THIS device's copies and claims only (§3.3).
            if (ackSeq > deviceMailbox.deviceCursor()) {
                val o = deviceClient.ack(myUserKey, ackSeq, deviceKeys)
                if (o == com.oshi.messenger.network.v2.devicemailbox.DeviceHttpOutcome.Ok) {
                    deviceMailbox.setDeviceCursor(ackSeq)
                    // The cursor moved: the mailbox is draining, restart the stall clock.
                    retryBudget.noteCursorMoved()
                }
                else deviceMailbox.handleOutcome(o)
            }
        } else if (ackSeq > lastSeq()) {
            if (messagesClient.ack(myUserKey, ackSeq)) {
                setLastSeq(ackSeq)
                retryBudget.noteCursorMoved()
            }
        }
    }

    /**
     * __RATCHET_HEAL_ANDROID__ Repair a 1:1 session the retry budget has condemned.
     * Port of the single-pass half of iOS `MessageManager+V2.swift` (the
     * `__V2_RATCHET_DEAD_END_2026_08_16__` + `__RATCHET_HEAL_OVER_V2_2026_08_28__`
     * block): delete OUR session, then send the acknowledgement. Because the
     * session is gone, [sendText] re-runs X3DH and the envelope CARRIES the
     * header; the peer adopts it while decrypting. That is the repair.
     *
     * What does NOT heal, and why:
     *  • [SecurityException] — the envelope's identity did not match its sender.
     *    A forgery proves nothing about the session. Nothing is deleted.
     *  • [V2X3DHException] — the peer bootstrapped with a pre-key generation this
     *    device no longer holds. An acknowledgement would RESTART the problem; what
     *    is needed is our material back on the server, so we republish instead.
     *
     * Rate: one acknowledgement per peer per [RatchetHealSignal.Policy.COOLDOWN_MS],
     * and the non-initiator of a pair waits a full cooldown after first evidence,
     * so two devices reacting to the same broken batch do not loop symmetrically.
     */
    private suspend fun healBrokenSession(peer: String, cause: Exception, me: String, peerDeviceId: String? = null) {
        val now = System.currentTimeMillis()
        when (cause) {
            is SecurityException -> {
                Log.w(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} result=skip_kind kind=spoofedIdentity"); return
            }
            is V2X3DHException -> {
                Log.w(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} result=prekeys_republished")
                try { publishBundleIfEnabled() } catch (e: Exception) { Log.w(TAG, "republish failed: ${e.message}") }
                return
            }
            else -> Unit
        }
        val evidenceKey = HEAL_EVIDENCE_KEY + peer
        val since = seqPrefs.getLong(evidenceKey, 0L).takeIf { it > 0 } ?: now.also {
            seqPrefs.edit().putLong(evidenceKey, it).apply()
        }
        if (!RatchetHealSignal.Policy.isHealInitiator(me, peer) &&
            now - since < RatchetHealSignal.Policy.COOLDOWN_MS) {
            Log.i(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} result=yield_to_initiator"); return
        }
        val lastKey = HEAL_SENT_KEY + peer
        val last = seqPrefs.getLong(lastKey, 0L).takeIf { it > 0 }
        if (!RatchetHealSignal.Policy.maySend(last, now)) {
            Log.i(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} result=cooldown"); return
        }
        // The deletion lives HERE, in the single pass, never inside the envelope loop.
        ratchetMutex.withLock { sessionStore.delete(peer, peerDeviceId) }
        // __RESEND_LIST_ANDROID_2026_10_12__ Learn the peer's capabilities BEFORE deciding,
        // and do it HERE: the session has just been deleted, so `ensureCapable` can no longer
        // return early on `sessionStore.has` and actually reads the bundle — which is where
        // `caps` lives. It costs no extra round trip, because the bundle it fetches is the
        // one [sendText] consumes a few lines below.
        try { ensureCapable(peer) } catch (e: Exception) { Log.w(TAG, "caps probe failed: ${e.message}") }
        val capable = peerUnderstandsResendList(peer)
        val drained = unreadable.drain(peer, now)
        val resend = if (capable) drained else emptyList()
        if (!capable && drained.isNotEmpty()) {
            // Exactly what iOS logs in the mirror case (MessageManager+V2.swift:2366): the
            // bare form still heals the session, it just rescues nothing.
            Log.w(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} resend_withheld=${drained.size} raison=pair_v1")
        }
        val sent = try {
            sendText(peer, RatchetHealSignal.encode(resend).toByteArray(Charsets.UTF_8), RatchetHealSignal.makeMsgId())
        } catch (e: Exception) {
            Log.w(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} result=error err=${e.message}"); false
        }
        if (sent) {
            seqPrefs.edit().putLong(lastKey, now).remove(evidenceKey).apply()
            // An id is forgotten only AFTER the acknowledgement actually left — a failed send
            // would otherwise take the list with it and lose both the messages and the trigger.
            for (id in resend) unreadable.clear(id)
            Log.i(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} result=sent x3dh=1 resend=${resend.size}")
        } else {
            // No bundle for the peer (or relay down): the session is gone either way,
            // so the next outgoing message re-runs X3DH itself and carries the header.
            Log.w(TAG, "DIAG_V2_HEAL_TX to=${peer.take(12)} result=not_sent session_deleted=1")
        }
    }

    /**
     * Download + open a v2file blob and hand it to the delivery callback. Returns
     * false when the bytes could not be produced, so the caller holds the relay ack
     * and the envelope survives for another try (iOS MessageManager+V2.swift:694-740).
     * On success the blob is DELETED — delete-after-delivery, iOS :728-730.
     */
    private suspend fun deliverFile(env: V2Envelope, key: V2FileKeyMessage): Boolean {
        // STREAMING download (iOS MessageManager+V2.swift:715-722). The previous code
        // held every ciphertext chunk in a List and then allocated the whole plaintext
        // again — peak ≈ 2× the file size, which OOMs well below the declared 200 MB
        // ceiling on a heap with no `android:largeHeap`. Now the blob lands on disk one
        // 2 MiB chunk at a time and is HANDED OVER as a file, so nothing on this path
        // ever allocates the whole plaintext. The bytes are identical; only the peak
        // changed. The file is deleted in the `finally` below — the callback is awaited,
        // so a consumer that wants to keep the bytes must move the file before returning.
        val tmp = try {
            val dir = java.io.File(context.cacheDir, "v2blobs").apply { mkdirs() }
            java.io.File.createTempFile("blob_", ".bin", dir)
        } catch (e: Exception) {
            Log.w(TAG, "no scratch space for blob ${key.blobId.take(12)}: ${e.message}")
            return false
        }
        try {
            val size = blobClient.downloadAndDecryptToFile(
                blobId = key.blobId,
                chunkCount = key.chunkCount,
                fileKey = key.fileKey,
                fileNonce = key.fileNonce,
                manifest = key.manifest,
                out = tmp,
            ) ?: return false
            // GROUP media is the one consumer that still has to hold the whole file in
            // RAM: `dispatchV2GroupFile` base64s the bytes into a `GroupMessage` for
            // `GroupManager`, which has no file store of its own (audit M-3). Refuse
            // rather than crash when that single copy cannot fit — a visible drop beats
            // a permanent crash loop on the same conversation. 1:1 media has no such
            // ceiling any more: it is handed over as a FILE and only the inline-sized
            // tail of it is ever read into memory.
            val ceiling = Runtime.getRuntime().maxMemory() / 4
            if (env.groupId != null && size > ceiling) {
                Log.w(TAG, "group blob ${key.blobId.take(12)} is $size B, over the $ceiling B in-memory ceiling — dropped")
                pendingFiles.remove(env.msgId)
                retryBudget.clear(env.msgId)
                blobClient.delete(key.blobId)
                return true      // ack it: retrying would only crash again
            }
            try {
                onMessageDelivered?.invoke(
                    V2Inbound.File(
                        from = env.from,
                        msgId = env.msgId,
                        ts = env.ts,
                        groupId = env.groupId,
                        payload = tmp,
                        size = size,
                        filename = key.filename,
                        mime = key.mime,
                        mediaType = key.mediaType,
                        isViewOnce = key.isViewOnce == true,
                        // The four validated wire scalars (iOS `RevealWirePlan(key:)`,
                        // MessageManager+V2.swift:943-961). Parsing them and then not
                        // forwarding them is what left the receiver guessing the layer
                        // from the msgId and with no `layerCount`/`totalMs` for the
                        // countdown.
                        reveal = key.revealPlan,
                        // __VIDEO_NOTE_2026_09_24__ spec §1 rule 1: only a VIDEO can be a note.
                        videoNote = com.oshi.messenger.service.media.VideoNote
                            .isVideoNote(key.videoNote, key.mediaType, key.mime),
                        durationMs = key.durationMs,
                        groupMessageB64 = key.groupMessage,
                        caption = key.caption,
                    )
                )
            } catch (e: Exception) {
                // Persisted-or-not, the ratchet is spent; don't wedge the queue on it.
                Log.e(TAG, "file deliver failed for ${env.msgId.take(8)}: ${e.message}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "blob ${key.blobId.take(12)} failed to open: ${e.message}")
            return false
        } finally {
            // No-op when the consumer moved the file into its own store.
            tmp.delete()
        }
        pendingFiles.remove(env.msgId)
        retryBudget.clear(env.msgId)
        blobClient.delete(key.blobId)
        return true
    }

    /**
     * Pure crypto: decrypt one inbound envelope, advancing + persisting the ratchet.
     * Returns the plaintext, or null when the message is undecryptable-forever — which
     * is ONLY "first contact with no X3DH header", a no-op the caller simply acks.
     * Throws on a genuine decrypt failure AND on a prekey mismatch (see
     * [V2X3DHException]), so the caller holds the seq under the retry budget.
     * MUST be called under [ratchetMutex]; performs NO network or delivery I/O.
     */
    private fun decryptInbound(env: V2Envelope, peerDeviceId: String? = null): ByteArray? {
        val header = OSHICryptoV2.decodeHeader(env.header)
        val rec = sessionStore.load(env.from, peerDeviceId)

        if (rec == null) {
            // First contact — must carry an X3DH header to establish. This is the ONLY
            // genuinely unrecoverable case: there is nothing to establish from.
            val x = env.x3dh ?: return null
            val (state, opkId) = establishResponder(env.from, x)
            val pt = OSHIRatchetV2.decrypt(state, header, env.ciphertext, EMPTY) // throws if forged
            opkId?.let { prekeyStore.consumeOneTimePreKeyPriv(it) }              // burn only after verify
            sessionStore.save(record(env.from, state, x.identityKey, peerDeviceId))
            return pt
        }

        return try {
            val pt = OSHIRatchetV2.decrypt(rec.ratchet, header, env.ciphertext, EMPTY)
            rec.pendingInitiatorHeader = null // the peer answered → stop attaching x3dh
            sessionStore.save(rec)
            pt
        } catch (e: Exception) {
            // Ratchet recovery: a message carrying an X3DH header that the current
            // session can't open → re-establish as responder and retry (iOS behaviour).
            val x = env.x3dh ?: throw e
            val (state, opkId) = establishResponder(env.from, x)
            val pt = OSHIRatchetV2.decrypt(state, header, env.ciphertext, EMPTY)
            opkId?.let { prekeyStore.consumeOneTimePreKeyPriv(it) }
            sessionStore.save(record(env.from, state, x.identityKey, peerDeviceId))
            pt
        }
    }

    /**
     * __V2_PADDING_2026_11_09__ The plaintext without its padding, recording what this message
     * proves about the session's counterpart. Null = flag present but malformed tail: those are
     * authenticated bytes from the peer, nothing a session heal could fix — ack and drop, no
     * retry, no row. Never throws.
     */
    private fun unpadInbound(env: V2Envelope, sessionDevice: String?, raw: ByteArray): ByteArray? =
        when (val u = V2Padding.unpad(raw)) {
            is V2Padding.Unpadded.Plain -> {
                V2PaddingPeers.noteInbound(env.from, sessionDevice, padded = false, carriedX3DH = env.x3dh != null); u.bytes
            }
            is V2Padding.Unpadded.Padded -> {
                V2PaddingPeers.noteInbound(env.from, sessionDevice, padded = true, carriedX3DH = env.x3dh != null); u.bytes
            }
            V2Padding.Unpadded.Malformed -> {
                Log.w(TAG, "DIAG_V2_PAD_MALFORMED ${env.msgId.take(8)} from ${env.from.take(8)} — acked, dropped")
                null
            }
        }

    /**
     * Build a responder session from an X3DH header, returning (state, opkId-to-burn).
     * Throws [SecurityException] on a spoofed identity and [V2X3DHException] when the
     * named prekeys are not ours to use — never returns a "give up quietly" value.
     */
    private fun establishResponder(sender: String, x: V2Session.X3DHHeader): Pair<OSHIRatchetV2.State, String?> {
        // ── RESPONDER-SIDE TOFU (iOS V2MessageRouter.swift:421-427) ──────────────
        // The initiator's X3DH identity key MUST equal their routing address, because
        // the address IS the X25519 identity. Without this check anyone who can POST a
        // relay envelope bootstraps a session under a SPOOFED `from`: we derive the SK
        // from the attacker's IK/EK, the AEAD verifies (they own those keys), and the
        // message is stored as coming from the impersonated contact. iOS rejects it, so
        // we throw — the caller then holds the seq under the retry budget rather than
        // silently acking a forgery away.
        val claimed = Base64.encodeToString(x.identityKey, Base64.NO_WRAP)
        if (claimed != sender) {
            throw SecurityException("x3dh.identityKey != sender (${sender.take(12)}…) — possible impersonation")
        }
        val spk = prekeyStore.currentSignedPreKey()
        if (spk.keyId != x.signedPreKeyId) {
            // Our SPK rotated — this SK cannot be derived with the key we hold now.
            Log.w(TAG, "DIAG_V2_SPK_MISMATCH from ${sender.take(12)}… wants ${x.signedPreKeyId.take(8)}…, we hold ${spk.keyId.take(8)}…")
            throw V2X3DHException("signed prekey mismatch")
        }
        var opkPair: X25519Pair? = null
        val opkId: String? = x.oneTimePreKeyId
        if (opkId != null) {
            val priv = prekeyStore.oneTimePreKeyPrivPeek(opkId)
                ?: run {
                    // iOS's note on the silent version of this path: "the ONLY symptom
                    // was that the first message failed to authenticate… which is why
                    // this looked like a broken ratchet for days".
                    Log.w(TAG, "DIAG_V2_OPK_UNKNOWN from ${sender.take(12)}… names opk ${opkId.take(8)}… we do not hold")
                    throw V2X3DHException("unknown one-time prekey")
                }
            opkPair = X25519Pair(priv, OSHICryptoV2.x25519PubFromPriv(priv))
        }
        return V2Session.responder(myIdentity(), spk.pair, opkPair, x) to opkId
    }

    private fun record(peer: String, state: OSHIRatchetV2.State, peerIdentity: ByteArray, peerDeviceId: String? = null) =
        V2SessionStore.Record(peer, V2SessionStore.Role.RESPONDER, state, peerIdentity, null, null, peerDeviceId)

    private fun loadSeenMsgIds(): LinkedHashSet<String> {
        val raw = seqPrefs.getString(SEEN_KEY, null) ?: return LinkedHashSet()
        return try {
            val arr = org.json.JSONArray(raw)
            val out = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
            out
        } catch (_: Exception) { LinkedHashSet() }
    }

    /** Persist the newest [SEEN_PERSIST_CAP] ids — iOS persists 500 (MessageManager+V2.swift:209-218). */
    private fun saveSeenMsgIds() {
        val arr = org.json.JSONArray()
        seenMsgIds.asSequence().drop((seenMsgIds.size - SEEN_PERSIST_CAP).coerceAtLeast(0)).forEach { arr.put(it) }
        seqPrefs.edit().putString(SEEN_KEY, arr.toString()).apply()
    }

    /**
     * Reload the un-downloaded blobs from disk. A record that no longer parses is
     * dropped rather than throwing — its envelope is then treated as a fresh arrival
     * and the [seenMsgIds] skip acks it, which is exactly the old behaviour.
     */
    private fun loadPendingFiles(): LinkedHashMap<String, V2FileKeyMessage> =
        decodePendingFiles(seqPrefs.getString(PENDING_KEY, null))

    /**
     * Persist the un-downloaded blobs, bounded by [PENDING_FILE_CAP]. This holds file
     * KEY MATERIAL — the same trade `service/media/V2SendResumeStore.kt` makes on the
     * send side, and iOS makes in its own resume ledger: the alternative is losing the
     * media outright. Connectivity failures retain the key for a later download;
     * they do not consume the cryptographic retry budget.
     */
    private fun savePendingFiles() {
        seqPrefs.edit().putString(PENDING_KEY, encodePendingFiles(pendingFiles)).apply()
    }

    private fun lastSeq(): Long = seqPrefs.getLong("last_seq", 0L)
    private fun setLastSeq(v: Long) = seqPrefs.edit().putLong("last_seq", v).apply()

    internal companion object {
        /**
         * Codec for the un-downloaded-blob ledger: a JSON object keyed by msgId whose
         * values are the `v2file` payloads themselves, so the format is the wire format
         * and cannot drift from it.
         *
         * An entry that no longer parses is DROPPED, not thrown on: a corrupt ledger
         * degrades to the previous behaviour (the envelope is acked away) instead of
         * wedging every poll after it.
         */
        internal fun encodePendingFiles(pending: Map<String, V2FileKeyMessage>): String {
            val o = org.json.JSONObject()
            for ((k, v) in pending) o.put(k, v.toJson())
            return o.toString()
        }

        internal fun decodePendingFiles(raw: String?): LinkedHashMap<String, V2FileKeyMessage> {
            if (raw.isNullOrEmpty()) return LinkedHashMap()
            return try {
                val o = org.json.JSONObject(raw)
                val out = LinkedHashMap<String, V2FileKeyMessage>(o.length())
                for (k in o.keys()) V2FileKeyMessage.parse(o.optString(k))?.let { out[k] = it }
                out
            } catch (_: Exception) { LinkedHashMap() }
        }

        const val TAG = "V2MessageRouter"
        /** Envelope `type` of a group content envelope (relay_v2 `validateEnvelope`). */
        const val TYPE_GROUP = "group"
        const val SEEN_CAP = 4000
        const val PENDING_FILE_CAP = 64
        const val RETRY_KEY = "undecryptable_retries"
        /** __RESEND_LIST_ANDROID_2026_10_12__ peer → (msgId → envelope ts) of what we gave up on. */
        const val UNREADABLE_KEY = "unreadable_by_peer"
        /** Attempts per msgId + the rolling hourly window per peer, for SERVING a request. */
        const val RESEND_BUDGET_KEY = "resend_budget"
        /** __V2_PADDING_2026_11_09__ [V2PaddingPeers] persistence. */
        const val PADDING_SLOTS_KEY = "padding_slots"
        /** __V2_PADDING_2026_11_09__ The `caps` value last published in the ACCOUNT bundle. */
        const val PUBLISHED_CAPS_KEY = "published_caps"
        /** Peers that announced, in an acknowledgement id, that they can read a resend list. */
        const val RESEND_CAPABLE_KEY = "peer_resend_capable"
        const val HEAL_SENT_KEY = "heal_sent_"          // + peer → epoch ms of the last acknowledgement
        const val HEAL_EVIDENCE_KEY = "heal_evidence_"  // + peer → epoch ms of the first give-up
        const val SEEN_KEY = "seen_msg_ids"
        /** __LEGACY_READER_2026_09_28__ + normalized peer → true while it writes on the account path. */
        const val LEGACY_READER_KEY = "peer_legacy_view_"
        const val PENDING_KEY = "pending_file_keys"
        const val SEEN_PERSIST_CAP = 500
        /** iOS `refillOneTimePreKeysIfNeeded(lowWater:target:)` — V2MessageRouter.swift:288-325. */
        const val OPK_TARGET = 20
        const val OPK_LOW_WATER = 5
    }
}
