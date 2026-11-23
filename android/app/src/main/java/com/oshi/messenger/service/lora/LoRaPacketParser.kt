package com.oshi.messenger.service.lora

/**
 * Every Meshtastic protobuf OSHI encodes or decodes, in one pure file.
 *
 * Kept free of Android and of BLE so the whole wire surface is testable on the
 * JVM. `MeshtasticManager` is then only transport + state: it moves bytes
 * between a GATT characteristic and these functions.
 *
 * Every field number below cites the Swift line it was read from. Meshtastic's
 * mesh.proto is the ultimate authority, but iOS is what is SHIPPING, and parity
 * means matching what ships.
 */
object LoRaPacketParser {

    // ─────────────────────────── Port numbers ───────────────────────────
    // MeshtasticManager.swift:168-171, :558, :575

    /** PRIVATE_APP — OSHI↔OSHI envelopes. :169 */
    const val PORT_OSHI = 256L

    /** TEXT_MESSAGE_APP — interop with the stock Meshtastic app. :170 */
    const val PORT_TEXT = 1L

    /** ROUTING_APP — how the mesh reports what became of a `want_ack` packet. :558 */
    /**
     * __LORA_POSITION_BEACON_2026_10_28__ POSITION_APP. The port every Meshtastic client
     * reads to put a node on its map, and the one this app already PARSES on receive.
     */
    const val PORT_POSITION = 3L

    const val PORT_ROUTING = 5L

    /** ADMIN_APP — config writes to the node on the other end of the BLE link. :575 */
    const val PORT_ADMIN = 6L

    /**
     * __ANTI_JAMMING_PHASE1_2026_11_13__ TELEMETRY_APP. Our own radio sends its DeviceMetrics (channel
     * utilisation, TX airtime) to the phone about once a minute and its LocalStats (RX counters, noise
     * floor) every 15 minutes, or when asked ([localStatsRequest]). Read for [LoRaJammingDetector].
     */
    const val PORT_TELEMETRY = 67L

    /** :168 */
    val BROADCAST_ADDR: UInt = 0xFFFFFFFFu

    // ─────────────────────────── Encoders ───────────────────────────

    /** `ToRadio.want_config_id`, field 3 varint. MeshtasticManager.swift:410-412 */
    fun wantConfig(configId: UInt): ByteArray =
        ProtoWriter().varint(3, configId.toLong()).data

    /**
     * A whole `ToRadio` carrying one `MeshPacket`. MeshtasticManager.swift:766-782.
     *
     *   Data     : 1 varint portnum, 2 bytes payload
     *   MeshPacket: 2 fixed32 to, 4 bytes decoded, 6 fixed32 id, 10 varint want_ack
     *   ToRadio  : 1 bytes packet
     *
     * `want_ack` is written ONLY when true — protobuf omits defaults, and a
     * `want_ack: false` on the wire is the same bytes as no field at all.
     */
    /**
     * @param channel __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ the radio's
     *   channel SLOT to transmit on. `MeshPacket.channel` is field 3 and this builder
     *   never emitted it — ironic, since [MeshPacket.channel] below PARSES it — so
     *   every frame OSHI has ever sent went out on slot 0. For OSHI's own envelopes
     *   and for direct messages that is right and unchanged. For a LoRa channel ROOM
     *   it was not: text typed into a private channel was broadcast on the primary,
     *   which is normally LongFast and normally public.
     *
     *   Omitted from the wire when 0, because 0 is the proto default and a stock
     *   Meshtastic encoder omits it too — so every frame that worked before this
     *   change is byte-identical after it.
     */
    fun toRadioPacket(
        to: UInt,
        portnum: Long,
        payload: ByteArray,
        wantAck: Boolean,
        packetId: UInt,
        channel: Int = 0,
        wantResponse: Boolean = false,
    ): ByteArray {
        val dataW = ProtoWriter()
            .varint(1, portnum)          // Data.portnum        :768
            .bytes(2, payload)           // Data.payload        :769
        if (wantResponse) dataW.varint(3, 1)   // Data.want_response — only for local requests (telemetry)
        val data = dataW.data

        val pkt = ProtoWriter()
            .fixed32(2, to)              // MeshPacket.to       :773
        if (channel != 0) pkt.varint(3, channel.toLong())   // MeshPacket.channel (a client sends the index)
        pkt.bytes(4, data)               // MeshPacket.decoded  :774
            .fixed32(6, packetId)        // MeshPacket.id       :775
        if (wantAck) pkt.varint(10, 1)   // MeshPacket.want_ack :776

        return ProtoWriter().bytes(1, pkt.data).data   // ToRadio.packet :779
    }

