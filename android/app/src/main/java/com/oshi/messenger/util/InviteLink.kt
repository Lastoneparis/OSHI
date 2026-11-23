package com.oshi.messenger.util

import java.net.URLEncoder

/**
 * __FIRST_CONTACT_REMOTE_2026_10_10__ Adding your first contact when the other
 * person is NOT in the room with you.
 *
 * Reported: older users get lost adding their first contact when they are not
 * together. That was not a wording problem — the path did not exist. The empty
 * state offered only "Show my code" and "Scan a friend's code", and both need
 * the other phone physically present.
 *
 * Port of `OSHI/InviteLink.swift`. The URL shape must match it byte for byte:
 * a link made on an iPhone is opened on Android and vice versa, so a
 * difference in escaping here is a dead invite with no error message.
 */
object InviteLink {

    /**
     * __THE_INVITE_LINK_POINTED_AT_THE_UNVERIFIABLE_HOST_2026_11_07__
     * (Anatole N5: "the invite link does not work when it is sent in a
     * conversation".)
     *
     * This was `oshi-messenger.org`, and that host CANNOT be verified by
     * either platform. Measured on 2026-11-07:
     *
     *     https://oshi-messenger.org/.well-known/assetlinks.json
     *         -> HTTP 301 -> https://oshi-messenger.com/.well-known/...
     *     https://oshi-messenger.org/.well-known/apple-app-site-association
     *         -> HTTP 301
     *
     * Android App Links verification does NOT follow redirects, and neither
     * does Apple's CDN when it fetches the association file. A 301 is a
     * verification failure, so the OS never binds `.org` to the app and the
     * link opens a browser instead — which is exactly the report. The `.com`
     * host serves both files directly with HTTP 200 and verifies fine, and it
     * is the host the GROUP invite already uses (`GroupMessaging`), which is
     * why group links worked while contact links did not.
     *
     * Only what we EMIT changes. Both hosts stay in the entitlement and in
     * the Android manifest, and every receiver still ACCEPTS `.org`, because
     * links already sent are in people's chat histories.
     *
     * The complementary fix is server-side: serve those two files on `.org`
     * directly instead of redirecting. Until that happens, old `.org` links
     * keep opening a browser.
     */
    const val HOST = "oshi-messenger.com"

    /**
     * The link that adds *me*.
     *
     * An https App Link, deliberately, NOT the `oshi://` scheme: a custom
     * scheme does nothing at all on a phone that does not have OSHI yet, and
     * for a FIRST contact that is the likely case.
     */
    /**
     * (N1) [myName], when non-blank, is carried as `&name=` — the same shape
     * a group invite already uses — so the receiver's add-contact prompt can
     * default to it instead of the raw key. Optional and additive: a link
     * without it (older app, or no name set) still parses exactly as before.
     */
    fun url(myPublicKey: String, myName: String? = null): String? {
        val trimmed = myPublicKey.trim()
        if (trimmed.isEmpty()) return null
        // A base64 key contains `+`, `/` and `=`. `URLEncoder` turns a space
        // into `+`, and a literal `+` into `%2B`, which is what we need — but
        // it leaves nothing else to chance, so the key is recovered exactly.
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val base = "https://$HOST/add?key=$encoded"
        val name = myName?.trim().orEmpty()
        return if (name.isEmpty()) base else "$base&name=${URLEncoder.encode(name, "UTF-8")}"
    }

    /**
     * What gets sent, as one block of text.
     *
     * Written for someone who has never heard of OSHI, because that is who
     * receives a first invite: who it is from, what to do, and what happens if
     * they do not have the app — in the order the questions arrive in.
     *
     * The three sentences are passed in already-localised so this stays free of
     * a `Context` and testable on the JVM.
     */
    fun message(myPublicKey: String, opener: String, hint: String, myName: String? = null): String? {
        val link = url(myPublicKey, myName) ?: return null
        return "$opener\n\n$link\n\n$hint"
    }
}
