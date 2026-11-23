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
     */
    fun interceptor(): okhttp3.Interceptor =
        com.oshi.messenger.network.ohttp.OhttpInterceptor(ohttpSettings::transport, CoverInterceptor(::mode))
}
