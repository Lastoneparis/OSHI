package com.oshi.messenger.service

import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.OSHIControlPayload

/**
 * The two pure decisions the LoRa lane needs, kept away from the send pipeline
 * so they can be tested without a radio, a database or a network.
 *
 * Android port of `OSHI/MessageSendPolicy.swift` (the byte budget) and of the
 * two LoRa trigger conditions in `OSHI/MessageManager.swift:4140-4180`.
 *
 * Nothing here does I/O, crypto or UI. That is what makes it testable, and a
 * budget that is not tested is a budget that drifts away from the radio.
 */
/**
 * __CASHU_SEND_READY_2026_11_09__ The byte-budget refusal, as its own type so a
 * caller can tell "too long for this route" from every other failure. Still an
 * [IllegalArgumentException], so every existing catch is unaffected. The Cashu
 * send path needs the distinction: "shorten it" means nothing for a string that
 * IS the money and cannot be edited.
 */
class MessageTooLongException(message: String) : IllegalArgumentException(message)

object MessageSendPolicy {

    // ─────────────────────────── The byte budget ───────────────────────────

    /**
     * Largest text, in UTF-8 bytes, that survives the 12-frame LoRa cap.
     *
     * READ FROM iOS, NOT GUESSED — `MessageSendPolicy.swift:56-60`,
     * `loRaSafeUTF8Bytes = 500`. The derivation, quoted from that file's header
     * so it cannot rot silently here:
     *
     *   `MeshtasticManager` chunks the JSON-encoded wire-safe envelope at 180
     *   bytes/frame and refuses anything over 12 frames — 12 × 180 = 2160 bytes
     *   on the wire (Android: [com.oshi.messenger.service.lora.LoRaEnvelope]
     *   `MAX_CHUNKS`). That budget is spent on the ENVELOPE, not on the text: a
     *   stripped envelope costs ~1114 bytes empty, and every plaintext byte
     *   costs ~1.83 wire bytes once it has been through AES-GCM →
     *   DoubleRatchetMessage JSON → AES-GCM → base64 → JSON. Measured, not
     *   estimated: 572 UTF-8 bytes of text is the exact ceiling that still fits
     *   in 2160. 500 leaves ~130 bytes of headroom for the fields that vary in
     *   length (message id, senderAddress, replyToId).
     */
    const val LORA_SAFE_UTF8_BYTES = 500

    /**
     * __LORA_LONG_TEXT_OMP_2026_11_18__ Largest text, in UTF-8 bytes, when the paired radio runs
     * OSHI Mesh firmware (it answered the OMP beacon probe). That radio takes the WHOLE envelope
     * ([com.oshi.messenger.service.lora.MeshtasticManager] `sendViaOmp`) and fragments it itself:
     * 64 x 182 = 11,648 B of OMP body ([com.oshi.messenger.service.lora.OshiMeshProtocol.MAX_MESSAGE]),
     * not the 12 x 180 the phone-side stock path allows.
     *
     * MEASURED, not the 1.83x quoted above (that figure under-counts one base64 layer: ratchet
     * ciphertext b64 -> ratchet JSON b64 -> AES-GCM -> b64 = ~2.38x): `LoRaLongTextTest` builds the
     * envelope layer for layer and finds wire ~= 1,350 + 2.38 x bytes — 10,861 B for 4,000 B of text,
     * still under 11,648 B with NO compression; deflate of the outer base64 then takes the OMP body
     * to ~8 KB (~44 of 64 frames). 3,080 B measured: wire 8,673 B, body 6,341 B, 35 frames. Bench-proven on two T1000-E
     * (2,000 B and 3,800 B both ways, first contact included) — Seeed's failure was the app
     * refusing anything over 500 B, never the radio.
     */
    const val LORA_OMP_UTF8_BYTES = 4_000

    /**
     * Limit whenever LoRa is not the route. INVENTED on iOS and kept identical
     * there (`MessageSendPolicy.swift`, `standardUTF8Bytes`). No OSHI transport
     * requires it; the V2 relay's own ceiling (256 KB of base64 ciphertext,
     * `relay_v2.js` MAX_CIPHERTEXT_BYTES) is ~140 KB of plaintext.
     *
     * __LONG_TEXT_ONE_MESSAGE_2026_09_28__ 4000 → 16000 (≈ 8,000 accented
     * Latin characters, 16,000 ASCII, 5,300 CJK) so a pasted text of a few
     * thousand characters goes as ONE message. ~30 KB on the wire at the
     * measured 1.83× inflation. No shipped client (iOS, Android, Desktop) caps
     * INBOUND text length — only the sender ever limited it — so older
     * receivers display it unchanged.
     */
    const val STANDARD_UTF8_BYTES = 16_000

