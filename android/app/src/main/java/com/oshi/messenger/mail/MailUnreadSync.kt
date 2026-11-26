package com.oshi.messenger.mail

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __MAIL_UNREAD_REFRESH_2026_11_22__ Keeps [MailUnread] true without the Mail screen.
 *
 * Reported on iOS (owner, 22/11): after a few hours in flight mode, new mail showed
 * neither a badge nor a notification until the Email view was opened. Android had the
 * same gap: [MailUnread] was SET only by `MailViewModel.loadInbox` (Mail screen on
 * screen) and otherwise only bumped by a mail push. Offline, FCM collapses or drops
 * those pushes, so mail that arrived during flight mode never reached the badge.
 *
 * Now the count is asked for on every event that can change it: app start, process
 * foreground, network available, every 60 s while in the foreground (doubling on
 * failure up to 15 min), and after any mail push. Each check asks
 * `GET /mail/v1/inbox/status` (counts only, no sealed blob — ServerPatches/
 * mail_inbox_status); while that route is not deployed it falls back to the full
 * `/mail/v1/inbox` listing and counts it.
 */
@Singleton
class MailUnreadSync @Inject constructor(
    private val client: OshiMailClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val core = MailUnreadSyncCore(
        fetchStatusUnread = { client.inboxStatusUnread() },
        fetchListUnread = { client.inbox().count { it.folder == "inbox" && !it.seen } },
        publish = { MailUnread.set(it) },
    )

    private var periodic: Job? = null

    fun install(context: Context) {
        instance = this
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    core.resetBackoff()
                    trigger(MailUnreadSyncCore.Trigger.NETWORK)
                }
            })
        }.onFailure { Log.w(TAG, "network callback not registered: ${it.message}") }
        trigger(MailUnreadSyncCore.Trigger.LAUNCH)
    }

    fun onForeground() {
        trigger(MailUnreadSyncCore.Trigger.FOREGROUND)
        if (periodic?.isActive == true) return
        periodic = scope.launch {
            while (isActive) {
                delay(core.nextPeriodicDelayMs())
                core.check(MailUnreadSyncCore.Trigger.PERIODIC)
            }
        }
    }

    fun onBackground() {
        periodic?.cancel()
        periodic = null
    }

    fun onMailPush() = trigger(MailUnreadSyncCore.Trigger.PUSH)

    private fun trigger(t: MailUnreadSyncCore.Trigger) {
        scope.launch { core.check(t) }
    }

    companion object {
        private const val TAG = "MailUnreadSync"

        /** For FCMService (field-injected service that already exists; keeps its DI untouched). */
        @Volatile
        var instance: MailUnreadSync? = null
            private set
    }
}

/**
 * The policy, free of Android types so it runs as a JVM unit test.
 *
 * [fetchStatusUnread] returns the unread Inbox count from the status route, or null when
 * the route is not deployed (404 `not-found`); it throws [OshiMailClient.MailException]
 * `no-mailbox` when the identity has no mailbox.
 */
class MailUnreadSyncCore(
    private val fetchStatusUnread: suspend () -> Int?,
    private val fetchListUnread: suspend () -> Int,
    private val publish: (Int) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Trigger(val urgent: Boolean) {
        LAUNCH(false), FOREGROUND(false), PERIODIC(false), NETWORK(true), PUSH(true)
    }

    enum class Outcome { RAN, SKIPPED_GATE, NO_MAILBOX, FAILED }

    private val mutex = Mutex()

    @Volatile var consecutiveFailures = 0
        private set
    @Volatile var noMailbox = false
        private set
    @Volatile var statusRouteMissing = false
        private set
    @Volatile private var lastStartedAt = Long.MIN_VALUE / 2

    fun resetBackoff() { consecutiveFailures = 0 }

    fun nextPeriodicDelayMs(): Long =
        if (noMailbox) CAP_MS else backoffDelayMs(consecutiveFailures)

    suspend fun check(trigger: Trigger): Outcome {
        // Foreground/launch within MIN_SPACING of the last check are the same event;
        // a push or the network returning always asks (after any check in flight).
        if (!trigger.urgent && trigger != Trigger.PERIODIC && mutex.isLocked) return Outcome.SKIPPED_GATE
        if (trigger == Trigger.PERIODIC && noMailbox) return Outcome.NO_MAILBOX
        return mutex.withLock {
            val now = nowMs()
            if (!trigger.urgent && trigger != Trigger.PERIODIC && now - lastStartedAt < MIN_SPACING_MS) {
                return@withLock Outcome.SKIPPED_GATE
            }
            lastStartedAt = now
            try {
                var unread: Int? = null
                if (!statusRouteMissing) {
                    unread = fetchStatusUnread()
                    if (unread == null) statusRouteMissing = true
                }
                publish(unread ?: fetchListUnread())
                noMailbox = false
                consecutiveFailures = 0
                Outcome.RAN
            } catch (e: OshiMailClient.MailException) {
                if (e.code == "no-mailbox") {
                    noMailbox = true
                    consecutiveFailures = 0
                    publish(0)
                    Outcome.NO_MAILBOX
                } else {
                    consecutiveFailures++
                    Outcome.FAILED
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                Outcome.FAILED
            }
        }
    }

    companion object {
        const val BASE_MS = 60_000L
        const val CAP_MS = 15 * 60_000L
        const val MIN_SPACING_MS = 10_000L

        fun backoffDelayMs(failures: Int): Long =
            (BASE_MS shl failures.coerceIn(0, 16)).coerceAtMost(CAP_MS)
    }
}
