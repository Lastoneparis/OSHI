package com.oshi.messenger.service

import com.oshi.messenger.data.model.BotCallbackAnswer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Transport for the `bot_callback_answer` queue item, from the poller that decodes it
 * ([com.oshi.messenger.network.vps.VPSClient]) to whatever screen is open.
 *
 * A plain object rather than a Hilt binding because the producer is the VPS poller —
 * which is constructed long before, and lives far outside, any screen's graph — and
 * the consumer is a composable that may not exist at all. `replay = 0` is the point:
 * an answer to a tap the user made two screens ago is noise, not a message, so if
 * nobody is listening it is dropped. `extraBufferCapacity` keeps `tryEmit` from ever
 * failing on the poller's thread when several land in one poll cycle.
 */
object BotCallbackAnswers {

    private val _answers = MutableSharedFlow<BotCallbackAnswer>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val answers: SharedFlow<BotCallbackAnswer> = _answers.asSharedFlow()

    fun publish(answer: BotCallbackAnswer) {
        _answers.tryEmit(answer)
    }
}

/*
 * __BOT_PUBLIC_ID_2026_09_16__ `BotTokenRegistry` used to live here: an in-memory map
 * of bot tokens harvested from inbound envelopes, so that a button on a bot the user
 * does not own would have something to authenticate a dispatch with.
 *
 * It is deleted, and it should never come back. Two independent reasons:
 *
 *  1. It is unnecessary. `/api/bot/dispatch` and `/api/bot/miniapp/session` now accept
 *     `botId` + the caller's public key, and the SERVER checks group membership. A
 *     client no longer needs a bearer secret to press a button, so there is nothing to
 *     cache. See `BotPlatformClient.BotAuth`.
 *
 *  2. It never worked, and could not have. The `botToken` on the inbound envelope is
 *     the server's 8-character DISPLAY prefix plus "…", not the secret
 *     (`OSHI/BotManager.swift:1384-1387,:1504-1510`). The registry's own length guard
 *     (`if (token.length < 8) return`) happily accepted that 11-character prefix and
 *     would have handed it to `/api/bot/dispatch` AS a token — an authentication
 *     failure dressed up as a working code path.
 *
 * The general rule this leaves behind: a client caching a credential it was pushed is
 * a smell even when it works, and here it was a bug as well.
 */