    /**
     * __LORA_POSITION_BEACON_2026_10_28__ A `Position` payload for POSITION_APP.
     *
     * Here rather than inside `MeshtasticManager.broadcastPosition` so a test can call the
     * SHIPPED encoder instead of reimplementing its arithmetic — a test that re-derives
     * `Math.round(lat * 1e7)` agrees with itself whatever the sender does.
     *
     * `latitude_i`/`longitude_i` are **sfixed32 scaled by 1e-7**, written little-endian
     * and read SIGNED. Getting the sign wrong does not corrupt the frame; it moves the
     * node to the other side of the planet on somebody else's map.
     */
    fun positionPayload(latitude: Double, longitude: Double, timeSec: Long): ByteArray =
        ProtoWriter()
            .fixed32(1, Math.round(latitude * 1e7).toInt().toUInt())
            .fixed32(2, Math.round(longitude * 1e7).toInt().toUInt())
            .varint(4, timeSec)
            .data

    /**
     * __LORA_FIXED_POSITION_2026_10_28__ `AdminMessage{ set_fixed_position: Position }` —
     * field **41**.
     *
     * This exists because [positionPayload] broadcast to ^all DOES NOT WORK, and the
     * measurement is the reason to keep both: with two ThinkNode M1s in one room, ten
     * POSITION_APP broadcasts over four minutes never reached the second radio, while a
     * plain interop TEXT broadcast from the SAME radio in the same second arrived in 3
     * seconds — on a mesh where that receiver was reading positions from 36 other nodes
     * correctly. Every write returned true, because a write is bytes leaving the process
     * and not a transmission. The portnum was the only difference between the two sends.
     *
     * The firmware owns a node's position. A client does not announce one on its behalf; it
     * tells the node where it is, and the node's PositionModule broadcasts on its own
     * schedule (`position_broadcast_secs`) — which is also why a 15-minute policy of ours
     * is the wrong shape for this lane.
     *
     * Field 41 continues the enumeration already proven here against a real radio:
     * `set_owner` 32, `set_channel` 33, `set_config` 34. (`remove_fixed_position` is 42.)
     *
     * ⚠️ This WRITES THE NODE'S CONFIGURATION and persists on the device: a radio told it
     * is in Geneva stays in Geneva for every app that connects to it afterwards.
     */
    fun setFixedPositionPayload(latitude: Double, longitude: Double): ByteArray? {
        if (latitude == 0.0 && longitude == 0.0) return null
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null
        val position = ProtoWriter()
            .fixed32(1, Math.round(latitude * 1e7).toInt().toUInt())   // Position.latitude_i
            .fixed32(2, Math.round(longitude * 1e7).toInt().toUInt())  // Position.longitude_i
            .data
        return ProtoWriter().bytes(41, position).data                  // AdminMessage.set_fixed_position
    }

    /** `AdminMessage{ remove_fixed_position: true }` — field 42, a bool. */
    fun removeFixedPositionPayload(): ByteArray = ProtoWriter().varint(42, 1).data

