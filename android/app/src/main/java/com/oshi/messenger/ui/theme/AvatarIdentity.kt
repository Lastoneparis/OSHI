package com.oshi.messenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AvatarIdentity — the colour a contact or group gets when it has no picture.
 *
 * __AVATAR_PALETTE_V2_2026_11_17__ (owner: "more agreeable and diversified
 * colours or texture"). Version 1 was fourteen flat, fairly saturated hues all
 * darkened for WHITE initials, which left the yellow-green band as olive and
 * ochre and made a long list read as a row of heavy, similar discs.
 *
 * Version 2: sixteen two-tone soft gradients — nine mid tones carrying white
 * initials, seven pastels carrying a deep ink of their own hue — plus a soft
 * radial light blob baked into the fill ([avatarFill]). No image asset, no
 * per-frame work: the brushes are built once per size in `drawWithCache`.
 *
 * ONE SPEC, THREE PLATFORMS. The source of truth is `docs/avatar-palette.json`
 * (hex values, gradient direction, highlight geometry, selection rule and the
 * identifier -> slot vectors). iOS (`OSHI/AvatarIdentity.swift`) and desktop
 * (`OshiTheme.kt`) carry the same literals and every suite re-reads that file —
 * `AvatarIdentityTest` here — so a contact cannot change colour with their
 * phone. Never "improve" one platform alone.
 *
 * SELECTION: trim; a UUID (a group id) is lowercased — iOS spells
 * `uuidString` UPPERCASE — anything else (a contact's canonical base64 public
 * key) is used verbatim; SHA-256 over the UTF-8 bytes; slot = first digest
 * byte mod 16. Cached per identity, so a scrolling list hashes each key once.
 */
object AvatarIdentity {

    /** One palette slot: the diagonal gradient's two stops and the initials' ink. */
    data class Entry(val start: Color, val end: Color, val ink: Color)

    private fun rgb(hex: Long): Color = Color(0xFF000000L or hex)

    /** The palette, in spec order. Do not reorder: the index IS the identity. */
    val entries: List<Entry> = listOf(
        Entry(rgb(0xCB6C30), rgb(0xB63924), rgb(0xFFFFFF)),  //  0 coral
        Entry(rgb(0xFDD7AC), rgb(0xF6A97A), rgb(0x743B1F)),  //  1 apricot
        Entry(rgb(0xF8E9A3), rgb(0xF1BF5B), rgb(0x6B4716)),  //  2 honey
        Entry(rgb(0xD8EFB4), rgb(0x9AD387), rgb(0x31552A)),  //  3 pistachio
        Entry(rgb(0x43963C), rgb(0x027048), rgb(0xFFFFFF)),  //  4 fern
        Entry(rgb(0xC2F5E0), rgb(0x7ED8C6), rgb(0x07564F)),  //  5 mint
        Entry(rgb(0x179696), rgb(0x066674), rgb(0xFFFFFF)),  //  6 teal
        Entry(rgb(0xC2ECFE), rgb(0x87C6F2), rgb(0x264B7A)),  //  7 sky
        Entry(rgb(0x3E8CC9), rgb(0x2953A5), rgb(0xFFFFFF)),  //  8 ocean
        Entry(rgb(0x7B7FC9), rgb(0x5E45AE), rgb(0xFFFFFF)),  //  9 periwinkle
        Entry(rgb(0xE3DCFF), rgb(0xC9AFF4), rgb(0x553781)),  // 10 lavender
        Entry(rgb(0xB76BC3), rgb(0x7D32A3), rgb(0xFFFFFF)),  // 11 grape
        Entry(rgb(0xC96598), rgb(0x9E3082), rgb(0xFFFFFF)),  // 12 orchid
        Entry(rgb(0xFEDAE1), rgb(0xF0A9C4), rgb(0x7E314D)),  // 13 blush
        Entry(rgb(0xDB6371), rgb(0xA92552), rgb(0xFFFFFF)),  // 14 raspberry
        Entry(rgb(0x768997), rgb(0x47536A), rgb(0xFFFFFF)),  // 15 slate
    )

    /** `start to end` per entry — the shape older call sites use. */
    val gradients: List<Pair<Color, Color>> = entries.map { it.start to it.end }

    /** Highlight geometry, fractions of the diameter (spec `highlight`). */
    const val HIGHLIGHT_X = 0.30f
    const val HIGHLIGHT_Y = 0.20f
    const val HIGHLIGHT_RADIUS = 0.80f
    const val HIGHLIGHT_ALPHA = 0.24f

    private val UUID_RE = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /** Spec `selection.normalise`: a UUID is lowercased, anything else verbatim. */
    fun normalizedKey(key: String): String {
        val trimmed = key.trim()
        return if (UUID_RE.matches(trimmed)) trimmed.lowercase(java.util.Locale.ROOT) else key
    }

    private val digestCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** First byte of SHA-256(UTF-8(normalised key)), 0..255 — spec `selection`. */
    fun digestByte(key: String): Int {
        val normalized = normalizedKey(key)
        digestCache[normalized]?.let { return it }
        val byte = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))[0].toInt() and 0xff
        if (digestCache.size > 4096) digestCache.clear()
        digestCache[normalized] = byte
        return byte
    }

    /** A stable slot for [key], identical across launches, devices and platforms. */
    fun stableIndex(key: String, modulo: Int): Int =
        if (modulo <= 0) 0 else digestByte(key) % modulo

    fun slot(key: String): Int = stableIndex(key, entries.size)

    fun entry(key: String): Entry = entries[slot(key)]

    /** The gradient for [key] — a public key, a group id, whatever has no picture. */
    fun gradient(key: String): Pair<Color, Color> = gradients[slot(key)]

    // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__
    //
    // The ONE spelling of the key each kind of avatar is coloured by:
    //  - a CONTACT: its canonical public key (standard, padded base64 — the
    //    storage spelling, `MessageRepository.canonicalIdentity`), so keys that
    //    arrive from the wire in base64url or unpadded land on the same slot;
    //  - a GROUP: its id, lowercased (v2 spec; v1 uppercased to match iOS
    //    `uuidString` — [normalizedKey] now folds both spellings anyway).
    // NOT canonicalIdentity for groups: a UUID contains '-', which that function
    // folds to '+', and "8 hex + 4 hex + ..." is valid base64 — it would rewrite.

    /** The key a contact's avatar colour is derived from. */
    fun contactKey(publicKey: String): String =
        com.oshi.messenger.data.repository.MessageRepository.canonicalIdentity(publicKey)

    /** The key a group's avatar colour is derived from (same slot as iOS `uuidString`). */
    fun groupKey(groupId: String): String = groupId.trim().lowercase(java.util.Locale.ROOT)

    fun contactEntry(publicKey: String): Entry = entry(contactKey(publicKey))
    fun groupEntry(groupId: String): Entry = entry(groupKey(groupId))

    /** The gradient of a picture-less contact. Use this, not [gradient], for people. */
    fun contactGradient(publicKey: String): Pair<Color, Color> = gradient(contactKey(publicKey))

    /** The gradient of a picture-less group. Use this, not [gradient], for groups. */
    fun groupGradient(groupId: String): Pair<Color, Color> = gradient(groupKey(groupId))
}

