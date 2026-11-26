package com.oshi.messenger.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.BotType
import com.oshi.messenger.data.model.OSHIBot
import com.oshi.messenger.network.bot.BotApiResult
import com.oshi.messenger.network.bot.BotDirectoryApi
import com.oshi.messenger.network.bot.BotPlatformClient
import com.oshi.messenger.network.bot.BotProfileUpdate
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.BotGroupStore
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.util.BotCommandSyntax
import com.oshi.messenger.util.BotKind
import com.oshi.messenger.util.BotUsername
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * __BOTS_PHASE1_2026_11_24__ BotFather, in the app (owner re-scope §ADD 1).
 *
 * 1 name → 2 @username (live availability: `setMyProfile` answers 409 when taken) →
 * 3 picture, about, description → 4 commands (one-tap suggestions + templates per
 * kind) → 5 token (copy, "keep it secret", curl/Python) → 6 add it to a group or channel
 * I administer.
 *
 * The bot is created (locally, then registered with the server) when step 1 is left:
 * the username check needs a token the server knows.
 */
@HiltViewModel
class BotFatherViewModel @Inject constructor(
    private val botManager: BotManager,
    private val api: BotDirectoryApi,
    private val botPlatformClient: BotPlatformClient,
    private val botGroupStore: BotGroupStore,
    private val cryptoManager: CryptoManager,
) : ViewModel() {

    sealed interface UsernameStatus {
        data object Idle : UsernameStatus
        data class Invalid(val problem: BotUsername.Problem) : UsernameStatus
        data object Checking : UsernameStatus
        data object Available : UsernameStatus
        data class Taken(val suggestions: List<String>) : UsernameStatus
        /** Could not ask the server; the name is checked again on every later save. */
        data object Unchecked : UsernameStatus
    }

    data class Ui(
        val step: Int = 1,
        val name: String = "",
        val username: String = "",
        val usernameStatus: UsernameStatus = UsernameStatus.Idle,
        val emoji: String = "🤖",
        val about: String = "",
        val description: String = "",
        val kind: BotKind? = null,
        val commands: List<BotCommandSyntax.Spec> = emptyList(),
        val bot: OSHIBot? = null,
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) {
        val canContinue: Boolean get() = !busy && when (step) {
            1 -> name.isNotBlank() && name.trim().length <= 64
            2 -> usernameStatus is UsernameStatus.Available || usernameStatus is UsernameStatus.Unchecked
            3 -> about.length <= BotUsername.MAX_ABOUT && description.length <= BotUsername.MAX_DESCRIPTION
            else -> true
        }
    }

    companion object {
        const val STEPS = 6

        /** Pure: the one-tap suggestions for [kind], minus what is already added. */
        @JvmStatic
        fun suggestions(kind: BotKind?, added: List<BotCommandSyntax.Spec>): List<BotCommandSyntax.Spec> {
            val taken = added.map { it.name }.toSet()
            return (BotKind.SUGGESTED + (kind?.commands ?: emptyList()))
                .map { BotCommandSyntax.Spec(it.first, it.second) }
                .filter { it.name !in taken }
                .distinctBy { it.name }
        }

        /** Pure: `usernameAvailable` answer → status. */
        @JvmStatic
        fun availabilityStatus(username: String, result: BotApiResult<Boolean>): UsernameStatus = when (result) {
            is BotApiResult.Ok -> if (result.value) UsernameStatus.Available else UsernameStatus.Taken(BotUsername.suggestions(username))
            is BotApiResult.Invalid -> UsernameStatus.Invalid(BotUsername.problem(username) ?: BotUsername.Problem.BAD_CHARACTERS)
            else -> UsernameStatus.Unchecked
        }

        /** Pure: map a `setMyProfile` answer (claiming the username) to a status. */
        @JvmStatic
        fun statusFor(username: String, result: BotApiResult<Unit>): UsernameStatus = when (result) {
            is BotApiResult.Ok -> UsernameStatus.Available
            BotApiResult.UsernameTaken -> UsernameStatus.Taken(BotUsername.suggestions(username))
            is BotApiResult.Invalid -> UsernameStatus.Invalid(BotUsername.problem(username) ?: BotUsername.Problem.BAD_CHARACTERS)
            else -> UsernameStatus.Unchecked
        }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun setName(v: String) = _ui.update { it.copy(name = v.take(64)) }
    fun setEmoji(v: String) = _ui.update { it.copy(emoji = v) }
    fun setAbout(v: String) = _ui.update { it.copy(about = v.take(BotUsername.MAX_ABOUT)) }
    fun setDescription(v: String) = _ui.update { it.copy(description = v.take(BotUsername.MAX_DESCRIPTION)) }
    fun setKind(k: BotKind) = _ui.update { it.copy(kind = k) }

    fun addCommand(spec: BotCommandSyntax.Spec) = _ui.update { s ->
        if (s.commands.any { it.name == spec.name } || s.commands.size >= BotCommandSyntax.MAX_COMMANDS) s
        else s.copy(commands = s.commands + spec)
    }

    fun removeCommand(name: String) = _ui.update { s -> s.copy(commands = s.commands.filterNot { it.name == name }) }

    private var checkJob: Job? = null

    /** Live: format first (instant), then availability (debounced). */
    fun setUsername(raw: String) {
        val u = BotUsername.normalize(raw).take(BotUsername.MAX_LENGTH)
        checkJob?.cancel()
        val problem = BotUsername.problem(u)
        _ui.update { it.copy(username = u, usernameStatus = if (problem != null) UsernameStatus.Invalid(problem) else UsernameStatus.Checking) }
        if (problem != null) return
        val token = _ui.value.bot?.token
        if (token == null) { _ui.update { it.copy(usernameStatus = UsernameStatus.Unchecked) }; return }
        checkJob = viewModelScope.launch {
            delay(500)
            val r = api.usernameAvailable(u, token)
            if (_ui.value.username == u) _ui.update { it.copy(usernameStatus = availabilityStatus(u, r)) }
        }
    }

    fun back() = _ui.update { if (it.step > 1) it.copy(step = it.step - 1) else it }

    fun next() {
        val s = _ui.value
        if (!s.canContinue) return
        when (s.step) {
            1 -> createOrRename()
            2 -> claimUsername()
            3 -> saveAndAdvance { bot ->
                botManager.updateBot(bot.copy(name = s.name.trim(), description = s.description.trim(), avatarEmoji = s.emoji))
                botGroupStore.setOwnExtras(bot.id, botGroupStore.ownExtras(bot.id).copy(about = s.about.trim()))
                api.setMyProfile(bot.token, BotProfileUpdate(
                    name = s.name.trim(), about = s.about.trim(), description = s.description.trim(), avatarEmoji = s.emoji,
                ))
            }
            4 -> saveAndAdvance { bot ->
                for (c in s.commands) botManager.saveCommand(bot.id, null, c.name, c.description, "")
                if (s.commands.isNotEmpty()) botPlatformClient.setMyCommands(bot.token, s.commands)
            }
            else -> _ui.update { it.copy(step = (it.step + 1).coerceAtMost(STEPS)) }
        }
    }

    /** Step 2 → 3: claim the username on the server (409 keeps the user on this step). */
    private fun claimUsername() {
        val s = _ui.value
        val bot = s.bot ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = api.setMyProfile(bot.token, BotProfileUpdate(username = s.username))
            if (r == BotApiResult.UsernameTaken || r is BotApiResult.Invalid) {
                _ui.update { it.copy(busy = false, usernameStatus = statusFor(s.username, r)) }
                return@launch
            }
            val updated = bot.copy(username = s.username)
            botManager.updateBot(updated)
            _ui.update { it.copy(bot = updated, busy = false, step = 3) }
        }
    }

    private fun createOrRename() {
        val s = _ui.value
        _ui.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            try {
                val existing = s.bot
                val bot = if (existing != null) {
                    existing.copy(name = s.name.trim()).also { botManager.updateBot(it) }
                } else {
                    val created = botManager.createBot(
                        name = s.name.trim(),
                        username = BotUsername.fromName(s.name),
                        description = "",
                        type = BotType.WEBHOOK,
                        ownerPublicKey = cryptoManager.exportPublicKeyBase64(),
                    )
                    runCatching { botManager.registerBotWithServer(created) }
                    created
                }
                runCatching { api.setMyProfile(bot.token, BotProfileUpdate(name = bot.name)) }
                _ui.update { it.copy(bot = bot, step = 2, busy = false) }
                if (_ui.value.username.isEmpty()) setUsername(BotUsername.fromName(s.name)) else setUsername(_ui.value.username)
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, failed = true) }
            }
        }
    }

    private fun saveAndAdvance(work: suspend (OSHIBot) -> Unit) {
        val bot = _ui.value.bot ?: return
        _ui.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            runCatching { work(bot) }
            val fresh = runCatching { botManager.bots.first() }.getOrNull()
                ?.firstOrNull { it.id == bot.id } ?: bot
            _ui.update { it.copy(bot = fresh, busy = false, step = (it.step + 1).coerceAtMost(STEPS)) }
        }
    }

}