    /**
     * Byte budget for the transport that is actually reachable.
     * `MessageSendPolicy.swift:78-80`.
     *
     * `loRaPaired` is injected rather than read from `MeshtasticManager` so the
     * decision is testable without a radio. The call site passes
     * `MeshtasticManager.isRadioPaired(context)`.
     */
    fun maxUTF8Bytes(loRaPaired: Boolean, oshiFirmware: Boolean = false): Int = when {
        !loRaPaired -> STANDARD_UTF8_BYTES
        // __LORA_LONG_TEXT_OMP_2026_11_18__ `oshiFirmware` = the paired radio answered the OSHI Mesh
        // probe (remembered across connections: `MeshtasticManager.lastKnownOshiFirmware`). Unknown or
        // stock keeps the 500 B the phone-side 12-frame path can carry.
        oshiFirmware -> LORA_OMP_UTF8_BYTES
        else -> LORA_SAFE_UTF8_BYTES
    }

    /**
     * True only when LoRa is the route: a radio is paired AND this phone has no
     * physical network path — the exact condition of the off-grid shortcut in
     * `MessageRepository.sendTextMessage` (`loRaPaired && !hasPhysicalNetworkPath()`).
     *
     * __LONG_TEXT_ONE_MESSAGE_2026_09_28__ The gate used to pass `isRadioPaired`
     * alone, so anyone who had ever paired a radio was held to 500 bytes on
     * every message, online or not. Online, an oversize envelope that reaches
     * the peer-unreachable LoRa branch is refused by the radio (MAX_CHUNKS) and
     * the relay's store-and-forward carries it instead.
     */
    fun loRaBudgetApplies(radioPaired: Boolean, hasPhysicalPath: Boolean): Boolean =
        radioPaired && !hasPhysicalPath

    // ─────────────────────────── Line breaks ───────────────────────────

