package com.oshi.messenger.data.model

/**
 * __OSHI_MASCOT_STICKERS_2026_11_21__ The "OSHI" sticker tab: the 20 O-bubble mascot
 * animations ([OshiMascot.ALL]) as stickers anyone can send.
 *
 * ============================================================ THE WIRE DOES NOT CHANGE
 *
 * A mascot sticker is the bundled GIF (`assets/OshiMascot/gif/<name>.gif`, 240 px,
 * GIF89a, transparent, infinite loop) handed UNTOUCHED to the ordinary GIF send path
 * (`ChatViewModel.sendGifMessage` / `GroupChatViewModel.sendGifMessage`). There is no
 * sticker id, no new message type: every receiver already handles it.
 *  - 1:1 V2: `v2file`, mediaType `gif`, mime `image/gif`, name `oshi_<name>.gif`.
 *    iOS 1.0.43 has no `gif` media type, falls back to the mime (`image/` → .image,
 *    `MessageManager+V2.swift` `v2MediaType(forMime:)`) and draws `UIImage(data:)` —
 *    the FIRST FRAME as a still picture. iOS 1.0.46+ animates it
 *    (`MediaManager.isGIFData`), Android through Coil's GIF decoder, desktop through
 *    `GifBubble`.
 *  - groups: `photo` + a `.gif` name (see `GroupGifWireTest`).
 *
 * Because iOS 1.0.43 shows only frame 0, the shipped GIFs START on the manifest's
 * `still_frame` (the expressive pose: ✅ on "ok", 💯 on "hundred", the glasses on
 * "cool"…) — the same loop, rotated losslessly (gifsicle frame selection; every
 * composited frame verified identical, `wow` within 12/255 per channel after a 255-colour
 * pre-pass). `OshiStickerPackTest` pins the GIF magic, the loop, the file name and that
 * frame 0 matches `static/<name>_128.png` (the still pose).
 *
 * Pure Kotlin, no Android types: the picker passes an asset reader.
 */
enum class StickerPickerTab { OSHI, GIFS, STICKERS }

/** What the picker hands to `sendGifMessage`. */
class OshiSticker(val mascot: Mascot, val bytes: ByteArray, val fileName: String)

object OshiStickerPack {

    /** Tab order of the GIF & sticker picker: OSHI first. */
    val TABS: List<StickerPickerTab> = listOf(StickerPickerTab.OSHI, StickerPickerTab.GIFS, StickerPickerTab.STICKERS)

    /** The tab the picker opens on. */
    val DEFAULT_TAB: StickerPickerTab = StickerPickerTab.OSHI

    /** Only the GIFs and Stickers tabs talk to GIPHY (its key is capped per hour, app-wide). */
    fun usesGiphy(tab: StickerPickerTab): Boolean = tab != StickerPickerTab.OSHI

    /** The stickers shown for [query]: all 20 in [OshiMascot.ALL] order when blank. */
    fun stickers(query: String = ""): List<Mascot> = OshiMascot.search(query)

    /**
     * The asset a cell DRAWS: the animated GIF that will be sent, or the still frame
     * when the system asked for no animation ("Remove animations" / animator duration
     * scale off).
     */
    fun previewAsset(mascot: Mascot, reduceMotion: Boolean): String =
        if (reduceMotion) mascot.staticAsset else mascot.gifAsset

    /**
     * Loads [mascot]'s GIF through [readAsset] and returns what to send, or null when the
     * file is missing or is not a GIF (a WebP or PNG would arrive frozen, or not at all,
     * on older iPhones — never send one in its place).
     */
    fun sticker(mascot: Mascot, readAsset: (String) -> ByteArray?): OshiSticker? {
        val bytes = runCatching { readAsset(mascot.gifAsset) }.getOrNull() ?: return null
        if (!isGif89a(bytes)) return null
        return OshiSticker(mascot, bytes, mascot.gifFileName)
    }

    fun isGif89a(bytes: ByteArray): Boolean =
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a"
}