    /**
     * `AdminMessage.set_config` carrying a `Config.lora`. MeshtasticManager.swift:604-622.
     *
     * ⚠️ THE WHOLE LoRaConfig IS REPLACED by what is sent, and protobuf omits
     * defaults, so ANY FIELD LEFT OUT ARRIVES AS ZERO. Leaving `use_preset` out
     * sends `false`, which switches the radio to manual bandwidth/spread factor
     * of 0 and produces a node that is configured, on the air, and unable to talk
     * to anyone (MeshtasticManager.swift:587-591). Every field that matters is
     * therefore set explicitly — that is not defensive padding, it is the
     * contract.
     *
     * Field 34, NOT 33: `set_channel` is 33 and carries a Channel; `set_config`
     * is 34 and carries a Config. Sending a Config under 33 made "Set region"
     * silently do nothing (MeshtasticManager.swift:616-621).
     */
    fun adminSetLoRaConfig(regionRaw: Long, presetRaw: Long, hopLimit: Long): ByteArray {
        val lora = ProtoWriter()
            .varint(1, 1)            // use_preset = true   :606
            .varint(2, presetRaw)    // modem_preset        :607
            .varint(7, regionRaw)    // region              :608
            .varint(8, hopLimit)     // hop_limit           :609
            .varint(9, 1)            // tx_enabled = true   :610
            .data
        val config = ProtoWriter().bytes(6, lora).data          // Config.lora :613
        return ProtoWriter().bytes(34, config).data             // AdminMessage.set_config :622
    }

    /**
     * `AdminMessage.set_config` carrying a `Config.bluetooth`.
     * MeshtasticManager.swift:738-753.
     */
    fun adminSetBluetoothConfig(modeRaw: Long): ByteArray {
        val bt = ProtoWriter()
            .varint(1, 1)         // enabled = true :740
            .varint(2, modeRaw)   // mode           :741
            .data
        val config = ProtoWriter().bytes(7, bt).data            // Config.bluetooth :744
        return ProtoWriter().bytes(34, config).data             // AdminMessage.set_config :753
    }

    /**
     * `AdminMessage.set_channel` (field 33) carrying a `Channel` — WHICH MESH
     * THIS NODE IS ON.
     *
     * Field 33 is the one `adminSetLoRaConfig` warns about: it is NOT a Config,
     * it is a Channel, and the two are not interchangeable. Sending a Config
     * under 33 made "Set region" silently do nothing; sending a Channel under 34
     * would do the same to this.
     *
     *     Channel          { index = 1, settings = 2, role = 3 }
     *     ChannelSettings  { psk = 2, name = 3 }
     *     Channel.Role     { DISABLED = 0, PRIMARY = 1, SECONDARY = 2 }
     *
     * index 0 + role PRIMARY is the slot every node's main channel lives in, and
     * the slot the Meshtastic app edits when it says "Channel".
     *
     * Both halves are written EXPLICITLY, including an empty name: this replaces
     * the channel wholesale, so a field left out arrives as zero — and a psk
     * left out is `no encryption at all`, not `keep what you had`. An empty name
     * with [LoRaChannel.DEFAULT_PSK] is the stock LongFast channel.
     */
    fun adminSetChannel(
        name: String,
        psk: ByteArray,
        /**
         * Which of the node's eight slots. 0 is the primary; 1-7 are secondaries.
         * Defaulted so every existing caller keeps writing the primary.
         */
        index: Int = 0,
        /** [LoRaChannelSet.ROLE_PRIMARY] or [LoRaChannelSet.ROLE_SECONDARY]. */
        roleRaw: Long = LoRaChannelSet.ROLE_PRIMARY,
    ): ByteArray {
        val settings = ProtoWriter()
            .bytes(2, psk)                                              // ChannelSettings.psk
            .bytes(3, LoRaChannel.clampName(name).toByteArray(Charsets.UTF_8))  // .name (≤ 11 BYTES)
            .data
        val channel = ProtoWriter()
            .varint(1, index.toLong())  // Channel.index
            .bytes(2, settings)         // Channel.settings
            .varint(3, roleRaw)         // Channel.role
            .data
        return ProtoWriter().bytes(33, channel).data                    // AdminMessage.set_channel
    }