    /**
     * Line/paragraph separators other than LF that pasted text can carry: CR
     * (Word/classic Mac paragraphs), VT (Word manual break), NEL, U+2028 LINE
     * SEPARATOR, U+2029 PARAGRAPH SEPARATOR. Android's text layout breaks
     * paragraphs on LF only, so these rendered as one run-on block here while
     * the iPhone that sent them showed the paragraphs.
     */
    private fun isForeignLineBreak(c: Char): Boolean =
        c == '\r' || c == '\u000B' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    /**
     * Every line break becomes one LF (CRLF → one LF). No trimming, blank lines
     * kept. Parity with iOS `MessageTextSplitter.normalizeLineBreaks`.
     */
    fun normalizeLineBreaks(text: String): String {
        if (text.none(::isForeignLineBreak)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                out.append('\n'); i += 2; continue
            }
            out.append(if (isForeignLineBreak(c)) '\n' else c)
            i++
        }
        return out.toString()
    }

    /**
     * [content] normalised for sending — plain user prose only. Control payloads
     * and reply/forward envelopes (JSON) pass through untouched: rewriting raw
     * characters inside a JSON string literal could make it undecodable. The
     * composer normalises a reply's body BEFORE wrapping it
     * (`ChatViewModel.sendMessage`).
     */
    fun normalizedForSend(content: String): String =
        if (isUserProse(content) && !OSHIControlPayload.isEnvelope(content)) {
            normalizeLineBreaks(content)
        } else {
            content
        }

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * True when `content` is ordinary user prose, i.e. the only thing the budget
     * may be applied to. `MessageSendPolicy.swift:110-118`.
     *
     * Control payloads (receipts, typing, reactions, edits, live location, group
     * envelopes …) ride the same `sendTextMessage` as user text. Refusing one of
     * these for length does not protect anybody — it breaks read receipts and
     * live location. The prefix list is DERIVED from [OSHIControlPayload], the
     * single place a prefix may be declared, rather than written from memory: a
     * marker missing from a hand-written copy is a control message that gets
     * refused or chopped.
     *
     * Reply and forward wrappers are envelopes around prose; iOS splits INSIDE
     * them. Android does not split (see the class note on [Outcome]), so they
     * are treated as prose for the purposes of the budget check — a 4 KB reply
     * is still a 4 KB message on the wire.
     */
    fun isUserProse(content: String): Boolean {
        if (content.isEmpty()) return false
        if (OSHIControlPayload.isControl(content) && !OSHIControlPayload.isEnvelope(content)) {
            return false
        }
        // Group media rides as a raw JSON object in `content`.
        val head = content.trimStart().firstOrNull()
        if (head == '{' || head == '[') return false
        return true
    }

    /** What the budget check decided. */
    enum class Outcome {
        /** Send it unchanged — no behaviour change at all. */
        WITHIN_BUDGET,

        /**
         * Over the budget for the transports that are reachable. The caller MUST
         * tell the user: iOS returns `.tooLong` from `sendMessage`
         * (`MessageManager.swift:4866-4870`) and the composer puts the text back
         * in the box.
         *
         * Android REFUSES where iOS SPLITS (`MessageTextSplitter.splitForSend`).
         * Splitting one composed message into N rows changes what
         * `sendTextMessage` returns and what every one of its ~8 callers stores,
         * so it is deliberately left out of this change; the refusal is the half
         * that stops a long message being encrypted and chunked only to die at
         * chunk 13.
         */
        TOO_LONG,
    }

    /**
     * The gate that runs BEFORE the message row is inserted and before the
     * ratchet is touched. iOS decides the same thing first, at
     * `MessageManager.swift:4861-4871`, and deliberately before charging the
     * rate limiter.
     */
    fun check(content: String, loRaPaired: Boolean, oshiFirmware: Boolean = false): Outcome {
        if (!isUserProse(content)) return Outcome.WITHIN_BUDGET
        return if (utf8Size(content) > maxUTF8Bytes(loRaPaired, oshiFirmware)) {
            Outcome.TOO_LONG
        } else {
            Outcome.WITHIN_BUDGET
        }
    }

    // ─────────────────────── The last-resort LoRa lane ───────────────────────

    /**
     * How stale a contact's last-seen may be before the peer is treated as
     * unreachable by network. `MessageManager.swift:4104` — five minutes rather
     * than seconds, because presence updates arrive on receipts and pings, which
     * are themselves sparse; a short window would call an actively-chatting peer
     * offline between messages.
     *
     * The same 5 minutes the conversation list already uses for its online dot
     * (`MessageRepository.kt`, `getConversations`).
     */
    const val PEER_ONLINE_WINDOW_MS = 5 * 60 * 1000L

    /**
     * `MessageManager.swift:4085-4095`. **Unknown is not evidence of absence**:
     * a contact we have never exchanged with has no last-seen and counts as
     * online, so a first message never gets pushed onto a 2 s/frame radio.
     */
    fun isPeerLikelyOnline(lastSeenMs: Long?, nowMs: Long): Boolean {
        val seen = lastSeenMs ?: return true
        if (seen <= 0L) return true
        return (nowMs - seen) < PEER_ONLINE_WINDOW_MS
    }

    /** Which of iOS's two LoRa situations applies, if either. */
    enum class Lane {
        /**
         * (a) THIS PHONE is off-grid. `MessageManager.swift:4140-4157`. Skip the
         * internet timeouts entirely — tens of seconds spent on transports that
         * cannot possibly succeed, in exactly the situation someone bought a LoRa
         * node for. Not gated on the radio being ready: a queued message is a
         * free second chance when the node reconnects.
         */
        OFF_GRID,

        /**
         * (b) The PEER looks unreachable by network even though we are online.
         * `MessageManager.swift:4159-4180`. Gated on a radio actually being
         * ready — with no radio this branch does not exist and the chain is
         * exactly what it was.
         */
        PEER_UNREACHABLE,

        /** Neither. The normal transport chain runs untouched. */
        NONE,
    }

    /**
     * `hasPhysicalPath`, not "is connected": iOS gates the off-grid shortcut on a
     * PHYSICAL path because a VPN keeps the path satisfied from a tunnel route
     * alone once the link under it is gone, so "connected" stays true off-grid
     * and the only route to the radio could never fire for a VPN user
     * (`MessageManager.swift:4132-4139`).
     */
    fun lane(
        hasPhysicalPath: Boolean,
        radioReady: Boolean,
        peerLastSeenMs: Long?,
        nowMs: Long,
    ): Lane = when {
        !hasPhysicalPath -> Lane.OFF_GRID
        radioReady && !isPeerLikelyOnline(peerLastSeenMs, nowMs) -> Lane.PEER_UNREACHABLE
        else -> Lane.NONE
    }

    // ─────────────── The order, and the one promise about it ───────────────

    /** Parity: iOS `TransportKind` in `LoRaTransport.swift`. */
    enum class Transport { BLUETOOTH_MESH, WIFI_MESH, LORA, INTERNET_RELAY }

    /**
     * Everything [order] is allowed to look at.
     *
     * DELIBERATE DIVERGENCE from iOS, written down so it does not read as a
     * porting mistake: iOS's `TransportPolicy.Conditions` does not carry the two
     * preferences, because `order(_:)` reads them straight out of
     * `UserDefaults`. Here they are parameters, so this object stays pure and
     * testable without a `Context` — which is the entire reason the LoRa
     * decisions were moved into it. The DECISION is identical on both
     * platforms; only where the flag is read differs.
     */
    data class RoutingConditions(
        val hasPhysicalPath: Boolean,
        val peerOnMesh: Boolean,
        val radioReady: Boolean,
        val peerLikelyOnline: Boolean,
        val carriesMedia: Boolean,
        val internetAllowedForSending: Boolean = true,
        val preferRadioOverRelay: Boolean = false,
        /**
         * __ANTI_JAMMING_PHASE1_2026_11_13__ The radio reports PROBABLE_JAMMING
         * (`MeshtasticManager.isJammed`, from `LoRaJammingDetector`). LoRa is then left out
         * entirely: whatever else is reachable carries the message, and with nothing else
         * the list is empty and the message waits (the radio keeps it for when the channel
         * clears). Not "last resort": frames into a jammed channel are lost airtime that
         * also reads as "sent" on screen.
         */
        val radioJammed: Boolean = false,
    )

    /**
     * The transports to try, in order, for one envelope.
     *
     * An empty list is a legitimate answer: a radio-only install with no radio
     * in range has nowhere to put the message right now, and saying so is
     * better than quietly using the relay the user forbade.
     */
    fun order(c: RoutingConditions): List<Transport> {
        val out = mutableListOf<Transport>()

        // Mesh first when the peer is actually there — free, local, instant.
        if (c.peerOnMesh) out += Transport.BLUETOOTH_MESH

        // Media stays off the radio while a network path exists: LoRa media is
        // minutes on the air. Off-grid it is the only option, so it is allowed.
        val radioUsable = c.radioReady && !c.radioJammed && (!c.carriesMedia || !c.hasPhysicalPath)
        val internetUsable = c.hasPhysicalPath && c.internetAllowedForSending

        // The radio goes ahead of the relay when the relay cannot work, when the
        // peer is unreachable by network anyway, or when the user asked for it.
        // The first two are the behaviour that already existed — [Lane.OFF_GRID]
        // and [Lane.PEER_UNREACHABLE]; only the third is new.
        val radioFirst = radioUsable &&
            (!internetUsable || !c.peerLikelyOnline || c.preferRadioOverRelay)

        if (radioFirst) out += Transport.LORA
        if (internetUsable) out += Transport.INTERNET_RELAY
        if (radioUsable && !radioFirst) out += Transport.LORA
        return out
    }

    /**
     * Whether the send path should offer the radio before the relay.
     *
     * DERIVED from [order] rather than re-deriving the condition, so the list
     * the diagnostics show and the branch the router takes cannot drift apart —
     * which is the failure this whole type exists to end.
     */
    fun radioComesBeforeRelay(c: RoutingConditions): Boolean {
        val o = order(c)
        val radio = o.indexOf(Transport.LORA)
        if (radio < 0) return false
        val relay = o.indexOf(Transport.INTERNET_RELAY)
        if (relay < 0) return true
        return radio < relay
    }
}

