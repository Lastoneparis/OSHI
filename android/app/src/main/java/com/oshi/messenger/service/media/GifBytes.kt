package com.oshi.messenger.service.media

/**
 * __GIF_WIRE_TYPE_2026_09_13__ Le seul test de GIF fiable de l'app, côté Android.
 *
 * Port de `MediaManager.isGIFData` (iOS, `MediaManager.swift:177`). Le `mediaType`
 * d'un message peut être nul (étiquette inconnue venue d'un pair plus récent), le
 * nom de fichier peut être absent ou mentir — les six premiers octets d'un GIF,
 * eux, sont toujours `GIF87a` ou `GIF89a`.
 *
 * C'est ce qui permet d'envoyer un GIF SANS ajouter de valeur à l'enum
 * [com.oshi.messenger.data.model.MediaType] (ce qui coûterait une migration Room)
 * et SANS nouveau paramètre sur le chemin d'envoi: le dépôt renifle les octets et
 * en déduit qu'il ne faut ni recompresser ni réétiqueter. Un GIF recompressé en
 * JPEG est un GIF mort — il n'a plus qu'une image.
 */
object GifBytes {

    /** Vrai quand les octets SONT un GIF, quoi que prétende le message. */
    fun isGif(bytes: ByteArray?): Boolean {
        val b = bytes ?: return false
        if (b.size < 6) return false
        fun at(i: Int): Int = b[i].toInt() and 0xFF
        // "GIF8" puis "7a" ou "9a".
        if (at(0) != 0x47 || at(1) != 0x49 || at(2) != 0x46 || at(3) != 0x38) return false
        return (at(4) == 0x37 || at(4) == 0x39) && at(5) == 0x61
    }
}
