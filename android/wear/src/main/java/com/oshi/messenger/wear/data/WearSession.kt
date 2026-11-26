package com.oshi.messenger.wear.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.wear.DebugSources
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearNet
import com.oshi.wearbridge.WearProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a screen is showing. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val reason: Reason) : Load<Nothing>
}

/** Where a reply / reaction stands, for the little confirmation on the reply screen. */
sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState
    data object Sent : SendState
    data class Failed(val reason: Reason) : SendState
}

/**
 * __WEAR_BRIDGE_2026_11_17__ The watch's ONLY copy of anything a message says:
 * in memory, in this ViewModel. [clear] drops it all, and MainActivity calls
 * it from onStop, so leaving the app (wrist down, home, another app) leaves
 * nothing behind; the next visit asks the phone again.
 */
class WearSession(app: Application) : AndroidViewModel(app) {

    private val source: WearSource = testSource ?: DebugSources.fake(app) ?: PhoneWearSource(app)

    companion object {
        /** Screenshot / UI tests only: the source the next session uses. */
        @androidx.annotation.VisibleForTesting
        @Volatile var testSource: WearSource? = null
    }

    private val _list = MutableStateFlow<Load<List<WearConversation>>>(Load.Loading)
    val list: StateFlow<Load<List<WearConversation>>> = _list.asStateFlow()

    /** __WEAR_POLISH_2026_11_25__ What the phone can do (empty until the first list, or for an older phone). */
    private val _caps = MutableStateFlow<Set<String>>(emptySet())
    val caps: StateFlow<Set<String>> = _caps.asStateFlow()

    /** How the phone is connected (internet / nearby mesh / LoRa); null when unknown. */
    private val _net = MutableStateFlow<WearNet?>(null)
    val net: StateFlow<WearNet?> = _net.asStateFlow()

    /**
     * Photo previews by message id, in memory only. A null value = asked and
     * there is none (so it is not asked again). Dropped by [clear].
     */
    private val _thumbs = MutableStateFlow<Map<String, ByteArray?>>(emptyMap())
    val thumbs: StateFlow<Map<String, ByteArray?>> = _thumbs.asStateFlow()
    private val thumbsAsked = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _threads = MutableStateFlow<Map<String, Load<WearThread>>>(emptyMap())
    val threads: StateFlow<Map<String, Load<WearThread>>> = _threads.asStateFlow()

    private val _send = MutableStateFlow<SendState>(SendState.Idle)
    val send: StateFlow<SendState> = _send.asStateFlow()

    /**
     * True while OUR OWN input screen (the system RemoteInput: voice / keyboard)
     * is on top. That covers the activity and stops it, but it is still the
     * person replying, not leaving: the cache is kept for that one hop.
     */
    @Volatile var awaitingInput: Boolean = false

    fun key(kind: WearConvKind, id: String) = kind.wire + ":" + id

    fun conversation(kind: WearConvKind, id: String): WearConversation? =
        (_list.value as? Load.Ready)?.value?.firstOrNull { it.kind == kind && it.id == id }

    fun refreshList() {
        if (_list.value !is Load.Ready) _list.value = Load.Loading
        viewModelScope.launch {
            _list.value = when (val r = source.inbox()) {
                is Outcome.Ok -> {
                    _caps.value = r.value.caps
                    _net.value = r.value.net
                    Load.Ready(r.value.conversations)
                }
                is Outcome.Fail -> Load.Failed(r.reason)
            }
        }
    }

    fun can(cap: String): Boolean = cap in _caps.value

    /**
     * __WEAR_POLISH_2026_11_25__ The thread is on screen: mark it read on the
     * phone, as opening it there would (only when the phone supports it, and
     * only when there is something unread). The list badge drops at once.
     */
    fun markRead(kind: WearConvKind, id: String) {
        if (!can(WearProtocol.CAP_READ)) return
        val conv = conversation(kind, id) ?: return
        if (conv.unread <= 0) return
        _list.update { l ->
            if (l is Load.Ready) Load.Ready(l.value.map { if (it.kind == kind && it.id == id) it.copy(unread = 0) else it }) else l
        }
        viewModelScope.launch { source.markRead(kind, id) }
    }

    /** Ask once for a photo preview; the bubble redraws when it lands. */
    fun requestThumb(kind: WearConvKind, id: String, messageId: String) {
        if (!can(WearProtocol.CAP_THUMB) || !thumbsAsked.add(messageId)) return
        viewModelScope.launch {
            val bytes = (source.thumbnail(kind, id, messageId) as? Outcome.Ok)?.value
            _thumbs.update { it + (messageId to bytes) }
        }
    }

    /** A voice note recorded on the watch: hand it to the phone, which sends it. */
    fun sendVoice(kind: WearConvKind, id: String, audio: ByteArray, durationMs: Long) {
        _send.value = SendState.Sending
        viewModelScope.launch {
            val r = source.sendVoice(kind, id, audio, durationMs)
            _send.value = if (r is Outcome.Fail) SendState.Failed(r.reason) else SendState.Sent
            if (r is Outcome.Ok) { refreshThread(kind, id); refreshList() }
        }
    }

    fun refreshThread(kind: WearConvKind, id: String) {
        val k = key(kind, id)
        if (_threads.value[k] !is Load.Ready) _threads.update { it + (k to Load.Loading) }
        viewModelScope.launch {
            val next: Load<WearThread> = when (val r = source.thread(kind, id)) {
                is Outcome.Ok -> Load.Ready(r.value)
                is Outcome.Fail -> Load.Failed(r.reason)
            }
            _threads.update { it + (k to next) }
        }
    }

    fun sendText(kind: WearConvKind, id: String, text: String, onDone: (Boolean) -> Unit = {}) {
        val body = text.trim()
        if (body.isEmpty()) return
        _send.value = SendState.Sending
        viewModelScope.launch {
            val r = source.send(kind, id, body)
            _send.value = if (r is Outcome.Fail) SendState.Failed(r.reason) else SendState.Sent
            if (r is Outcome.Ok) { refreshThread(kind, id); refreshList() }
            onDone(r is Outcome.Ok)
        }
    }

    fun react(kind: WearConvKind, id: String, messageId: String, emoji: String, onDone: (Boolean) -> Unit = {}) {
        _send.value = SendState.Sending
        viewModelScope.launch {
            val r = source.react(kind, id, messageId, emoji)
            _send.value = if (r is Outcome.Fail) SendState.Failed(r.reason) else SendState.Sent
            if (r is Outcome.Ok) refreshThread(kind, id)
            onDone(r is Outcome.Ok)
        }
    }

    fun resetSend() { _send.value = SendState.Idle }

    /** Forget everything a message said. */
    fun clear() {
        _list.value = Load.Loading
        _threads.value = emptyMap()
        _thumbs.value = emptyMap()
        thumbsAsked.clear()
        _send.value = SendState.Idle
    }

    override fun onCleared() {
        clear()
        source.close()
    }
}