    /**
     * Clear slot [index] — `role = DISABLED`, empty settings.
     *
     * Needed because joining a link with two channels onto a node that had four
     * must leave the node with two, not with two new ones and two stale ones the
     * user can no longer see the origin of.
     */
    fun adminDisableChannel(index: Int): ByteArray {
        val channel = ProtoWriter()
            .varint(1, index.toLong())
            .bytes(2, ByteArray(0))     // settings present but empty
            .varint(3, 0)               // Channel.role = DISABLED
            .data
        return ProtoWriter().bytes(33, channel).data
    }

    /**
     * `AdminMessage.set_owner` (field 32) carrying a `User`.
     * MeshtasticManager.swift:712-718. short_name is truncated to 4 characters —
     * that is what fits on the node's display and in other clients' lists.
     */
    fun adminSetOwner(nodeNum: UInt, longName: String, shortName: String): ByteArray {
        val user = ProtoWriter()
            .bytes(1, nodeIdString(nodeNum).toByteArray(Charsets.UTF_8))   // User.id         :713
            .bytes(2, longName.toByteArray(Charsets.UTF_8))                // User.long_name  :714
            // __SHORT_NAME_IS_BYTES_2026_10_21__ `take(4)` comptait des Char
            // UTF-16: deux emoji passaient (8 octets) et une paire de
            // substitution pouvait etre coupee en deux. Voir
            // `LoRaFormat.clampShortName`.
            .bytes(3, com.oshi.messenger.ui.viewmodel.LoRaFormat.clampShortName(shortName)
                .toByteArray(Charsets.UTF_8))                              // User.short_name :715 (≤4 OCTETS)
            .data
        return ProtoWriter().bytes(32, user).data                          // set_owner :718
    }

    /**
     * `AdminMessage.set_channel` (field 33) carrying Channel{index=1, settings=2{psk=2, name=3}, role=3}.
     * Used to give a STOCK radio the private OSHI channel (OshiMeshProtocol.CHANNEL_*).
     */
    fun adminSetChannel(index: Int, name: String, psk: ByteArray, role: Long = 2): ByteArray {
        val settings = ProtoWriter()
            .bytes(2, psk)
            .bytes(3, name.toByteArray(Charsets.UTF_8))
            .data
        val channel = ProtoWriter()
            .varint(1, index.toLong())
            .bytes(2, settings)
            .varint(3, role)
            .data
        return ProtoWriter().bytes(33, channel).data
    }

    /** "!a1b2c3d4". MeshtasticManager.swift:713 */
    fun nodeIdString(nodeNum: UInt): String = "!" + nodeNum.toString(16).padStart(8, '0')

    // ─────────────────────────── Decoders ───────────────────────────

    /** One thing a `FromRadio` frame can say. MeshtasticManager.swift:837-861 */
    sealed class FromRadio {
        data class Packet(val packet: MeshPacket) : FromRadio()          // field 2 :840
        data class MyNodeInfo(val myNodeNum: UInt) : FromRadio()         // field 3 :841-844
        data class NodeInfoUpdate(val info: NodeInfo) : FromRadio()      // field 4 :845
        data class ConfigUpdate(val config: NodeConfig) : FromRadio()    // field 5 :846
        data class ConfigComplete(val configId: UInt) : FromRadio()      // field 7 :847

        /**
         * __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ field 10, a `Channel`.
         *
         * The node streams ALL EIGHT of its channel slots during the ordinary
         * `wantConfig` handshake, unasked — the same handshake that already gives
         * us `my_info`, `node_info` and `config`. Nothing here had a case for
         * field 10, so every one of them was dropped on the floor and the app
         * could write the primary channel but never read back a single one. That
         * is why a user could not SEE which mesh their node was on, only
         * overwrite it. Same shape as the POSITION_APP portnum that had no case.
         *
         * No AdminMessage round trip is needed to list channels. It was already
         * arriving.
         */
        data class ChannelUpdate(val channel: NodeChannel) : FromRadio()  // field 10
    }