/**
 * Where the two routing preferences are stored.
 *
 * __NEVER_OVER_THE_INTERNET_2026_10_20__ SharedPreferences, not DataStore,
 * although the rest of this app's settings use DataStore. The reason is the
 * shape of the guarantee: the gate has to be callable from anywhere that can
 * reach the network, including code that is not `suspend`, and a promise you
 * have to `await` before you can keep is a promise somebody will skip. A
 * synchronous read makes every gate a single line — the same property iOS gets
 * from `UserDefaults`.
 *
 * Keys are byte-identical to the iOS ones so that a support answer ("check this
 * setting") is the same sentence on both platforms.
 */
object TransportPreferences {
    const val PREFS = "oshi_settings"
    const val KEY_INTERNET_SENDING_DISABLED = "oshi_transport_internet_sending_disabled"
    const val KEY_PREFER_RADIO_OVER_RELAY = "oshi_transport_prefer_radio_over_relay"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    /**
     * Stops OUTGOING traffic from using the internet. The app still PULLS from
     * the relay, so the server keeps seeing this device's address and its poll
     * timing; stopping that too would stop incoming messages, which is a
     * different switch and a different promise. Voice and video calls are NOT
     * covered either: a call is an explicit, synchronous action, and failing it
     * silently would be worse than honest.
     */
    fun internetAllowedForSending(context: android.content.Context): Boolean =
        !prefs(context).getBoolean(KEY_INTERNET_SENDING_DISABLED, false)

