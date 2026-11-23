package com.oshi.messenger.service

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.network.location.LocationMessage

/**
 * What a notification says for a given message body — and whether it may be
 * posted at all.
 *
 * ## Why this is a separate, pure object
 *
 * There were three notification builders on Android
 * (`FCMService.handleDataMessage`, `MessageRepository.showMessageNotification`,
 * `GroupManager.receiveGroupMessage`) and all three carried the same two lines:
 *
 *     val sanitized = MessageSanitizer.sanitizeForDisplay(raw).ifEmpty { "New message" }
 *
 * Both halves are wrong, and they are wrong in opposite directions:
 *
 *  * `MessageSanitizer` is the fourth copy of the control-payload prefix list —
 *    the one `OSHIControlPayload` was written to retire (see its header). It has
 *    **no `💬REPLY💬` branch** and **no `📢GROUP_UPDATE📢` branch**, and because
 *    both start with an emoji rather than a `{`, none of its JSON catch-alls
 *    fires either: it returns them **verbatim**. Replying to someone put
 *    `💬REPLY💬{"content":"…","replyTo":{…}}` on their lock screen. Every label
 *    it does produce is a hardcoded English literal in a 34-language app.
 *
 *  * `.ifEmpty { "New message" }` then **inverts the sanitiser's only correct
 *    answer**. An empty string is how `OSHIControlPayload.isSilent` says *"this
 *    is pure protocol — the user must never learn it happened"*. Turning that
 *    into "New message" is how a read receipt, a typing ping, a delivery
 *    acknowledgement or a key rotation buzzed the far phone with a banner behind
 *    which there was nothing (`parity/verify/2026-08-23-options.md` F-1; Hugo's
 *    "je reçois des notifications pour rien").
 *
 * The sending side of that is being fixed elsewhere (`suppressPush` in the
 * transport). This is the receiving side, and it has to be right independently:
 * an older peer, an iPhone on a build without the fix, or a server-side queue
 * fallback can all still deliver a push for a silent payload, and when one
 * arrives the correct number of banners is zero.
 *
 * ## Reference
 *
 * iOS `PushNotificationManager.swift:104-111` (1:1) and `:186-192` (group):
 *
 *     let body = OSHIControlPayload.previewText(for: message)
 *     content.body = body.isEmpty ? "sent_attachment".localized() : body
 *
 * — i.e. the catalog, then a **localized** fallback. iOS can use
 * `sent_attachment` unconditionally because `MessageManager` never asks for a
 * notification for a silent payload in the first place; Android's push arrives
 * from the network, so [decide] has to make that distinction itself.
 *
 * Pure and Context-free on purpose: the labels arrive as data, so the whole
 * decision table is exercisable in a JVM unit test.
 */
object NotificationPresentation {

    /**
     * Whether a banner may be posted for [raw], and what it should say.
     *
     * [Suppress] is not "show something generic" — it is *post nothing*: no
     * banner, no sound, no badge, no entry in the shade.
     */
    sealed interface Decision {
        /** [raw] is pure protocol. The user must not learn it arrived. */
        object Suppress : Decision

        /** Post a banner whose body is exactly [body]. */
        data class Show(val body: String) : Decision
    }

    /**
     * @param raw            the message body **exactly as it came off the wire**.
     *                       Never a string some other sanitiser already rewrote —
     *                       detection on a sanitised string is how a sanitiser
     *                       gets silently negated (it has already happened in
     *                       this codebase: `ChatScreen`'s AI filter, and the
     *                       `📍️LOCATION📍️` prefix with a stray U+FE0F that could
     *                       never match).
     * @param labels         localized catalog labels; `OSHIControlPayload.labels(context)`.
     * @param attachmentBody the localized "Sent an attachment" fallback, used
     *                       when a message is real but yields no text.
     */
    fun decide(
        raw: String,
        labels: OSHIControlPayload.Labels = OSHIControlPayload.Labels(),
        attachmentBody: String = "Sent an attachment"
    ): Decision {
        // Blank body: nothing was said, but something arrived. iOS shows the
        // attachment fallback rather than suppressing, because a real photo with
        // no caption reaches this path.
        if (raw.isBlank()) return Decision.Show(attachmentBody)

        // The ONE question, asked on the RAW string.
        if (OSHIControlPayload.isSilent(raw)) return Decision.Suppress

        // 📍 …and the one payload the catalog cannot answer for.
        //
        // `📍LOCATION📍` is a RENDERED entry, correctly: the initial share, an
        // extension and the stop signal are all things the user wants to be told
        // about. But a live share also emits a position ping roughly every ten
        // seconds under the SAME sentinel, told apart only by `isUpdate` inside the
        // JSON — so as far as the prefix catalog is concerned, a fifteen-minute live
        // share is ninety "📍 Location shared" banners.
        //
        // The sending side suppresses those (`MessageRepository.isSilentControlContent`
        // → `suppressPush`), but this object exists precisely because that is not
        // enough: a peer on an older build, an iPhone whose push went through a
        // server-side queue fallback, or any relay that pushes on its own can still
        // deliver one, and when one arrives the correct number of banners is zero.
        // Same single source of truth as the send side, asked on the RAW string.
        if (LocationMessage.isSilentPushContent(raw)) return Decision.Suppress

        val body = OSHIControlPayload.listPreview(raw, labels)

        // `listPreview` returns "" only for a payload the catalog calls silent,
        // which `isSilent` already caught — but an ENVELOPE around an
        // undecodable body also lands here (`displaySummary` returns "" when
        // `envelopeBody` cannot be read). Suppressing would lose a real message,
        // so fall back rather than drop.
        if (body.isBlank()) return Decision.Show(attachmentBody)

        return Decision.Show(body)
    }

    /** Longest body a banner carries; the rest is elided. Matches the two existing call sites. */
    const val MAX_BODY_CHARS = 50

    /** [decide], with the length cap the notification builders all applied. */
    fun decideCapped(
        raw: String,
        labels: OSHIControlPayload.Labels = OSHIControlPayload.Labels(),
        attachmentBody: String = "Sent an attachment"
    ): Decision = when (val d = decide(raw, labels, attachmentBody)) {
        is Decision.Suppress -> d
        is Decision.Show ->
            if (d.body.length > MAX_BODY_CHARS) Decision.Show(d.body.take(MAX_BODY_CHARS) + "…")
            else d
    }
}
