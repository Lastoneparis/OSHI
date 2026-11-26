package com.oshi.messenger.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.OSHIBot
import com.oshi.messenger.network.bot.BotApiResult
import com.oshi.messenger.network.bot.BotDirectoryApi
import com.oshi.messenger.network.bot.BotProfileUpdate
import com.oshi.messenger.service.BotGroupStore
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.util.BotUsername
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * __BOTS_PHASE1_2026_11_24__ "My bots" › a bot: the public profile (username with live
 * availability, about, public toggle), share link + QR, revoke token.
 */
@HiltViewModel
class BotPublicProfileViewModel @Inject constructor(
    private val botManager: BotManager,
    private val api: BotDirectoryApi,
    private val store: BotGroupStore,
) : ViewModel() {

    data class Ui(
        val bot: OSHIBot? = null,
        val username: String = "",
        val usernameStatus: BotFatherViewModel.UsernameStatus = BotFatherViewModel.UsernameStatus.Idle,
        val about: String = "",
        val isPublic: Boolean = false,
        val saving: Boolean = false,
    )

    enum class Event { SAVED, SAVED_LOCALLY, REVOKED, REVOKE_FAILED }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events

    fun load(localBotId: String) {
        viewModelScope.launch {
            val bot = botManager.bots.first().firstOrNull { it.id == localBotId } ?: return@launch
            val extras = store.ownExtras(bot.id)
            _ui.value = Ui(bot = bot, username = BotUsername.normalize(bot.username), about = extras.about, isPublic = extras.isPublic,
                usernameStatus = if (BotUsername.isValid(BotUsername.normalize(bot.username))) BotFatherViewModel.UsernameStatus.Idle
                else BotFatherViewModel.UsernameStatus.Invalid(BotUsername.problem(bot.username) ?: BotUsername.Problem.EMPTY))
        }
    }

    private var checkJob: Job? = null

    fun setUsername(raw: String) {
        val u = BotUsername.normalize(raw).take(BotUsername.MAX_LENGTH)
        checkJob?.cancel()
        val problem = BotUsername.problem(u)
        val bot = _ui.value.bot
        _ui.update { it.copy(username = u, usernameStatus = when {
            problem != null -> BotFatherViewModel.UsernameStatus.Invalid(problem)
            bot != null && BotUsername.normalize(bot.username) == u -> BotFatherViewModel.UsernameStatus.Idle
            bot != null && BotManager.isServerBot(bot) -> BotFatherViewModel.UsernameStatus.Checking
            else -> BotFatherViewModel.UsernameStatus.Unchecked
        }) }
        if (problem != null || bot == null || !BotManager.isServerBot(bot) || BotUsername.normalize(bot.username) == u) return
        checkJob = viewModelScope.launch {
            delay(500)
            val r = api.usernameAvailable(u, bot.token)
            if (_ui.value.username == u) _ui.update { it.copy(usernameStatus = BotFatherViewModel.availabilityStatus(u, r)) }
        }
    }

    fun setAbout(v: String) = _ui.update { it.copy(about = v.take(BotUsername.MAX_ABOUT)) }
    fun setPublic(v: Boolean) = _ui.update { it.copy(isPublic = v) }

    val canSave: Boolean get() = _ui.value.let {
        !it.saving && it.usernameStatus !is BotFatherViewModel.UsernameStatus.Invalid &&
            it.usernameStatus !is BotFatherViewModel.UsernameStatus.Taken &&
            it.usernameStatus !is BotFatherViewModel.UsernameStatus.Checking
    }

    fun save() {
        val s = _ui.value
        val bot = s.bot ?: return
        if (!canSave) return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val updated = bot.copy(username = s.username)
            botManager.updateBot(updated)
            store.setOwnExtras(bot.id, BotGroupStore.OwnProfileExtras(s.about.trim(), s.isPublic))
            val event = if (!BotManager.isServerBot(bot)) Event.SAVED else {
                when (val r = api.setMyProfile(bot.token, BotProfileUpdate(
                    username = s.username, name = bot.name, description = bot.description,
                    about = s.about.trim(), avatarEmoji = bot.avatarEmoji, public = s.isPublic,
                ))) {
                    is BotApiResult.Ok -> Event.SAVED
                    BotApiResult.UsernameTaken -> {
                        _ui.update { it.copy(usernameStatus = BotFatherViewModel.statusFor(s.username, r)) }
                        Event.SAVED_LOCALLY
                    }
                    else -> Event.SAVED_LOCALLY
                }
            }
            _ui.update { it.copy(bot = updated, saving = false) }
            _events.tryEmit(event)
        }
    }

    fun revokeToken() {
        val bot = _ui.value.bot ?: return
        viewModelScope.launch {
            when (val r = api.revokeToken(bot.token)) {
                is BotApiResult.Ok -> {
                    val updated = botManager.replaceToken(bot, r.value.token, r.value.botId)
                    _ui.update { it.copy(bot = updated) }
                    _events.tryEmit(Event.REVOKED)
                }
                else -> _events.tryEmit(Event.REVOKE_FAILED)
            }
        }
    }
}
