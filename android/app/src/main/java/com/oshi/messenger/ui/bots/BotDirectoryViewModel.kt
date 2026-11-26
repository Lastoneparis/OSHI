package com.oshi.messenger.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.data.model.OSHIBot
import com.oshi.messenger.network.bot.BotApiResult
import com.oshi.messenger.network.bot.BotDirectoryApi
import com.oshi.messenger.network.bot.BotPlatformClient
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.BotGroupStore
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.GroupUpdateAuthorizer
import com.oshi.messenger.util.BotAdminRights
import com.oshi.messenger.util.BotPublicProfile
import com.oshi.messenger.util.GroupBot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * __BOTS_PHASE1_2026_11_24__ Finding a bot (link, `@` search) and ADDING it to a group or
 * channel I administer, with the admin rights I choose. There is no private bot chat:
 * a bot lives inside groups and channels (owner re-scope).
 */
@HiltViewModel
class BotDirectoryViewModel @Inject constructor(
    private val api: BotDirectoryApi,
    private val groupManager: GroupManager,
    private val botManager: BotManager,
    private val botGroupStore: BotGroupStore,
    private val cryptoManager: CryptoManager,
) : ViewModel() {

    sealed interface ProfileState {
        data object Loading : ProfileState
        data class Loaded(val profile: BotPublicProfile) : ProfileState
        /** Unknown, or not public — the same sentence (contract §2). */
        data object NotFound : ProfileState
        /** The directory route is not on the server yet, or the network failed. */
        data object Unreachable : ProfileState
    }

    sealed interface SearchState {
        data object Idle : SearchState
        data object Searching : SearchState
        data class Results(val bots: List<BotPublicProfile>) : SearchState
        data object Unreachable : SearchState
    }

    enum class AddOutcome { ADDED, NOT_ALLOWED, UNREACHABLE, FAILED }

    companion object {
        /** Pure: groups/channels where [me] is an admin, channels last-name sorted. */
        @JvmStatic
        fun adminChats(groups: List<Group>, me: String): List<Group> {
            val mine = GroupUpdateAuthorizer.canonicalIdentity(me)
            return groups.filter { g -> g.adminPublicKeys.any { GroupUpdateAuthorizer.canonicalIdentity(it) == mine } }
                .sortedBy { it.name.lowercase() }
        }

        fun isChannel(group: Group): Boolean = group.groupType == GroupType.ADMIN_ONLY
    }

    private val _profile = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val profile: StateFlow<ProfileState> = _profile.asStateFlow()

    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _outcomes = MutableSharedFlow<AddOutcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<AddOutcome> = _outcomes

    val adminChats: StateFlow<List<Group>> = groupManager.groups
        .map { adminChats(it, myKey()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** My own bots, as profiles, so "Add a bot" lists them without a search. */
    val myBots: StateFlow<List<OSHIBot>> = botManager.bots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun myKey(): String = runCatching { cryptoManager.getPublicKeyString() }.getOrDefault("")

    fun loadProfile(username: String?, botId: String? = null) {
        _profile.value = ProfileState.Loading
        viewModelScope.launch {
            // One of MY bots needs no server round trip.
            own(username, botId)?.let { _profile.value = ProfileState.Loaded(it); return@launch }
            _profile.value = when (val r = api.publicProfile(username = username, botId = botId)) {
                is BotApiResult.Ok -> ProfileState.Loaded(r.value)
                BotApiResult.NotFound, is BotApiResult.Invalid -> ProfileState.NotFound
                else -> ProfileState.Unreachable
            }
        }
    }

    private suspend fun own(username: String?, botId: String?): BotPublicProfile? {
        val bots = runCatching { botManager.bots.first() }.getOrDefault(emptyList())
        val u = username?.let { com.oshi.messenger.util.BotUsername.normalize(it) }
        val bot = bots.firstOrNull { b ->
            (u != null && com.oshi.messenger.util.BotUsername.normalize(b.username) == u) ||
                (botId != null && botGroupStore.botIdFor(b) == botId)
        } ?: return null
        return profileOf(bot)
    }

    fun profileOf(bot: OSHIBot): BotPublicProfile {
        val extras = botGroupStore.ownExtras(bot.id)
        return BotPublicProfile(
            botId = botGroupStore.botIdFor(bot),
            username = com.oshi.messenger.util.BotUsername.normalize(bot.username),
            name = bot.name,
            description = bot.description,
            about = extras.about,
            avatarEmoji = bot.avatarEmoji,
            hosting = if (BotManager.isServerBot(bot)) "remote" else "local",
            public = extras.isPublic,
        )
    }

    private var searchJob: Job? = null

    /** `@` + text in the search field: debounced `/api/bot/search`. */
    fun setQuery(raw: String) {
        _query.value = raw
        searchJob?.cancel()
        val q = raw.trim().removePrefix("@")
        if (q.isEmpty()) { _search.value = SearchState.Idle; return }
        searchJob = viewModelScope.launch {
            delay(300)
            _search.value = SearchState.Searching
            _search.value = when (val r = api.search(q)) {
                is BotApiResult.Ok -> SearchState.Results(r.value)
                else -> SearchState.Unreachable
            }
        }
    }

    /**
     * Add [profile]'s bot to [group] with [rights]. My own bot is assigned locally and
     * re-registered with its group list (rights ride along); somebody else's goes through
     * `POST /api/bot/add` signed as me, the group's admin.
     */
    fun addToGroup(profile: BotPublicProfile, group: Group, rights: BotAdminRights) {
        viewModelScope.launch {
            val ownBot = runCatching { botManager.bots.first() }.getOrDefault(emptyList())
                .firstOrNull { botGroupStore.botIdFor(it) == profile.botId }
            val entry = GroupBot(profile.botId, profile.username, profile.name, profile.avatarEmoji, rights, profile.commands)
            val outcome = if (ownBot != null) {
                botGroupStore.put(group.id, entry)
                botManager.assignBotToGroup(ownBot.id, group.id)
                val fresh = runCatching { botManager.bots.first() }.getOrDefault(emptyList()).firstOrNull { it.id == ownBot.id }
                if (fresh != null && BotManager.isServerBot(fresh)) runCatching { botManager.registerBotWithServer(fresh) }
                AddOutcome.ADDED
            } else {
                when (api.addToGroup(profile.botId, group.id, isChannel(group), group.memberPublicKeys, rights)) {
                    is BotApiResult.Ok -> { botGroupStore.put(group.id, entry); AddOutcome.ADDED }
                    BotApiResult.Unauthorized, BotApiResult.NotFound -> AddOutcome.NOT_ALLOWED
                    BotApiResult.Unavailable -> AddOutcome.UNREACHABLE
                    else -> AddOutcome.FAILED
                }
            }
            _outcomes.tryEmit(outcome)
        }
    }
}