    /**
     * One of the node's eight channel slots, as the radio reports it.
     *
     * `Channel { index = 1, settings = 2, role = 3 }`, and
     * `Channel.Role { DISABLED = 0, PRIMARY = 1, SECONDARY = 2 }`.
     */
    data class NodeChannel(
        val index: Int,
        val name: String,
        val psk: ByteArray,
        val roleRaw: Long,
    ) {
        val isPrimary: Boolean get() = roleRaw == 1L
        val isSecondary: Boolean get() = roleRaw == 2L

        /**
         * A DISABLED slot is not a channel the user is on. Most nodes report
         * seven of them and showing those as "channels" would be a list of seven
         * blanks.
         */
        val isDisabled: Boolean get() = roleRaw == 0L

        /** Blank means the stock default, which users know as LongFast. */
        val displayName: String get() = LoRaChannel.displayName(name)

        // ByteArray in a data class — compared by reference unless we say
        // otherwise, which would make an unchanged channel look changed on every
        // handshake and re-render the list for ever.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NodeChannel) return false
            return index == other.index && name == other.name &&
                roleRaw == other.roleRaw && psk.contentEquals(other.psk)
        }

        override fun hashCode(): Int {
            var h = index
            h = 31 * h + name.hashCode()
            h = 31 * h + roleRaw.hashCode()
            h = 31 * h + psk.contentHashCode()
            return h
        }
    }

    /** `Channel { index = 1, settings = 2, role = 3 }`. */
    fun parseChannel(d: ByteArray): NodeChannel? {
        var index = 0
        var role = 0L
        var name = ""
        var psk = ByteArray(0)
        var sawSettings = false
        for (f in LoRaProto.parseFields(d)) {
            when {
                f.number == 1 && f.wire == 0 -> index = f.varintValue.toInt()
                f.number == 3 && f.wire == 0 -> role = f.varintValue
                f.number == 2 && f.wire == 2 -> {
                    sawSettings = true
                    for (sf in LoRaProto.parseFields(f.payload)) {
                        when {
                            sf.number == 2 && sf.wire == 2 -> psk = sf.payload
                            sf.number == 3 && sf.wire == 2 ->
                                name = sf.payload.toString(Charsets.UTF_8)
                        }
                    }
                }
            }
        }
        // index 0 with no settings and no role is an empty frame, not slot zero.
        if (!sawSettings && role == 0L && index == 0) return null
        if (index !in 0 until LoRaChannelSet.MAX_CHANNELS) return null
        return NodeChannel(index = index, name = name, psk = psk, roleRaw = role)
    }

    /** MeshtasticManager.swift:952-996 */
    data class MeshPacket(
        val from: UInt,
        val portnum: Long,
        val payload: ByteArray,
        /** Field 6 of the Data SUBMESSAGE — ties a ROUTING_APP reply to our packet. :976-987 */
        val requestId: UInt,
        val rssi: Int?,
        val snr: Float?,
        /**
         * __A_CHANNEL_BROADCAST_IS_NOT_A_PRIVATE_MESSAGE_2026_11_04__
         * `MeshPacket.to`, field 2. [BROADCAST_ADDR] means this went to the whole
         * channel; any other value means it was addressed to one node.
         *
         * Neither this nor [channelIndex] was parsed before, so a message put on a
         * PUBLIC channel — readable by every radio in range, because the default
         * key is published in Meshtastic's own source — arrived looking exactly
         * like a private one and was shown in an ordinary one-to-one thread.
         * Someone at a protest could reasonably read that thread as private and
         * answer accordingly. Of everything in this app, that is the mistake most
         * worth going out of our way to make impossible.
         *
         * Defaulted to 0 so every existing construction site keeps compiling; no
         * real firmware omits the field.
         */
        val to: UInt = 0u,
        /**
         * `MeshPacket.channel`, field 3: the index into the node's SECONDARY
         * channel table, unset (0) for the primary.
         *
         * Only meaningful against our own channel list, because the proto says so
         * out loud — "the index is local to each node, so it has no meaning when
         * sent between nodes". We resolve it against the slots the node itself
         * reported, which is precisely that local table.
         */
        val channelIndex: Int = 0,
    ) {
        /** True when this went to the whole channel rather than to us alone. */
        val isBroadcast: Boolean get() = to == BROADCAST_ADDR

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MeshPacket) return false
            return from == other.from && portnum == other.portnum &&
                payload.contentEquals(other.payload) && requestId == other.requestId &&
                rssi == other.rssi && snr == other.snr &&
                to == other.to && channelIndex == other.channelIndex
        }

        override fun hashCode(): Int {
            var h = from.hashCode()
            h = h * 31 + portnum.hashCode()
            h = h * 31 + payload.contentHashCode()
            h = h * 31 + requestId.hashCode()
            h = h * 31 + (rssi ?: 0)
            h = h * 31 + (snr?.hashCode() ?: 0)
            h = h * 31 + to.hashCode()
            h = h * 31 + channelIndex
            return h
        }
    }

    /** MeshtasticManager.swift:901-950 */
    data class NodeInfo(
        val num: UInt,
        val userId: String,
        val longName: String,
        val shortName: String,
        val snr: Float,
        val latitude: Double?,
        val longitude: Double?,
        val hopsAway: Int?,
        /**
         * NodeInfo field 5 `last_heard` (fixed32 Unix seconds, radio clock), as Unix
         * millis. Null when absent or implausible — a radio that never got the time
         * reports 0 or a 1970 value. __LORA_ONLINE_COUNT_2026_09_22__
         */
        val lastHeardMillis: Long? = null,
    )

    /** MeshtasticManager.swift:870-899 */
    data class NodeConfig(
        /**
         * Null when the frame carried no `Config.lora` at all. When the
         * submessage IS present but has no region field, this is 0 = UNSET:
         * protobuf omits zero values, so "absent" and "unset" are the same
         * thing and both mean CANNOT TRANSMIT (:881-884).
         */
        val regionRaw: Long?,
        val txEnabled: Boolean?,
        /** Null when no `Config.bluetooth`; 0 (RANDOM_PIN) when present but omitted (:890-895). */
        val bluetoothPairingRaw: Long?,
    )

    fun parseFromRadio(d: ByteArray): List<FromRadio> {
        val out = ArrayList<FromRadio>()
        for (f in LoRaProto.parseFields(d)) {
            when {
                f.number == 2 && f.wire == 2 -> parseMeshPacket(f.payload)?.let { out.add(FromRadio.Packet(it)) }
                f.number == 3 && f.wire == 2 -> {
                    // MyNodeInfo.my_node_num, field 1 varint. :842-843
                    LoRaProto.parseFields(f.payload)
                        .firstOrNull { it.number == 1 && it.wire == 0 }
                        ?.let { out.add(FromRadio.MyNodeInfo(it.asUInt32())) }
                }
                f.number == 4 && f.wire == 2 -> parseNodeInfo(f.payload)?.let { out.add(FromRadio.NodeInfoUpdate(it)) }
                f.number == 5 && f.wire == 2 -> out.add(FromRadio.ConfigUpdate(parseConfig(f.payload)))
                f.number == 7 && f.wire == 0 -> out.add(FromRadio.ConfigComplete(f.asUInt32()))
                // __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ see FromRadio.ChannelUpdate.
                f.number == 10 && f.wire == 2 ->
                    parseChannel(f.payload)?.let { out.add(FromRadio.ChannelUpdate(it)) }
            }
        }
        return out
    }

    /** MeshtasticManager.swift:952-996. Returns null when `from` or `decoded` is missing (:964). */
    fun parseMeshPacket(d: ByteArray): MeshPacket? {
        var from: UInt = 0u
        var decoded: ByteArray? = null
        var rssi: Int? = null
        var snr: Float? = null
        var to: UInt = 0u
        var channelIndex = 0
        for (f in LoRaProto.parseFields(d)) {
            when {
                f.number == 1 && f.wire == 5 -> from = f.asUInt32()          // :957
                // __A_CHANNEL_BROADCAST_IS_NOT_A_PRIVATE_MESSAGE_2026_11_04__
                // `to` is a fixed32, `channel` a varint. Neither was ever read.
                f.number == 2 && f.wire == 5 -> to = f.asUInt32()            // MeshPacket.to
                f.number == 3 && f.wire == 0 -> channelIndex = f.varintValue.toInt()
                f.number == 4 && f.wire == 2 -> decoded = f.payload          // :958
                f.number == 8 && f.wire == 5 -> snr = f.asFloat()            // :959
                f.number == 12 && f.wire == 0 -> rssi = f.asInt32()          // :960 rx_rssi
            }
        }
        val dec = decoded ?: return null
        if (from == 0u) return null

        var portnum = 0L
        var payload = ByteArray(0)
        var requestId: UInt = 0u
        for (f in LoRaProto.parseFields(dec)) {
            when {
                f.number == 1 && f.wire == 0 -> portnum = f.varintValue      // :981
                f.number == 2 && f.wire == 2 -> payload = f.payload          // :982
                // `request_id` is emitted as a VARINT by some firmware builds and
                // as a FIXED32 by others. Accept BOTH, or every ACK is silently
                // missed on one of them. MeshtasticManager.swift:983-987.
                f.number == 6 && (f.wire == 0 || f.wire == 5) -> requestId = f.asUInt32()
            }
        }
        return MeshPacket(from, portnum, payload, requestId, rssi, snr, to, channelIndex)
    }

    /** MeshtasticManager.swift:901-933. Null when `num` is 0 (:933). */
    fun parseNodeInfo(d: ByteArray): NodeInfo? {
        var num: UInt = 0u
        var uid = ""; var lname = ""; var sname = ""
        var snr = 0f
        var lat: Double? = null
        var lon: Double? = null
        var hops: Int? = null
        var lastHeardMillis: Long? = null
        for (f in LoRaProto.parseFields(d)) {
            when {
                f.number == 1 && f.wire == 0 -> num = f.asUInt32()                       // :906
                f.number == 2 && f.wire == 2 -> {                                        // User :907-915
                    for (uf in LoRaProto.parseFields(f.payload)) {
                        when {
                            uf.number == 1 && uf.wire == 2 -> uid = String(uf.payload, Charsets.UTF_8)
                            uf.number == 2 && uf.wire == 2 -> lname = String(uf.payload, Charsets.UTF_8)
                            uf.number == 3 && uf.wire == 2 -> sname = String(uf.payload, Charsets.UTF_8)
                        }
                    }
                }
                f.number == 3 && f.wire == 2 -> {                                        // Position :916-927
                    for (pf in LoRaProto.parseFields(f.payload)) {
                        // latitude_i / longitude_i are sfixed32 scaled by 1e-7 and
                        // must be read SIGNED — read unsigned, everything south of
                        // the equator or west of Greenwich lands near the north
                        // pole. MeshtasticManager.swift:918-924.
                        when {
                            pf.number == 1 && pf.wire == 5 -> lat = pf.asInt32() * 1e-7
                            pf.number == 2 && pf.wire == 5 -> lon = pf.asInt32() * 1e-7
                        }
                    }
                }
                f.number == 4 && f.wire == 5 -> snr = f.asFloat()                        // :928
                f.number == 9 && f.wire == 0 -> hops = f.varintValue.toInt()             // :929
                f.number == 5 && f.wire == 5 -> {                                        // last_heard
                    val secs = f.asUInt32().toLong()
                    if (secs > 1_600_000_000L) lastHeardMillis = secs * 1000L
                }
            }
        }
        if (num == 0u) return null
        return NodeInfo(num, uid, lname, sname, snr, lat, lon, hops, lastHeardMillis)
    }

    /** MeshtasticManager.swift:870-899. */
    fun parseConfig(d: ByteArray): NodeConfig {
        var regionRaw: Long? = null
        var txEnabled: Boolean? = null
        var pairing: Long? = null

        for (f in LoRaProto.parseFields(d)) {
            if (f.number == 6 && f.wire == 2) {                 // Config.lora :871
                var found: Long? = null
                var txOn = true                                  // :873 — default when the field is absent
                for (lf in LoRaProto.parseFields(f.payload)) {
                    when {
                        lf.number == 7 && lf.wire == 0 -> found = lf.varintValue   // region     :876
                        lf.number == 9 && lf.wire == 0 -> txOn = lf.varintValue != 0L // tx_enabled :877
                    }
                }
                // Absent region == UNSET == cannot transmit. :881-884
                regionRaw = found ?: 0L
                txEnabled = txOn
            }
            if (f.number == 7 && f.wire == 2) {                 // Config.bluetooth :889
                // Mode 0 (RANDOM_PIN) is the default and is therefore omitted
                // from the wire, so absent means randomPin. :890-895
                var mode = 0L
                for (bf in LoRaProto.parseFields(f.payload)) {
                    if (bf.number == 2 && bf.wire == 0) mode = bf.varintValue
                }
                pairing = mode
            }
        }
        return NodeConfig(regionRaw, txEnabled, pairing)
    }

    /**
     * `Routing.error_reason` is field 3. Absent or 0 (NONE) means DELIVERED;
     * anything else is a failure the radio is naming. MeshtasticManager.swift:792-796.
     */
    fun routingErrorReason(payload: ByteArray): Long {
        var reason = 0L
        for (f in LoRaProto.parseFields(payload)) {
            if (f.number == 3 && f.wire == 0) reason = f.varintValue
        }
        return reason
    }

    // ─────────────────────────── Telemetry (anti-jamming) ───────────────────────────

    /**
     * What a TELEMETRY_APP payload says about the radio channel. Only the fields the jamming detector
     * reads; null when the payload is neither DeviceMetrics nor LocalStats.
     *
     *   Telemetry   : 1 fixed32 time, 2 DeviceMetrics, 6 LocalStats
     *   DeviceMetrics: 3 float channel_utilization, 4 float air_util_tx
     *   LocalStats  : 2 float channel_utilization, 3 float air_util_tx, 5 num_packets_rx (good + bad),
     *                 6 num_packets_rx_bad, 15 int32 noise_floor (0 = no sample: there is no presence bit)
     */
    data class RadioTelemetry(
        val channelUtilPct: Float?,
        val airUtilTxPct: Float?,
        val noiseFloorDbm: Int?,
        val rxTotal: Long?,
        val rxBad: Long?,
        val isLocalStats: Boolean,
    )

    fun parseTelemetry(payload: ByteArray): RadioTelemetry? {
        for (f in LoRaProto.parseFields(payload)) {
            if (f.wire != 2) continue
            if (f.number == 2) {
                var util: Float? = null; var air: Float? = null
                for (m in LoRaProto.parseFields(f.payload)) when {
                    m.number == 3 && m.wire == 5 -> util = m.asFloat()
                    m.number == 4 && m.wire == 5 -> air = m.asFloat()
                }
                return RadioTelemetry(util, air, null, null, null, isLocalStats = false)
            }
            if (f.number == 6) {
                var util: Float? = null; var air: Float? = null; var noise: Int? = null
                var rx: Long? = null; var bad: Long? = null
                for (m in LoRaProto.parseFields(f.payload)) when {
                    m.number == 2 && m.wire == 5 -> util = m.asFloat()
                    m.number == 3 && m.wire == 5 -> air = m.asFloat()
                    m.number == 5 && m.wire == 0 -> rx = m.varintValue and 0xFFFFFFFFL
                    m.number == 6 && m.wire == 0 -> bad = m.varintValue and 0xFFFFFFFFL
                    m.number == 15 && m.wire == 0 -> noise = m.asInt32().takeIf { it != 0 }
                }
                // proto3 omits zero counters, so a LocalStats without them means 0, not "unknown".
                return RadioTelemetry(util, air, noise, rx ?: 0L, bad ?: 0L, isLocalStats = true)
            }
        }
        return null
    }

    /** `Telemetry { local_stats {} }` — asks our own radio for its LocalStats (sent with want_response). */
    fun localStatsRequest(): ByteArray = ProtoWriter().bytes(6, ByteArray(0)).data
}
