package com.oshi.messenger.service

/**
 * The trailing capability run of a call offer, peeled off the callId.
 *
 * Port of iOS `VoiceCallManager.swift:13034-13070 peelOfferCapabilities`.
 *
 * A callId is a UUID string — hex digits and hyphens, every byte >= 0x2D — so a
 * trailing byte < 0x2D is an unambiguous, length-independent marker for "capability
 * byte". Two things this fixes over the previous inline peel:
 *
 *  - the run is interpreted POSITIONALLY, in append order, exactly as iOS writes it
 *    (`slot(0)=OshiCodec, slot(1)=16 kHz PCM, slot(2)=AppleCodec`). A `contains()`
 *    test happens to agree while the three flags are 0x01/0x02/0x04, and misreads
 *    the moment a future flag repeats an earlier value in another slot;
 *  - the run is bounded at 8 bytes, so a malformed body cannot eat the whole callId
 *    and leave `callId = ""`.
 *
 * INVARIANT for anyone adding a capability: the flag value MUST stay < 0x2D.
 */
object CallOfferCapabilities {

    const val OSHI_CODEC_FLAG = 0x01
    const val RAW_PCM_16K_FLAG = 0x02
    const val APPLE_CODEC_FLAG = 0x04

    /**
     * __VUPG_CAPABILITY_2026_09_14__ Quatrième emplacement: « je sais faire la
     * vidéo ». Reste sous 0x2D, comme l'invariant l'exige.
     */
    const val VIDEO_FLAG = 0x08

    /** __WB_ADPCM_CODEC_2026_09_23__ 5th slot — see audio/WbAdpcmCodec.kt. < 0x2D. */
    const val WB_ADPCM_FLAG = 0x10

    /** __OPUS_CODEC_2026_09_23__ 6th slot — see audio/OpusCallCodec.kt. < 0x2D. */
    const val OPUS_FLAG = 0x20

    /** iOS bounds the run at 8 — far beyond any plausible capability count. */
    private const val MAX_CAP_BYTES = 8

    data class Peel(
        /** Exclusive end of the callId inside `body`. */
        val callIdEnd: Int,
        val oshiCodec: Boolean,
        val pcm16k: Boolean,
        val appleCodec: Boolean,
        /**
         * `null` quand l'octet est ABSENT: le pair est d'avant cette version et
         * n'a rien dit. Ne rien dire n'est pas dire non — les confondre
         * désactiverait la vidéo avec tous les téléphones pas encore à jour.
         * Un octet présent mais à zéro, lui, est une réponse.
         */
        val video: Boolean?,
        /** __WB_ADPCM_CODEC_2026_09_23__ 5th slot: wideband IMA-ADPCM (0x18). */
        val wbAdpcm: Boolean = false,
        /** __OPUS_CODEC_2026_09_23__ 6th slot: Opus (0x19). */
        val opus: Boolean = false
    )

    fun peel(body: ByteArray, callIdStart: Int): Peel {
        var end = body.size
        val peeled = ArrayList<Int>(MAX_CAP_BYTES)
        while (end > callIdStart && peeled.size < MAX_CAP_BYTES &&
            (body[end - 1].toInt() and 0xFF) < 0x2D
        ) {
            peeled.add(body[end - 1].toInt() and 0xFF)
            end -= 1
        }
        peeled.reverse()   // peeled backwards; restore wire (append) order
        fun slot(i: Int): Int = if (i < peeled.size) peeled[i] else 0x00
        return Peel(
            callIdEnd = end,
            oshiCodec = slot(0) == OSHI_CODEC_FLAG,
            pcm16k = slot(1) == RAW_PCM_16K_FLAG,
            appleCodec = slot(2) == APPLE_CODEC_FLAG,
            video = if (peeled.size > 3) (peeled[3] == VIDEO_FLAG) else null,
            wbAdpcm = slot(4) == WB_ADPCM_FLAG,
            opus = slot(5) == OPUS_FLAG
        )
    }
}
