package com.oshi.messenger.util

/**
 * The name an attachment travels under, and the name it is written to disk under.
 *
 * ## Why this exists
 *
 * Two separate defects, one root:
 *
 * **1. The real name never reached the wire.** `MessageRepository.sendMediaMessage`
 * built the outbound filename as `"$messageId.$ext"` unconditionally, so a document
 * picked as `Contrat-2026.pdf` was announced to the peer as
 * `9f3c1b2a-….dat`. iOS reads that field straight onto the bubble —
 * `MessageManager+V2.swift:1017` (`msg.mediaFileName = filename`), rendered by
 * `MediaMessageView`; iOS's own sender puts the true name there
 * (`MessageManager+V2.swift:1071-1073`, `v2FileName(for:)` returns
 * `message.mediaFileName` when set). Android's RECEIVE side already honours the
 * field (`MessageRepository.kt`, `content = if (mediaType == DOCUMENT) inbound.filename`),
 * so Android→Android was broken by the same omission: the receiving phone showed a
 * UUID where the sender had picked a name.
 *
 * **2. The name is peer-controlled and reaches a `File` constructor.**
 * `ChatScreen.saveDocumentToDownloads` on API < 29 does
 * `java.io.File(oshiDir, filename)`. A peer-supplied `../../…` would escape the
 * `Downloads/OSHI` directory. [sanitize] is therefore applied on BOTH ends — the
 * sender never emits a traversal, and the receiver never trusts one.
 *
 * ## Rules
 *
 * Everything here is a pure function of its arguments so it is unit-testable on the
 * JVM with hand-written literals.
 */
object AttachmentFileName {

    /**
     * Upper bound on a stored name. Well under the 255-byte limit every common
     * filesystem imposes, with room for the extension that is re-appended.
     */
    private const val MAX_LENGTH = 120

    /**
     * Characters no filesystem this app writes to accepts, plus the separators that
     * make traversal possible. The whole C0 range goes too: NUL terminates a C
     * string and would truncate the name inside the platform layer, and a newline
     * would break the single-line label. Ordinary SPACES are legal and kept —
     * `Rapport final.pdf` is a name people really pick.
     */
    private fun isForbidden(c: Char): Boolean =
        c == '/' || c == '\\' || c < ' ' || c == ':' || c == '*' ||
            c == '?' || c == '"' || c == '<' || c == '>' || c == '|'

    /**
     * A filesystem-safe, traversal-free rendering of [raw], or `null` when nothing
     * usable survives.
     *
     * `null` — not `""` and not a made-up name — because the two callers want
     * different fallbacks: the sender falls back to `<messageId>.<ext>`, the saver
     * falls back to the app's generic document name. A function that invented one
     * here would force the wrong one on somebody.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        // Take the last path component FIRST: "../../etc/passwd" → "passwd", and a
        // legitimate "Documents/Contrat.pdf" → "Contrat.pdf". Stripping the
        // separators instead would turn the first into "......etcpasswd".
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString(leaf.length) {
            for (c in leaf) if (!isForbidden(c)) append(c)
        }.trim().trim('.')          // a name that is only dots is "." or ".." — gone
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= MAX_LENGTH) return cleaned
        // Too long: keep the head and the extension, because the extension is what
        // picks the icon (`getFileTypeIcon`) and the MIME type (`getMimeType`).
        val ext = cleaned.substringAfterLast('.', "")
        return if (ext.isEmpty() || ext.length > 12) {
            cleaned.take(MAX_LENGTH)
        } else {
            cleaned.take(MAX_LENGTH - ext.length - 1) + "." + ext
        }
    }

    /**
     * The name to put in the `filename` field of the outbound file-key message.
     *
     * [requested] wins when the caller knows the real one; otherwise a synthetic
     * `<messageId>.<ext>`, which is what every path did before and remains correct
     * for camera photos and voice notes that never had a user-visible name.
     *
     * [defaultExtension] is passed in rather than derived, so this object carries no
     * dependency on the media enum and therefore none on Android.
     */
    fun forWire(messageId: String, defaultExtension: String, requested: String?): String =
        sanitize(requested) ?: "$messageId.$defaultExtension"
}
