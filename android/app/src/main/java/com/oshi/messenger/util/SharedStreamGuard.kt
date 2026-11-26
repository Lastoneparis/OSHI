package com.oshi.messenger.util

import java.io.File

/**
 * __SHARE_TARGET_OWN_FILES_2026_11_20__ Which inbound share streams OSHI may read.
 *
 * MainActivity is the system share target (exported ACTION_SEND / SEND_MULTIPLE). The
 * stream URIs in the intent come from WHOEVER sent it, and OSHI then reads them with
 * its OWN permissions (`contentResolver.openInputStream`). Without a check, any app on
 * the phone could hand OSHI
 *   • `file:///data/user/0/com.oshi.messenger/shared_prefs/…` or `…/files/…` — OSHI's
 *     private sandbox, which only OSHI can read, or
 *   • `content://com.oshi.messenger.fileprovider/files_root/…` — OSHI's own
 *     (non-exported) FileProvider, which exposes the whole files dir to OSHI itself,
 * and OSHI would read the file and send it as an attachment to whichever contact the
 * person taps in the picker ("a file to share" — the person cannot see what it is).
 * That is the classic share-target confused deputy: the other app gets no read access
 * itself, but it makes OSHI exfiltrate its own keys/state over an E2E channel.
 *
 * Rule: a stream is refused when it points INTO OSHI — a `file:` path whose canonical
 * form is under one of OSHI's private roots, or a `content:` URI served by one of OSHI's
 * own authorities. Everything another app legitimately shares (its own content URIs,
 * MediaStore, a file on shared storage) is unaffected.
 *
 * Pure (no android.*) so it is unit-testable on the JVM.
 */
object SharedStreamGuard {

    /**
     * @param scheme URI scheme (`file`, `content`, …), any case
     * @param authority URI authority for `content:` URIs
     * @param path decoded URI path for `file:` URIs
     * @param ownAuthorities authorities of OSHI's own providers
     * @param privateRoots OSHI's private directories (dataDir, filesDir, cacheDir,
     *   external files/cache dirs) — compared canonically, so `..` and symlinks in
     *   [path] cannot step around them
     */
    fun isAllowed(
        scheme: String?,
        authority: String?,
        path: String?,
        ownAuthorities: Collection<String>,
        privateRoots: Collection<File>,
    ): Boolean {
        return when (scheme?.lowercase()) {
            "content" -> {
                val auth = authority?.lowercase() ?: return false
                ownAuthorities.none { it.lowercase() == auth }
            }
            "file" -> {
                val p = path ?: return false
                // /proc/self/fd/N and /proc/self/root/… are views of OSHI's OWN open
                // files and filesystem; nothing legitimate is shared from /proc.
                if (p.startsWith("/proc/") || p == "/proc") return false
                val target = canonical(File(p)) ?: return false
                if (target.startsWith("/proc/")) return false
                privateRoots.mapNotNull { canonical(it) }.none { root -> isUnder(target, root) }
            }
            // http(s), data:, android.resource:, … are never valid EXTRA_STREAMs.
            else -> false
        }
    }

    private fun canonical(f: File): String? = runCatching { f.canonicalPath }.getOrNull()

    private fun isUnder(target: String, root: String): Boolean {
        val r = root.trimEnd(File.separatorChar)
        return target == r || target.startsWith(r + File.separatorChar)
    }
}
