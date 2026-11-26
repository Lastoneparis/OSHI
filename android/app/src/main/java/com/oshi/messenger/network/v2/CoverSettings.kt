package com.oshi.messenger.network.v2

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oshi.messenger.ui.viewmodel.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoverSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ohttpSettings: com.oshi.messenger.network.ohttp.OhttpSettings,
) {
    fun mode(): String? = runBlocking {
        val prefs = context.dataStore.data.first()
        when {
            prefs[booleanPreferencesKey("covert_channel_enabled")] != true -> null
            else -> "event"
        }
    }.also { com.oshi.messenger.service.AppPollState.coverTimingJitter = it != null }
    /**
     * The ONE interceptor every v2 OkHttp client installs. Outermost: Oblivious HTTP
     * ("Privacy relay (beta)", see OhttpInterceptor) — when active it handles eligible
     * /v2/ calls itself and fails closed; everything else falls to the cover transport.
     *
     * __SERVER_CLOCK_EVERY_SIGNER_2026_11_23__ wrapped by ServerClock.sampling: every /v2/
     * answer teaches the clock offset, and a request refused for the clock is re-signed
     * once and re-sent through the same transport.
     */
    fun interceptor(): okhttp3.Interceptor =
        com.oshi.messenger.network.auth.ServerClock.sampling(
            com.oshi.messenger.network.ohttp.OhttpInterceptor(ohttpSettings::transport, CoverInterceptor(::mode))
        )
}
