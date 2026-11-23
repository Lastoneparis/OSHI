package com.oshi.messenger.service

import com.oshi.messenger.data.repository.MessageRepository

/**
 * Which conversation the user is **looking at right now** — process-wide.
 *
 * ## Why this exists
 *
 * Android only ever marked a thread read on ENTRY:
 * `ChatScreen.kt:719` → `ChatViewModel.loadConversation` → `markConversationAsRead`.
 * A message that landed while the chat was already open stayed unread until the
 * user backed out and came back in. iOS has a second trigger — the arrival itself,
 * gated on visibility:
 *
 *  - 1:1  — `OSHI/ChatView.swift:826-831` sets `isVisible` on `.onAppear` /
 *           `.onDisappear`, and `:863-866` runs
 *           `onChange(of: messages.count) { if isVisible { markMessagesAsRead() } }`.
 *  - group — `OSHI/GroupMessaging.swift:461` holds `activeGroupId`, set on the
 *           group view's appear/disappear (`GroupViews.swift:4717,4870`), and the
 *           inbound path stamps `incomingMessage.isRead = (activeGroupId == groupId)`
 *           (`GroupMessaging.swift:3330-3339`) — i.e. read **at insert time** when
 *           that group is the one on screen.
 *
 * This object is the Android equivalent of `isVisible` + `activeGroupId`, hoisted
 * out of the composition because three call sites outside the UI need the answer:
 * `MessageRepository.showMessageNotification`, `FCMService`'s `message` and
 * `group_message` branches, and the mark-read watcher in the chat ViewModels.
 *
 * ## What "visible" means here, exactly
 *
 * Only the two chat screens write to it, and only from a `LifecycleEventObserver`:
 * `enter*` on `ON_RESUME`, `leave*` on `ON_PAUSE` and on dispose. So a key is
 * present **iff the process is foregrounded AND that specific thread is the one on
 * screen** — a backgrounded app pauses its screen, and navigating to another
 * conversation pauses the old one. That precision is the whole safety story: a
 * false "visible" would tell the sender ✓✓ Read for a message nobody ever saw,
 * which is worse than the bug being fixed.
 *
 * Consequently:
 *  - a blank key is NEVER active (an unset `publicKey` must not read as "the user
 *    is looking at everything");
 *  - [leaveDirect] / [leaveGroup] take the key and clear only if it still matches,
 *    so a hand-off that resumes screen B before pausing screen A cannot have A's
 *    late `ON_PAUSE` erase B's claim;
 *  - entering a direct thread clears any active group and vice versa — exactly one
 *    conversation can be on screen.
 *
 * Keys are compared through [MessageRepository.normalizeBase64Key] (base64url →
 * base64, padding dropped) because the spelling that reaches an FCM payload is not
 * always the spelling the screen was navigated with. This is a comparison-only
 * normalisation; nothing here is ever stored.
 *
 * Nothing in this file is Android-typed, so it is directly unit-testable
 * (`ActiveConversationTest`).
 */
object ActiveConversation {

    @Volatile
    private var directKey: String? = null

    @Volatile
    private var groupKey: String? = null

    /** The normalised key of the 1:1 thread on screen, or null. Diagnostics only. */
    val activeDirectKey: String? get() = directKey

    /** The id of the group thread on screen, or null. Diagnostics only. */
    val activeGroupId: String? get() = groupKey

    /** The 1:1 thread with [publicKey] is now on screen and foregrounded. */
    fun enterDirect(publicKey: String) {
        if (publicKey.isBlank()) return
        val norm = MessageRepository.normalizeBase64Key(publicKey)
        if (norm.isEmpty()) return
        synchronized(this) {
            directKey = norm
            groupKey = null
        }
    }

    /**
     * The 1:1 thread with [publicKey] left the screen (paused, disposed, or the app
     * was backgrounded). No-op if some OTHER thread has since claimed the screen.
     */
    fun leaveDirect(publicKey: String) {
        if (publicKey.isBlank()) return
        val norm = MessageRepository.normalizeBase64Key(publicKey)
        synchronized(this) {
            if (directKey == norm) directKey = null
        }
    }

    /** The group thread [groupId] is now on screen and foregrounded. */
    fun enterGroup(groupId: String) {
        if (groupId.isBlank()) return
        synchronized(this) {
            groupKey = groupId.trim()
            directKey = null
        }
    }

    /** The group thread [groupId] left the screen. No-op if another thread claimed it. */
    fun leaveGroup(groupId: String) {
        if (groupId.isBlank()) return
        val trimmed = groupId.trim()
        synchronized(this) {
            if (groupKey == trimmed) groupKey = null
        }
    }

    /**
     * True iff the user is looking at the 1:1 thread with [publicKey] right now.
     *
     * This is the predicate that decides whether an arriving message is marked read
     * and whether it raises a banner. It must answer false for a blank key and for
     * every key other than the one on screen.
     */
    fun isDirectActive(publicKey: String): Boolean {
        if (publicKey.isBlank()) return false
        val current = directKey ?: return false
        return current == MessageRepository.normalizeBase64Key(publicKey)
    }

    /** True iff the user is looking at group [groupId] right now. */
    fun isGroupActive(groupId: String): Boolean {
        if (groupId.isBlank()) return false
        val current = groupKey ?: return false
        return current == groupId.trim()
    }

    /** Nothing is on screen (logout, identity reset, tests). */
    fun clear() {
        synchronized(this) {
            directKey = null
            groupKey = null
        }
    }
}