/**
 * __AVATAR_PALETTE_V2_2026_11_17__ The picture-less disc: the entry's diagonal
 * gradient (top-left to bottom-right) and the soft top-left light blob, both
 * from the spec. `drawWithCache` builds the two brushes once per size, so a
 * scrolling list does no per-frame allocation.
 */
fun Modifier.avatarFill(entry: AvatarIdentity.Entry): Modifier = this.drawWithCache {
    val d = size.minDimension
    val linear = Brush.linearGradient(
        listOf(entry.start, entry.end),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val light = Brush.radialGradient(
        listOf(Color.White.copy(alpha = AvatarIdentity.HIGHLIGHT_ALPHA), Color.White.copy(alpha = 0f)),
        center = Offset(size.width * AvatarIdentity.HIGHLIGHT_X, size.height * AvatarIdentity.HIGHLIGHT_Y),
        radius = d * AvatarIdentity.HIGHLIGHT_RADIUS,
    )
    onDrawBehind {
        drawCircle(linear)
        drawCircle(light)
    }
}

/**
 * __A_NAME_COLOUR_PER_PERSON_2026_11_07__ (Anatole N11)
 *
 * Every sender name in a group was drawn in one brand colour — no help at all
 * when you are scanning a busy room for who said what.
 *
 * NOT the avatar palette: those gradients are solved so WHITE sits on them at
 * 5.2:1, i.e. they are backgrounds. As TEXT the measurement is against the
 * bubble instead, and one colour cannot answer that for both a light and a dark
 * bubble. So: the same HUES as the avatar palette — a person's name is the same colour
 * family as their avatar, and the two read as one identity — with the LIGHTNESS
 * solved per hue, per appearance, for 4.75:1 against the bubble it sits on.
 *
 * The margin over AA's 4.5 exists because these literals are rounded to three
 * decimals and the ratio is measured on the rounded value. Values are the
 * spec's `nameLight` / `nameDark` (`docs/avatar-palette.json`), re-read and
 * re-measured by `AvatarIdentityTest`, so a group reads alike on every platform.
 */
object AvatarNameColors {

    val light: List<Color> = listOf(
        0xBE4106, 0xA15905, 0x846706, 0x4B7722, 0x007C37, 0x087965, 0x07777E, 0x05739F,
        0x156BC6, 0x6B5ACB, 0x7A5CAD, 0x984AB2, 0xB23E89, 0xA84D70, 0xBF395B, 0x576D88,
    ).map { Color(0xFF000000L or it.toLong()) }

    val dark: List<Color> = listOf(
        0xE87750, 0xCB884F, 0xB5932E, 0x79A35B, 0x43AB60, 0x32A98F, 0x13A7B0, 0x45A0CF,
        0x5699EC, 0x948AF1, 0xA48AD4, 0xC27CD9, 0xDC74B3, 0xD27E9B, 0xE97188, 0x8499B1,
    ).map { Color(0xFF000000L or it.toLong()) }

    /** The slot for [key] — the SAME slot as the avatar, so the two never disagree. */
    fun slot(key: String): Int = AvatarIdentity.stableIndex(key, light.size)

    fun forKey(key: String, isDark: Boolean): Color =
        (if (isDark) dark else light)[slot(key)]
}

/**
 * __A_NAME_COLOUR_PER_PERSON_2026_11_07__ The name colour for [key], following
 * the current theme.
 */
@Composable
fun rememberNameColor(key: String): Color {
    // __BUBBLE_MEDIA_2026_11_21__ the APP's scheme, not the phone's: with the theme
    // forced to Light on a dark phone the dark-tuned names landed on white bubbles.
    val dark = isDarkScheme()
    return remember(key, dark) { AvatarNameColors.forKey(key, dark) }
}

/**
 * __ONE_AVATAR_NOT_THREE_2026_11_07__ The letters to draw on a default avatar.
 *
 * Pure, so the rule is testable without composing anything, and identical to
 * `AvatarIdentity.initials(name:key:count:)` in `OSHI/AvatarIdentity.swift`.
 */
fun avatarInitials(name: String?, key: String, count: Int = 2): String {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isNotEmpty()) {
        val parts = trimmed.split(" ").filter { it.isNotBlank() }
        if (count >= 2 && parts.size >= 2) {
            return (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
        return trimmed.take(count).uppercase()
    }
    return key.take(count).uppercase()
}

/**
 * __ONE_AVATAR_NOT_THREE_2026_11_07__ The default avatar, everywhere.
 *
 * THE BUG THIS CLOSES, reported twice on the same day: Anatole's N12 ("the
 * profile-photo colour works on the home page but not in the conversations")
 * and the owner's ("someone with no picture shows up BLUE in the
 * conversation"). Both are the same thing, and it was true on iOS and Android
 * alike.
 *
 * [AvatarIdentity] — the contrast-solved palette with cross-platform vectors
 * in `AvatarIdentityTest` — had exactly ONE
 * caller: the conversation list row. Every other avatar had grown its own, most
 * often a flat `MaterialTheme.colorScheme.primaryContainer`, which is one
 * colour for every person in the app. So a contact was teal in the list and the
 * same theme blue as everybody else the moment you opened the conversation.
 *
 * Takes the IDENTITY ([key]) separately from the TEXT ([name]) on purpose: the
 * colour must follow the key, so renaming someone does not recolour them, while
 * the letters follow the name.
 */
@Composable
fun DefaultAvatar(
    key: String,
    name: String? = null,
    size: Dp = 44.dp,
    initialCount: Int = 2,
    modifier: Modifier = Modifier,
) {
    val entry = remember(key) { AvatarIdentity.entry(key) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .avatarFill(entry),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitials(name, key, initialCount),
            // Scaled to the circle so one composable serves a 24.dp header badge
            // and a 96.dp profile hero without a second set of numbers to keep
            // in step. The slot's own ink: white on mid tones, deep on pastels.
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold,
            color = entry.ink,
            maxLines = 1,
        )
    }
}

/**
 * __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ The picture-less avatar of a
 * PERSON: [DefaultAvatar] keyed on [AvatarIdentity.contactKey], i.e. exactly
 * the colour the conversation list gives the same public key.
 */
@Composable
fun ContactAvatar(
    publicKey: String,
    name: String? = null,
    size: Dp = 44.dp,
    initialCount: Int = 2,
    modifier: Modifier = Modifier,
) {
    val key = remember(publicKey) { AvatarIdentity.contactKey(publicKey) }
    DefaultAvatar(key = key, name = name, size = size, initialCount = initialCount, modifier = modifier)
}

/**
 * __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ The picture-less avatar of a
 * GROUP: keyed on [AvatarIdentity.groupKey], the same colour in the group list,
 * the group info sheet and the header inside the group.
 */
@Composable
fun GroupAvatar(
    groupId: String,
    name: String? = null,
    size: Dp = 44.dp,
    initialCount: Int = 2,
    modifier: Modifier = Modifier,
) {
    val key = remember(groupId) { AvatarIdentity.groupKey(groupId) }
    DefaultAvatar(key = key, name = name, size = size, initialCount = initialCount, modifier = modifier)
}
