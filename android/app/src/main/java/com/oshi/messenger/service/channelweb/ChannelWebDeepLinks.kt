package com.oshi.messenger.service.channelweb

import java.util.concurrent.ConcurrentHashMap

/**
 * __CHANNEL_WEB_2026_11_26__ Hand-off between a `/c/<handle>/<n>` deep link (MainActivity) and
 * the channel screen that scrolls to post `n` once it is open. One pending target per group,
 * consumed once.
 */
object ChannelWebDeepLinks {
    private val pending = ConcurrentHashMap<String, Int>()

    fun put(groupId: String, n: Int) { pending[groupId.uppercase()] = n }

    fun take(groupId: String): Int? = pending.remove(groupId.uppercase())
}
