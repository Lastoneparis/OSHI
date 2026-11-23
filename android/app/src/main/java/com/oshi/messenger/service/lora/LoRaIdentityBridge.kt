package com.oshi.messenger.service.lora

/**
 * The ONLY place the radio driver touches `LoRaRegion` and `LoRaNodeIdentityMap`.
 *
 * Those two files belong to the LoRa settings/UI work stream. Funnelling every
 * call through one small file means a signature change there is a one-file fix
 * instead of a rewrite of `MeshtasticManager` — and it is where the two type
 * vocabularies are reconciled: the driver carries node numbers as `UInt` (they
 * are Meshtastic `uint32`s and are compared against `0xFFFFFFFF`), while the
 * identity map carries them as `Long`.
 *
 * The identity rules themselves come from OSHI/LoRaNodeIdentityMap.swift:
 *  • an OSHI envelope (portnum 256) carries `senderPublicKey`, so it lands in the
 *    right conversation by itself;
 *  • a RAW Meshtastic text packet (portnum 1) carries NOTHING but a node number,
 *    so the only way it reaches the right conversation is a LEARNED mapping;
 *  • the mapping is only ever created by an envelope THAT WE DECRYPTED — a
 *    claimed `senderPublicKey` proves nothing, and anyone with a radio can put a
 *    contact's (openly shared) public key into an envelope.
 *    LoRaNodeIdentityMap.swift:23-41.
 */
internal object LoRaIdentityBridge {

    /** Meshtastic's `Config.LoRaConfig.RegionCode` 0 = UNSET. */
    const val REGION_UNSET_RAW = 0L

    /** Zero-extends: a node number is unsigned, and 0xFFFFFFFF must not read as -1. */
    private fun nodeLong(node: UInt): Long = node.toLong()

    /**
     * Null for a region THIS BUILD DOES NOT KNOW as well as for one it has not
     * read — see [MeshtasticManager.regionRaw] for why the driver keeps the raw
     * value alongside this.
     */
    fun regionFromRaw(raw: Long): LoRaRegion? = LoRaRegion.fromRaw(raw.toInt())

    fun regionRaw(region: LoRaRegion): Long = region.rawValue.toLong()

    /** The OSHI public key this node has PROVED, or null. */
    fun boundKey(node: UInt): String? =
        LoRaNodeIdentityMap.shared.publicKey(forNode = nodeLong(node))

    /**
     * __LORA_ACK_2026_10_10__ The reverse of [boundKey]: the node number that
     * has PROVED it speaks for `publicKey`, or null when we have never heard
     * from them over the radio.
     *
     * Null is the normal, safe answer and the caller must treat it as "keep
     * broadcasting": a binding only exists for a peer whose envelope actually
     * decrypted (see [bind]), which is exactly the condition under which a
     * unicast can be expected to reach them. Guessing a node number here would
     * send a message into a void that broadcasting would have reached.
     *
     * Why the driver needs it: Meshtastic does not acknowledge broadcasts, so
     * `wantAck` is meaningless on one. Without a node number every OSHI LoRa
     * send went out as a broadcast and delivery could never be confirmed.
     */
    fun nodeFor(publicKey: String): UInt? =
        LoRaNodeIdentityMap.shared.node(forPublicKey = publicKey)?.toUInt()

    /** The conversation a raw text from this node belongs in. */
    fun conversationKey(node: UInt): String =
        LoRaNodeIdentityMap.shared.conversationKey(forNode = nodeLong(node))

    /** "lora!<nodehex>" — the conversation used when nothing has been proved. */
    fun fallbackConversationKey(node: UInt): String =
        LoRaNodeIdentityMap.fallbackConversationKey(nodeLong(node))

    /**
     * Record that `node` speaks for `publicKey`. Call ONLY after the envelope
     * decrypted — never on the strength of the claimed field. The driver does not
     * call this: it cannot decrypt, so it has no proof. The message pipeline does,
     * once the ratchet has succeeded.
     */
    fun bind(node: UInt, publicKey: String) =
        LoRaNodeIdentityMap.shared.bind(nodeLong(node), publicKey)
}
