package com.oshi.messenger.service.channelweb

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.OSHIControlPayload

/**
 * __CHANNEL_WEB_2026_11_26__ What of a channel post goes to the web (spec §9.2). Pure, tested.
 *
 *  - text → its text (converted to the web syntax, [ChannelWebText]);
 *  - photo / GIF → the image + its caption;
 *  - video, voice, video note, file → the caption alone (the web has no player and the spec
 *    uploads images only); a post with no caption publishes nothing — same rule as iOS;
 *  - location, contact card, polls, check-ins, live-location ticks and every other control
 *    payload → nothing: they are personal data or app-only objects, not text.
 */
object ChannelWebContent {
    data class Plan(val text: String, val withImage: Boolean)

    fun plan(content: String, mediaType: MediaType?): Plan? {
        if (OSHIControlPayload.isControl(content) || OSHIControlPayload.isMediaEnvelope(content)) return null
        val text = ChannelWebText.forPublish(content)
        return when (mediaType) {
            null -> text.takeIf { it.isNotBlank() }?.let { Plan(it, false) }
            MediaType.IMAGE -> Plan(text, true)
            MediaType.VIDEO, MediaType.AUDIO, MediaType.DOCUMENT ->
                text.takeIf { it.isNotBlank() }?.let { Plan(it, false) }
            MediaType.LOCATION, MediaType.CONTACT -> null
        }
    }
}