    /**
     * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ May the relay carry something for
     * THIS conversation?
     *
     * The app-wide switch alone was never enough: it is all-or-nothing, and
     * someone who wants one conversation kept off the internet does not want the
     * other forty to stop working. Every gate on the send path that knows who it
     * is sending to asks this instead.
     *
     * [conversationId] is the contact's public key for a 1:1 and the group's id
     * for a group. Mirrors `TransportPolicy.internetAllowedForSending(inConversation:)`
     * in Swift, and composes through the same shared rule so the two platforms
     * cannot drift on what "allowed" means.
     */
    fun internetAllowedForSending(
        context: android.content.Context,
        conversationId: String,
    ): Boolean {
        OfflineOnlyStore.attach(context)
        return com.oshi.messenger.data.model.OfflineOnlyPolicy.internetAllowed(
            globalInternetAllowed = internetAllowedForSending(context),
            conversationIsOfflineOnly = OfflineOnlyStore.isOfflineOnly(conversationId),
        )
    }

    fun setInternetAllowedForSending(context: android.content.Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_INTERNET_SENDING_DISABLED, !allowed).apply()
    }

    /** Offer the paired radio before the relay, for text. Off by default. */
    fun preferRadioOverRelay(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_PREFER_RADIO_OVER_RELAY, false)

    fun setPreferRadioOverRelay(context: android.content.Context, prefer: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFER_RADIO_OVER_RELAY, prefer).apply()
    }
}

/**
 * What a LoRa send or a mesh ACK is allowed to write onto a message row.
 *
 * Pure, and the only copy: `MessageRepository.deliverViaLoRa` and
 * `MessageRepository.promoteLoRaAcked` call these rather than building the
 * `copy(...)` themselves, so the rule below is the rule the app runs — not a
 * paraphrase of it that a test could pass while the app does something else.
 */
object LoRaDeliveryLedger {

    /**
     * What to write after the radio answered.
     *
     * `framesWritten` is `queueOrSendSecureMessage`'s return value, which is true
     * ONLY when frames actually reached a CONNECTED radio
     * (`MeshtasticManager.kt:755-782`). A merely-QUEUED message returns false and
     * this returns **null — write nothing**: the message keeps its normal
     * failed + retry path, the store-and-forward flush becomes a free second
     * chance, and receivers dedup by message id across transports.
     *
     * Reporting a queued message as sent is the failure this function exists to
     * make impossible: it turns a message that may never leave the device into a
     * ✓ on screen.
     */
    fun afterSendAttempt(row: Message, framesWritten: Boolean): Message? {
        if (!framesWritten) return null
        return row.copy(
            deliveryMethod = DeliveryMethod.LORA,
            isDelivered = true,
            isFailed = false,
        )
    }

    /**
     * What to write when the mesh confirms this row's id
     * (`MeshtasticManager.loraAcked`). Null means the row already says this.
     *
     * The transition that matters is out of FAILED: a message the radio could
     * only queue was marked failed, and the store-and-forward flush then
     * delivered it. Note the ack only ever exists for a UNICAST send, and only
     * for a message's LAST chunk — Meshtastic never acks a broadcast, so a
     * missing ack is not evidence of anything and never marks a row failed here.
     */
    fun afterMeshAck(row: Message): Message? {
        if (row.isDelivered && !row.isFailed && row.deliveryMethod == DeliveryMethod.LORA) {
            return null
        }
        return row.copy(
            isDelivered = true,
            isFailed = false,
            deliveryMethod = DeliveryMethod.LORA,
        )
    }
}
