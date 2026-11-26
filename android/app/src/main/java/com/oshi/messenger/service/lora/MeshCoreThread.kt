package com.oshi.messenger.service.lora

/**
 * Conversation keys for MeshCore USERS — people on plain MeshCore apps, not OSHI. Pure JVM.
 *
 * Deliberately NOT the `lora!` / `lorachan!` families of the Meshtastic interop ([LoRaNodeIdentityMap],
 * [LoRaChannelThread]): those keys are routed to the Meshtastic radio, and a MeshCore contact is not a
 * Meshtastic node. Every key here is answered through the MeshCore radio only.
 *
 *  - `meshcore!<64 hex>`: a contact, by its full public key (from the radio's contact table).
 *  - `meshcore!<12 hex>`: a sender whose 6-byte key prefix (all a received DM carries) was not in the table
 *    yet. Still answerable: CMD_SEND_TXT_MSG addresses by that same 6-byte prefix.
 *  - `mcchan!<name>`: a channel, by its exact name. NOT lower-cased: a hashtag channel's key is
 *    SHA-256 of its name, so "#Paris" and "#paris" are two different channels.
 *
 * None of this is OSHI end-to-end encryption: MeshCore protects DMs and channels with its own AES-128 and a
 * 2-byte MAC, and a public or hashtag channel can be read by anyone who knows its name.
 */
object MeshCoreThread {
    const val CONTACT_PREFIX = "meshcore!"
    const val CHANNEL_PREFIX = "mcchan!"

    fun isContactKey(key: String): Boolean = key.startsWith(CONTACT_PREFIX)
    fun isChannelKey(key: String): Boolean = key.startsWith(CHANNEL_PREFIX)
    fun isMeshCoreKey(key: String): Boolean = isContactKey(key) || isChannelKey(key)

    fun contactKey(publicKey: ByteArray): String = CONTACT_PREFIX + MeshCore.hex(publicKey)

    /** The key for a received DM: the full key when the contact is known, else the 6-byte prefix. */
    fun contactKeyFor(prefix: ByteArray, contact: MeshCore.Contact?): String =
        if (contact != null) contactKey(contact.publicKey)
        else CONTACT_PREFIX + MeshCore.hex(prefix.copyOf(MeshCore.PUB_KEY_PREFIX))

    fun channelKey(name: String): String = CHANNEL_PREFIX + name.trim()

    fun channelName(key: String): String? = if (isChannelKey(key)) key.removePrefix(CHANNEL_PREFIX) else null

    /** The 6-byte prefix CMD_SEND_TXT_MSG addresses, from either contact-key form. */
    fun publicKeyPrefix(key: String): ByteArray? {
        if (!isContactKey(key)) return null
        val hex = key.removePrefix(CONTACT_PREFIX)
        if (hex.length < 2 * MeshCore.PUB_KEY_PREFIX) return null
        return MeshCore.unhex(hex.substring(0, 2 * MeshCore.PUB_KEY_PREFIX).lowercase())
    }

    /** The slot holding the channel [key] names, matched by exact name; never "#oshi-bridge". */
    fun channelIndexFor(key: String, channels: List<MeshCore.ChannelInfo>): Int? {
        val name = channelName(key)?.takeIf { it.isNotEmpty() } ?: return null
        if (name == OshiBridgeWire.CHANNEL_NAME) return null
        return channels.firstOrNull { it.name == name }?.idx
    }

    /** The contact a key refers to, when the table has it (by full key or by prefix). */
    fun contactFor(key: String, contacts: List<MeshCore.Contact>): MeshCore.Contact? {
        val prefix = publicKeyPrefix(key) ?: return null
        val full = key.removePrefix(CONTACT_PREFIX).lowercase()
        return contacts.firstOrNull { it.publicKeyHex == full } ?: contacts.firstOrNull { it.hasPrefix(prefix) }
    }

    /** What to call the conversation: the contact's advert name, the channel name, or "MeshCore a1b2c3". */
    fun displayName(key: String, contacts: List<MeshCore.Contact> = emptyList()): String {
        channelName(key)?.let { return it }
        contactFor(key, contacts)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return "MeshCore " + key.removePrefix(CONTACT_PREFIX).take(6)
    }

    /**
     * Readable by anyone who knows its name: the stock Public channel, or a hashtag channel. When the radio's
     * channel table does not have it, the name alone decides (a '#' name or "Public"), erring towards the warning.
     */
    fun isPublicChannelKey(key: String, channels: List<MeshCore.ChannelInfo>): Boolean {
        val name = channelName(key) ?: return false
        val info = channels.firstOrNull { it.name == name }
            ?: return name.startsWith("#") || name.equals(MeshCore.PUBLIC_CHANNEL_NAME, ignoreCase = true)
        return MeshCore.isPublicChannel(info.name, info.secret)
    }

    /** Contacts a person can write to: companions (chat) and room servers; not repeaters or sensors. */
    fun chatContacts(contacts: List<MeshCore.Contact>): List<MeshCore.Contact> =
        contacts.filter { it.type == MeshCore.ADV_TYPE_CHAT || it.type == MeshCore.ADV_TYPE_ROOM }
            .sortedBy { it.name.lowercase() }
}

/**
 * Which radios can carry an OSHI message right now. Before MeshCore interop only the Meshtastic radio was
 * consulted, so a phone holding ONLY a MeshCore radio was treated as having no radio at all: the router never
 * offered LoRa, and cover mode parked messages the radio could have carried.
 */
object LoRaRadios {
    /** Either radio counts as a ready LoRa route (the same OMP frames go out on each). */
    fun anyReady(meshtasticReady: Boolean, meshCoreReady: Boolean): Boolean = meshtasticReady || meshCoreReady

    /**
     * Jamming is measured by the Meshtastic radio only. It keeps LoRa out of the route only when that radio is
     * the whole LoRa route: a ready MeshCore radio is a second, separately measured link.
     */
    fun routeJammed(meshtasticReady: Boolean, meshtasticJammed: Boolean, meshCoreReady: Boolean): Boolean =
        meshtasticReady && meshtasticJammed && !meshCoreReady

    /**
     * May a LoRa hand-off be shown DELIVERED? Only when a Meshtastic radio carried it (its own receipt machinery).
     * A MeshCore "#oshi-bridge" broadcast has no ACK at all: it is SENT, and stays so until the peer's receipt.
     */
    fun handOffCountsAsDelivered(viaMeshtastic: Boolean, viaMeshCore: Boolean): Boolean = viaMeshtastic
}
