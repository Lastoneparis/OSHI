package com.oshi.messenger.mail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unread-inbox count for the private mailbox — process-wide.
 *
 * ## Why this exists
 *
 * The mailbox is server-backed (no local Room table), so the only place that knows
 * the true unread count is [MailViewModel] after it has loaded the inbox — and that
 * only happens while the Mail screen is on screen. The Email entry point, however,
 * lives in the Conversations top bar (a different composition that never mounts the
 * mail ViewModel), and a mail push can arrive with the app in the background.
 *
 * This holder is the Android equivalent of iOS's `UIApplication.applicationIconBadgeNumber`
 * for mail (`PushNotificationManager.swift` mail badge): one number, updated from
 * three places —
 *  - [set] by [MailViewModel.loadInbox], the authoritative count of unseen inbox mail;
 *  - [increment] by `FCMService` when a `type == "mail"` push lands before the inbox
 *    has been (re)loaded — optimistic, reconciled by the next [set];
 *  - [clear] on sign-out.
 *
 * Nothing here is Android-typed, so it is directly unit-testable and safe to read
 * from any thread.
 */
object MailUnread {

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** Authoritative: the mailbox reported exactly this many unseen inbox messages. */
    fun set(value: Int) { _count.value = value.coerceAtLeast(0) }

    /** Optimistic bump when a mail push arrives ahead of an inbox load. */
    fun increment() { _count.value = _count.value + 1 }

    fun clear() { _count.value = 0 }
}
