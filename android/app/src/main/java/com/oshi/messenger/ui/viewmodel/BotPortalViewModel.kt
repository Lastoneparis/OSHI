package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.*
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.service.GroupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BotPortalViewModel @Inject constructor(
    private val botManager: BotManager,
    private val cryptoManager: CryptoManager,
    private val groupManager: GroupManager,
    // __BOT_COMMANDS_2026_11_24__ pushes a server bot's command list (setMyCommands).
    private val botPlatformClient: com.oshi.messenger.network.bot.BotPlatformClient,
    // __BOTS_PHASE1_2026_11_24__ name / description / avatar reach the public profile too.
    private val botDirectoryApi: com.oshi.messenger.network.bot.BotDirectoryApi
) : ViewModel() {

    companion object {
        const val MAX_NAME = 64
        const val MAX_DESCRIPTION = 512

        /** Why the profile editor must refuse, or null. Pure, unit-tested. */
        @JvmStatic
        fun validateProfile(name: String, description: String): ProfileError? = when {
            name.isBlank() -> ProfileError.NAME_EMPTY
            name.trim().length > MAX_NAME -> ProfileError.NAME_TOO_LONG
            description.trim().length > MAX_DESCRIPTION -> ProfileError.DESCRIPTION_TOO_LONG
            else -> null
        }

        /**
         * Where a bot's command list lives. A LOCAL bot answers on this phone, so its list
         * is complete the moment it is saved; a webhook bot is registered on the server and
         * its list must also reach `setMyCommands` (see [BotManager.needsServerRegistration]).
         */
        @JvmStatic
        fun syncsCommandsToServer(bot: OSHIBot): Boolean = BotManager.isServerBot(bot)

        /** The avatars the profile editor offers. The bot model stores ONE emoji. */
        val AVATAR_CHOICES = listOf(
            "🤖", "👋", "❓", "⏰", "🛡️", "🔗", "🔊", "📣", "🧠", "📚", "🌦️", "🎲",
            "🎵", "🛒", "📅", "✅", "💡", "🔔", "🧭", "🗳️", "🐱", "🦊", "🌱", "🚀",
        )
    }

    enum class CommandSync { LOCAL, SYNCING, SYNCED, FAILED }
    enum class ProfileError { NAME_EMPTY, NAME_TOO_LONG, DESCRIPTION_TOO_LONG }

    enum class PortalTab { CHANNELS, BOTS, APPS }

    data class PortalUiState(
        val selectedTab: PortalTab = PortalTab.BOTS,
        val searchQuery: String = "",
        val bots: List<OSHIBot> = emptyList(),
        val filteredBots: List<OSHIBot> = emptyList(),
        val myChannels: List<Group> = emptyList(),
        val discoveredChannels: List<GroupManager.DiscoveredGroup> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    data class CreateBotState(
        val step: Int = 0,
        val name: String = "",
        val username: String = "",
        val description: String = "",
        val selectedType: BotType = BotType.AUTOMATION,
        val selectedTemplate: BotTemplate? = null,
        val triggers: List<BotTrigger> = emptyList(),
        val webhookURL: String = "",
        val scriptContent: String = "",
        val selectedGroups: List<String> = emptyList(),
        val isCreating: Boolean = false,
        val createdBot: OSHIBot? = null,
        val error: String? = null
    )

    data class BotDetailState(
        val bot: OSHIBot? = null,
        val triggers: List<BotTrigger> = emptyList(),
        val messages: List<BotMessage> = emptyList(),
        val isEditing: Boolean = false,
        val showDeleteConfirm: Boolean = false,
        val testMessage: String = "",
        val isSending: Boolean = false,
        /** __BOT_COMMANDS_2026_11_24__ the `/` menu as the owner configured it. */
        val commands: List<BotManager.CommandRow> = emptyList(),
        val commandSync: CommandSync = CommandSync.LOCAL,
        /** Names of the groups the bot is in (empty = in none: its commands reach nobody yet). */
        val groupNames: List<String> = emptyList()
    )

    private val _portalState = MutableStateFlow(PortalUiState())
    val portalState: StateFlow<PortalUiState> = _portalState.asStateFlow()

    private val _createBotState = MutableStateFlow(CreateBotState())
    val createBotState: StateFlow<CreateBotState> = _createBotState.asStateFlow()

    private val _botDetailState = MutableStateFlow(BotDetailState())
    val botDetailState: StateFlow<BotDetailState> = _botDetailState.asStateFlow()

    init {
        viewModelScope.launch {
            botManager.bots.collect { bots ->
                _portalState.value = _portalState.value.copy(
                    bots = bots,
                    filteredBots = filterBots(bots, _portalState.value.searchQuery)
                )
            }
        }
        viewModelScope.launch {
            groupManager.groups.collect { allGroups ->
                val channels = allGroups.filter { it.isPublic }
                _portalState.value = _portalState.value.copy(myChannels = channels)
            }
        }
        viewModelScope.launch {
            groupManager.discoveredGroups.collect { discovered ->
                _portalState.value = _portalState.value.copy(discoveredChannels = discovered)
            }
        }
    }

    // =========================================================================
    // PORTAL
    // =========================================================================

    fun selectTab(tab: PortalTab) {
        _portalState.value = _portalState.value.copy(selectedTab = tab)
    }

    fun updateSearchQuery(query: String) {
        _portalState.value = _portalState.value.copy(
            searchQuery = query,
            filteredBots = filterBots(_portalState.value.bots, query)
        )
    }

    private fun filterBots(bots: List<OSHIBot>, query: String): List<OSHIBot> {
        if (query.isBlank()) return bots
        return bots.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.username.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
        }
    }

    // =========================================================================
    // CREATE BOT WIZARD
    // =========================================================================

    fun showCreateBot() {
        _createBotState.value = CreateBotState()
    }

    fun dismissCreateBot() {
        _createBotState.value = CreateBotState()
    }

    fun updateBotName(name: String) {
        _createBotState.value = _createBotState.value.copy(name = name)
    }

    fun updateBotUsername(username: String) {
        _createBotState.value = _createBotState.value.copy(
            username = username.replace(" ", "").lowercase()
        )
    }

    fun updateBotDescription(description: String) {
        _createBotState.value = _createBotState.value.copy(description = description)
    }

    fun selectBotType(type: BotType) {
        _createBotState.value = _createBotState.value.copy(selectedType = type)
    }

    fun selectTemplate(template: BotTemplate) {
        _createBotState.value = _createBotState.value.copy(
            selectedTemplate = template,
            selectedType = template.type,
            triggers = template.triggers,
            scriptContent = template.scriptTemplate ?: ""
        )
    }

    fun addTrigger(trigger: BotTrigger) {
        val current = _createBotState.value.triggers.toMutableList()
        current.add(trigger)
        _createBotState.value = _createBotState.value.copy(triggers = current)
    }

    fun removeTrigger(triggerId: String) {
        val current = _createBotState.value.triggers.filter { it.id != triggerId }
        _createBotState.value = _createBotState.value.copy(triggers = current)
    }

    fun updateWebhookURL(url: String) {
        _createBotState.value = _createBotState.value.copy(webhookURL = url)
    }

    fun updateScriptContent(script: String) {
        _createBotState.value = _createBotState.value.copy(scriptContent = script)
    }

    fun toggleGroupAssignment(groupId: String) {
        val current = _createBotState.value.selectedGroups.toMutableList()
        if (current.contains(groupId)) current.remove(groupId) else current.add(groupId)
        _createBotState.value = _createBotState.value.copy(selectedGroups = current)
    }

    fun nextStep() {
        val current = _createBotState.value.step
        if (current < 3) _createBotState.value = _createBotState.value.copy(step = current + 1)
    }

    fun previousStep() {
        val current = _createBotState.value.step
        if (current > 0) _createBotState.value = _createBotState.value.copy(step = current - 1)
    }

    fun createBot() {
        val state = _createBotState.value
        viewModelScope.launch {
            _createBotState.value = _createBotState.value.copy(isCreating = true)
            try {
                val ownerKey = cryptoManager.exportPublicKeyBase64()
                val bot = botManager.createBot(
                    name = state.name,
                    username = state.username,
                    description = state.description,
                    type = state.selectedType,
                    ownerPublicKey = ownerKey
                )

                // Add triggers
                for (trigger in state.triggers) {
                    botManager.addTrigger(bot.id, trigger)
                }

                // Update webhook/script if provided
                if (state.webhookURL.isNotBlank() || state.scriptContent.isNotBlank()) {
                    botManager.updateBot(bot.copy(
                        webhookURL = state.webhookURL.ifBlank { null },
                        scriptContent = state.scriptContent.ifBlank { null }
                    ))
                }

                // Assign to groups
                for (groupId in state.selectedGroups) {
                    botManager.assignBotToGroup(bot.id, groupId)
                }

                _createBotState.value = _createBotState.value.copy(
                    isCreating = false,
                    createdBot = bot,
                    step = 4
                )
            } catch (e: Exception) {
                _createBotState.value = _createBotState.value.copy(
                    isCreating = false,
                    error = e.message
                )
            }
        }
    }

    // =========================================================================
    // BOT DETAIL
    // =========================================================================

    fun loadBotDetail(botId: String) {
        viewModelScope.launch {
            val bot = botManager.bots.first().find { it.id == botId }
            if (bot != null) {
                val ids = bot.assignedGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val names = groupManager.groups.value
                    .filter { g -> ids.any { it.equals(g.id, ignoreCase = true) } }
                    .map { it.name }
                _botDetailState.value = BotDetailState(bot = bot, groupNames = names)
                launch {
                    botManager.commandRowsForBot(botId).collect { rows ->
                        _botDetailState.value = _botDetailState.value.copy(commands = rows)
                    }
                }
                botManager.getTriggersForBot(botId).collect { triggers ->
                    _botDetailState.value = _botDetailState.value.copy(triggers = triggers)
                }
            }
        }
    }

    fun toggleBotEnabled(botId: String) {
        viewModelScope.launch {
            botManager.toggleBot(botId)
        }
    }

    fun deleteBot(botId: String) {
        viewModelScope.launch {
            val bot = _botDetailState.value.bot ?: return@launch
            botManager.deleteBot(bot)
        }
    }

    fun deleteTrigger(triggerId: String) {
        viewModelScope.launch {
            botManager.removeTrigger(triggerId)
        }
    }

    fun joinDiscoveredChannel(discovered: GroupManager.DiscoveredGroup) {
        groupManager.joinDiscoveredGroup(discovered)
    }

    fun registerBotWithServer(botId: String) {
        viewModelScope.launch {
            val bot = _botDetailState.value.bot ?: return@launch
            botManager.registerBotWithServer(bot)
        }
    }

    /** Toggle a single bot permission and persist — task 1.6.6 */
    fun updateBotPermission(botId: String, field: String, value: Boolean) {
        viewModelScope.launch {
            val bot = _botDetailState.value.bot ?: return@launch
            val perms = try {
                org.json.JSONObject(bot.permissions)
            } catch (_: Exception) {
                org.json.JSONObject()
            }
            perms.put(field, value)
            val updated = bot.copy(permissions = perms.toString())
            botManager.updateBot(updated)
            _botDetailState.value = _botDetailState.value.copy(bot = updated)
        }
    }

    fun updateTestMessage(message: String) {
        _botDetailState.value = _botDetailState.value.copy(testMessage = message)
    }

    fun sendTestMessage() {
        val bot = _botDetailState.value.bot ?: return
        val message = _botDetailState.value.testMessage
        if (message.isBlank()) return

        viewModelScope.launch {
            _botDetailState.value = _botDetailState.value.copy(isSending = true)
            try {
                val groups = bot.assignedGroups.split(",").filter { it.isNotEmpty() }
                val groupId = groups.firstOrNull() ?: ""
                botManager.sendTestMessage(bot, groupId, message)
                _botDetailState.value = _botDetailState.value.copy(
                    isSending = false,
                    testMessage = ""
                )
            } catch (e: Exception) {
                _botDetailState.value = _botDetailState.value.copy(isSending = false)
            }
        }
    }

    // =========================================================================
    // __BOT_COMMANDS_2026_11_24__ PROFILE + COMMANDS (owner configuration)
    // =========================================================================

    /** Save name / description / avatar. Returns the refusal, or null when saved. */
    fun updateBotProfile(name: String, description: String, emoji: String): ProfileError? {
        validateProfile(name, description)?.let { return it }
        val bot = _botDetailState.value.bot ?: return null
        val updated = bot.copy(
            name = name.trim(),
            description = description.trim(),
            avatarEmoji = emoji.ifBlank { bot.avatarEmoji }
        )
        _botDetailState.value = _botDetailState.value.copy(bot = updated)
        viewModelScope.launch {
            botManager.updateBot(updated)
            if (BotManager.isServerBot(updated)) runCatching {
                botDirectoryApi.setMyProfile(updated.token, com.oshi.messenger.network.bot.BotProfileUpdate(
                    name = updated.name, description = updated.description, avatarEmoji = updated.avatarEmoji))
            }
        }
        return null
    }

    /**
     * Add ([triggerId] null) or edit one command; [onResult] gets the refusal or null.
     * A webhook bot's whole list is then pushed to the server.
     */
    fun saveCommand(
        triggerId: String?,
        name: String,
        description: String,
        reply: String,
        onResult: (com.oshi.messenger.util.BotCommandSyntax.EditError?) -> Unit
    ) {
        val bot = _botDetailState.value.bot ?: return
        viewModelScope.launch {
            when (val r = botManager.saveCommand(bot.id, triggerId, name, description, reply)) {
                is BotManager.CommandSave.Refused -> onResult(r.error)
                is BotManager.CommandSave.Saved -> {
                    onResult(null)
                    pushCommands(bot, r.rows)
                }
            }
        }
    }

    fun deleteCommand(triggerId: String) {
        val bot = _botDetailState.value.bot ?: return
        viewModelScope.launch {
            pushCommands(bot, botManager.deleteCommand(bot.id, triggerId))
        }
    }

    /** Retry the server push after a failure. */
    fun retryCommandSync() {
        val bot = _botDetailState.value.bot ?: return
        viewModelScope.launch { pushCommands(bot, _botDetailState.value.commands) }
    }

    private suspend fun pushCommands(bot: OSHIBot, rows: List<BotManager.CommandRow>) {
        if (!syncsCommandsToServer(bot)) {
            _botDetailState.value = _botDetailState.value.copy(commandSync = CommandSync.LOCAL)
            return
        }
        _botDetailState.value = _botDetailState.value.copy(commandSync = CommandSync.SYNCING)
        val ok = botPlatformClient.setMyCommands(bot.token, rows.filter { it.isActive }.map { it.spec })
        _botDetailState.value = _botDetailState.value.copy(
            commandSync = if (ok) CommandSync.SYNCED else CommandSync.FAILED
        )
    }
}
