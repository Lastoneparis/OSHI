package com.oshi.messenger.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupConversation
import com.oshi.messenger.data.model.GroupMember
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.MeshState
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.GroupTypeLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for Groups Screen
 */
data class GroupsUiState(
    val groups: List<GroupConversation> = emptyList(),
    val filteredGroups: List<GroupConversation> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: GroupType? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedGroup: Group? = null,
    val showCreateGroupSheet: Boolean = false,
    val showGroupInfoSheet: Boolean = false
)

/**
 * UI State for Create Group
 */
data class CreateGroupState(
    val groupName: String = "",
    val selectedGroupType: GroupType = GroupType.COLLABORATIVE,
    val selectedAvatar: String = "👥",
    val addedMembers: List<GroupMember> = emptyList(),
    val newMemberKey: String = "",
    val isCreating: Boolean = false,
    val error: String? = null
)

/**
 * What the create-group sheet actually asks `GroupManager.createGroup` for.
 *
 * Pure, so the state → arguments mapping is unit-testable without an Android
 * runtime. It exists because that mapping already lost a field once: the emoji
 * picker wrote `selectedAvatar` and nothing read it, so every group Android
 * created carried `avatar = null` (audit G-4) and every iPhone in it saw the
 * default glyph. iOS: `MessageGroup.avatar`, `GroupMessaging.swift:123`, taken
 * by `createGroup` at `:1177`.
 */
data class CreateGroupRequest(
    val name: String,
    val memberPublicKeys: List<String>,
    val isPublic: Boolean,
    val groupType: GroupType,
    val avatar: String?
) {
    companion object {
        fun of(state: CreateGroupState): CreateGroupRequest = CreateGroupRequest(
            name = state.groupName,
            memberPublicKeys = state.addedMembers.map { it.publicKey },
            isPublic = state.selectedGroupType == GroupType.PUBLIC,
            groupType = state.selectedGroupType,
            // Blank is not an avatar: an incoming update that carries an empty
            // `avatar` clears it on iOS (GroupMessaging.swift:800-820).
            avatar = state.selectedAvatar.takeIf { it.isNotBlank() }
        )
    }
}

/**
 * Terminal-aware state of the "Discover Groups" screen.
 *
 * Public-group discovery is PASSIVE — `GroupManager` only listens for
 * `PUBLIC_GROUP_AD` broadcasts from nearby peers (`GroupManager.kt:390`) — so
 * "no result" and "still looking" are indistinguishable from the list alone.
 * The screen used to render `discoveredGroups.isEmpty()` as the spinner, which
 * means it spun forever: verified identical on device at t+0 and t+30 s.
 */
enum class DiscoveryPhase { SCANNING, RESULTS, EMPTY, ERROR }

/**
 * GroupsViewModel - Handles all group-related UI logic
 * Compatible with iOS GroupViews.swift functionality
 */
@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupManager: GroupManager,
    private val cryptoManager: CryptoManager,
    private val messageRepository: MessageRepository,
    private val meshManager: MeshNetworkManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** __MENTIONS_2026_09_23__ groups with an unread message that mentions us (canonical, uppercase). */
    val mentionedGroups: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = groupManager.mentionedGroups

    companion object {

        /**
         * The list the groups tab renders: type filter, then search, then newest first.
         *
         * [typeLabel] is passed in rather than read off the model. The search used to
         * match `GroupType.displayName`, an English literal on a data-layer enum, so in
         * an app shipping 34 languages the only users who could find a group by its type
         * were the ones reading English. Keeping the resolver a parameter also keeps this
         * function pure, so the matching is testable without a Context.
         */
        fun filterGroups(
            groups: List<GroupConversation>,
            searchQuery: String,
            typeFilter: GroupType?,
            typeLabel: (GroupType) -> String
        ): List<GroupConversation> {
            var filtered = groups

            if (typeFilter != null) {
                filtered = filtered.filter { it.group.effectiveGroupType == typeFilter }
            }

            if (searchQuery.isNotBlank()) {
                filtered = filtered.filter { conversation ->
                    conversation.group.name.contains(searchQuery, ignoreCase = true) ||
                        typeLabel(conversation.group.effectiveGroupType)
                            .contains(searchQuery, ignoreCase = true)
                }
            }

            return filtered.sortedByDescending {
                it.lastMessage?.timestamp ?: it.group.createdAt
            }
        }
        val AVATAR_OPTIONS = listOf(
            "👥", "💬", "🚀", "🎯", "💡", "🌟", "🔥", "⚡",
            "🎨", "🎮", "🌍", "🔒", "💼", "🎵", "📚", "🏆"
        )

        /** How long we keep the spinner up before admitting nothing is out there. */
        const val DISCOVERY_TIMEOUT_MS = 20_000L

        /**
         * Grace period before an idle mesh is reported as an error. The mesh takes
         * a moment to advertise after the screen opens, and flashing "mesh is off"
         * during that window would be a lie.
         */
        const val MESH_GRACE_MS = 5_000L

        /**
         * The screen's whole state machine, as a pure function so it can be tested
         * without a device, a mesh or a clock.
         *
         * Results beat everything: an ad can land after the timeout and must
         * promote the screen back out of the empty state.
         */
        fun discoveryPhase(
            groupCount: Int,
            meshIdle: Boolean,
            elapsedMillis: Long,
            timeoutMillis: Long = DISCOVERY_TIMEOUT_MS,
            meshGraceMillis: Long = MESH_GRACE_MS
        ): DiscoveryPhase = when {
            groupCount > 0 -> DiscoveryPhase.RESULTS
            meshIdle && elapsedMillis >= meshGraceMillis -> DiscoveryPhase.ERROR
            elapsedMillis >= timeoutMillis -> DiscoveryPhase.EMPTY
            else -> DiscoveryPhase.SCANNING
        }
    }

    // Main UI State
    private val _uiState = MutableStateFlow(GroupsUiState())
    val uiState: StateFlow<GroupsUiState> = _uiState.asStateFlow()

    // Create Group State
    private val _createGroupState = MutableStateFlow(CreateGroupState())
    val createGroupState: StateFlow<CreateGroupState> = _createGroupState.asStateFlow()

    // User's public key
    val myPublicKey: String
        get() = cryptoManager.getPublicKeyString()

    // Contacts for the contact picker
    val contacts: StateFlow<List<Conversation>> = messageRepository.getConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * __GROUP_MEMBER_NAMES_2026_09_23__ What the group info screen needs to NAME a member instead
     * of printing their address: contact aliases, shared nicknames, definition aliases. Live, so a
     * profile that lands while the sheet is open renames the row. See [memberDisplayName].
     */
    data class MemberNameInputs(
        val contactAliases: Map<String, String?> = emptyMap(),
        val nicknames: Map<String, String> = emptyMap(),
        val definitionAliases: Map<String, Map<String, String>> = emptyMap(),
    )

    val memberNameInputs: StateFlow<MemberNameInputs> = combine(
        messageRepository.getAllContacts(),
        com.oshi.messenger.data.model.PeerNicknameStore.nicknames,
        com.oshi.messenger.data.model.GroupMemberAliasStore.aliases.onStart {
            com.oshi.messenger.data.model.GroupMemberAliasStore.init(appContext)
        },
    ) { contacts, nicks, aliases ->
        MemberNameInputs(contacts.associate { it.publicKey to it.alias }, nicks, aliases)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MemberNameInputs())

    /** Display name for [publicKey] in [groupId], or null (caller shows the formatted address). */
    fun memberDisplayName(groupId: String, publicKey: String, inputs: MemberNameInputs): String? =
        com.oshi.messenger.data.model.GroupMemberNames.resolve(
            publicKey, inputs.contactAliases, inputs.nicknames,
            inputs.definitionAliases[groupId.lowercase()].orEmpty(),
        )

    // Discovered groups from cross-platform mesh
    val discoveredGroups: StateFlow<List<GroupManager.DiscoveredGroup>> = groupManager.discoveredGroups

    // ── Discover Groups state machine ────────────────────────────────────────
    private val _discoveryElapsedMs = MutableStateFlow(0L)
    private var discoveryTicker: Job? = null

    val discoveryPhase: StateFlow<DiscoveryPhase> = combine(
        discoveredGroups,
        meshManager.meshState,
        _discoveryElapsedMs
    ) { groups, meshState, elapsed ->
        discoveryPhase(
            groupCount = groups.size,
            meshIdle = meshState == MeshState.IDLE,
            elapsedMillis = elapsed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DiscoveryPhase.SCANNING)

    init {
        observeGroups()
    }

    /**
     * (Re)start the scan window. Called on entry and by the retry button; retrying
     * drops the stale result set so a group that has gone away does not linger.
     */
    fun startGroupDiscovery(clearResults: Boolean = false) {
        if (clearResults) groupManager.clearDiscoveredGroups()
        discoveryTicker?.cancel()
        _discoveryElapsedMs.value = 0L
        discoveryTicker = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                delay(500)
                val elapsed = System.currentTimeMillis() - startedAt
                _discoveryElapsedMs.value = elapsed
                if (elapsed >= DISCOVERY_TIMEOUT_MS) break
            }
        }
    }

    /**
     * Add members from the contact picker
     */
    fun addMembersFromContacts(publicKeys: List<String>) {
        val state = _createGroupState.value
        val existingKeys = state.addedMembers.map { it.publicKey }.toSet()
        val newMembers = publicKeys
            .filter { it != myPublicKey && it !in existingKeys }
            .map { pk ->
                val alias = contacts.value.find { it.participantPublicKey == pk }?.participantAlias
                GroupMember(
                    publicKey = pk,
                    name = alias ?: formatAddress(pk),
                    isAdmin = false
                )
            }
        if (newMembers.isNotEmpty()) {
            _createGroupState.update { current ->
                current.copy(
                    addedMembers = current.addedMembers + newMembers,
                    error = null
                )
            }
        }
    }

    /**
     * Observe group conversations from GroupManager
     */
    private fun observeGroups() {
        viewModelScope.launch {
            groupManager.groupConversations.collect { conversations ->
                _uiState.update { state ->
                    state.copy(
                        groups = conversations,
                        filteredGroups = filterGroups(
                            conversations,
                            state.searchQuery,
                            state.selectedFilter
                        ),
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Filter groups by search query and type filter, with the type name resolved in the
     * user's language.
     */
    private fun filterGroups(
        groups: List<GroupConversation>,
        searchQuery: String,
        typeFilter: GroupType?
    ): List<GroupConversation> = filterGroups(groups, searchQuery, typeFilter, ::groupTypeLabel)

    /** Localized name of a group type, for the search box. */
    private fun groupTypeLabel(type: GroupType): String = GroupTypeLabels.label(appContext, type)

    // =========================================================================
    // SEARCH & FILTER ACTIONS
    // =========================================================================

    /**
     * Update search query
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredGroups = filterGroups(state.groups, query, state.selectedFilter)
            )
        }
    }

    /**
     * Set type filter
     */
    fun setTypeFilter(type: GroupType?) {
        _uiState.update { state ->
            state.copy(
                selectedFilter = type,
                filteredGroups = filterGroups(state.groups, state.searchQuery, type)
            )
        }
    }

    /**
     * Clear search
     */
    fun clearSearch() {
        _uiState.update { state ->
            state.copy(
                searchQuery = "",
                filteredGroups = filterGroups(state.groups, "", state.selectedFilter)
            )
        }
    }

    // =========================================================================
    // GROUP SELECTION & SHEETS
    // =========================================================================

    /**
     * Select a group to view info
     */
    fun selectGroup(group: Group) {
        _uiState.update { it.copy(selectedGroup = group, showGroupInfoSheet = true) }
    }

    /**
     * Dismiss group info sheet
     */
    fun dismissGroupInfo() {
        _uiState.update { it.copy(showGroupInfoSheet = false, selectedGroup = null) }
    }

    /**
     * Show create group sheet
     */
    fun showCreateGroup() {
        _createGroupState.value = CreateGroupState() // Reset state
        _uiState.update { it.copy(showCreateGroupSheet = true) }
    }

    /**
     * Dismiss create group sheet
     */
    fun dismissCreateGroup() {
        _uiState.update { it.copy(showCreateGroupSheet = false) }
        _createGroupState.value = CreateGroupState()
    }

    // =========================================================================
    // CREATE GROUP ACTIONS
    // =========================================================================

    /**
     * Update group name
     */
    fun updateGroupName(name: String) {
        _createGroupState.update { it.copy(groupName = name) }
    }

    /**
     * Select group type
     */
    fun selectGroupType(type: GroupType) {
        _createGroupState.update { it.copy(selectedGroupType = type) }
    }

    /**
     * Select avatar
     */
    fun selectAvatar(avatar: String) {
        _createGroupState.update { it.copy(selectedAvatar = avatar) }
    }

    /**
     * Update new member key input
     */
    fun updateNewMemberKey(key: String) {
        _createGroupState.update { it.copy(newMemberKey = key) }
    }

    /**
     * Why a typed member key was refused, as a localized string.
     *
     * __GROUP_CREATE_SCROLL_2026_10_12__ The two messages this replaces were
     * `"Member already added"` and `"Cannot add yourself"` — English literals in
     * an app that ships 34 languages, the same defect [GroupTypeLabels] records
     * for the group-type names. Every id here already exists in the default
     * locale and in all 33 translations, so none of this needs new copy.
     */
    private fun memberKeyProblemMessage(problem: RecipientKey.Problem): String =
        appContext.getString(
            when (problem) {
                RecipientKey.Problem.EMPTY -> R.string.new_key_error_empty
                RecipientKey.Problem.CONTAINS_WHITESPACE -> R.string.new_key_error_spaces
                RecipientKey.Problem.TRAILING_CHARACTERS -> R.string.new_key_error_extra_characters
                RecipientKey.Problem.MISSING_PADDING -> R.string.new_key_error_padding
                RecipientKey.Problem.TOO_SHORT -> R.string.new_key_error_too_short
                RecipientKey.Problem.TOO_LONG -> R.string.new_key_error_too_long
                RecipientKey.Problem.NOT_A_KEY -> R.string.new_key_error_not_a_key
                // Not `new_key_error_own_key` ("choose someone else to message"),
                // which is written for the New Message screen: the creator is
                // already a member of every group they create.
                RecipientKey.Problem.OWN_KEY -> R.string.deeplink_own_key_error
            }
        )

    /**
     * Add the typed member key to the group being created.
     *
     * __GROUP_CREATE_SCROLL_2026_10_12__ This used to accept ANY non-blank
     * string: `if (trimmedKey.isBlank()) return` and nothing else. Typing a
     * person's name added them as a "member", enabled "Create", and produced a
     * private group holding a participant no key can ever match — the group
     * looked made and could never deliver. The QR branch in `GroupsScreen`
     * at least required 20 characters; the field beside it required nothing.
     *
     * [RecipientKey] is the validator the New Message screen already uses, and
     * it does the half that matters more here than there: it **canonicalises**.
     * A key reaches this app in two spellings (standard base64 and base64url —
     * `VPSClient.publicKeyToBase64url`, iOS's `urlEncodedKey`), and a member
     * stored under the spelling nobody else uses matches no roster, no envelope
     * and no `normalizeKey` comparison anywhere in `GroupManager`.
     */
    fun addMember() {
        val state = _createGroupState.value

        when (val check = RecipientKey.validate(state.newMemberKey, myPublicKey)) {
            is RecipientKey.Result.Invalid -> {
                // A blank field is not an error the user needs told about — it is
                // the resting state of the row, and the Add button is disabled.
                if (check.problem != RecipientKey.Problem.EMPTY) {
                    _createGroupState.update {
                        it.copy(error = memberKeyProblemMessage(check.problem))
                    }
                }
            }

            is RecipientKey.Result.Valid -> {
                val key = check.canonicalKey
                // Deduped on the canonical form, so the same key pasted in both
                // spellings is caught — the old string compare was not.
                if (state.addedMembers.any { it.publicKey == key }) {
                    _createGroupState.update {
                        it.copy(error = appContext.getString(R.string.error_already_added))
                    }
                    return
                }

                val alias = contacts.value.find { it.participantPublicKey == key }?.participantAlias
                val newMember = GroupMember(
                    publicKey = key,
                    name = alias?.takeIf { it.isNotBlank() } ?: formatAddress(key),
                    isAdmin = false
                )

                _createGroupState.update { current ->
                    current.copy(
                        addedMembers = current.addedMembers + newMember,
                        newMemberKey = "",
                        error = null
                    )
                }
            }
        }
    }

    /**
     * Remove member from the group
     */
    fun removeMember(publicKey: String) {
        _createGroupState.update { current ->
            current.copy(
                addedMembers = current.addedMembers.filter { it.publicKey != publicKey }
            )
        }
    }

    /**
     * Check if group can be created
     */
    fun canCreateGroup(): Boolean {
        val state = _createGroupState.value
        if (state.groupName.isBlank()) return false

        // Public groups don't need members
        if (state.selectedGroupType == GroupType.PUBLIC) return true

        // Non-public groups need at least one member
        return state.addedMembers.isNotEmpty()
    }

    /**
     * Create the group
     */
    fun createGroup() {
        val state = _createGroupState.value

        if (!canCreateGroup()) return

        _createGroupState.update { it.copy(isCreating = true) }

        viewModelScope.launch {
            try {
                val request = CreateGroupRequest.of(state)

                groupManager.createGroup(
                    name = request.name,
                    description = "",
                    memberPublicKeys = request.memberPublicKeys,
                    isPublic = request.isPublic,
                    groupType = request.groupType,
                    avatar = request.avatar
                )

                // Success - dismiss sheet
                _createGroupState.value = CreateGroupState()
                _uiState.update { it.copy(showCreateGroupSheet = false) }

            } catch (e: Exception) {
                _createGroupState.update {
                    it.copy(
                        isCreating = false,
                        error = e.message ?: "Failed to create group"
                    )
                }
            }
        }
    }

    /**
     * Clear create group error
     */
    fun clearCreateError() {
        _createGroupState.update { it.copy(error = null) }
    }

    // =========================================================================
    // GROUP MANAGEMENT ACTIONS
    // =========================================================================

    /**
     * Set the group picture (compresses bytes via the existing pipeline before storing).
     * The bytes passed in should already be downsampled by the caller (see PhotoPicker
     * launcher in `GroupsScreen`). GroupManager broadcasts to all members.
     */
    fun setGroupPicture(groupId: String, imageData: ByteArray?) {
        viewModelScope.launch {
            groupManager.setGroupPicture(groupId, imageData)
        }
    }

    /**
     * Set the per-group wallpaper. Synced to all members via group envelope.
     * Caller must ensure bytes are ≤ 500 KB (the launcher does so via
     * MediaCompressionManager / a local fallback compressor).
     */
    fun setGroupWallpaper(groupId: String, imageData: ByteArray?) {
        viewModelScope.launch {
            groupManager.setGroupWallpaper(groupId, imageData)
        }
    }

    /**
     * Rename a group.
     *
     * Emits the full iOS `MessageGroup` JSON (applied at GroupMessaging.swift:827) plus the
     * cheap `group_renamed` update iOS also parses (swift:972). Returns false when the local
     * user lacks `canChangeName` for this group type.
     */
    fun renameGroup(groupId: String, newName: String): Boolean =
        groupManager.updateGroup(groupId, name = newName.trim())

    /**
     * Add a member to an EXISTING group (iOS `AddMemberView`, GroupViews.swift:6456).
     * Returns false when the key is empty/already present or permission is denied.
     */
    fun addMemberToGroup(groupId: String, memberPublicKey: String): Boolean =
        groupManager.addMember(groupId, memberPublicKey.trim())

    /** Remove (kick) a member from an existing group — iOS `removeMember`, GroupMessaging.swift:1309. */
    fun removeMemberFromGroup(groupId: String, memberPublicKey: String): Boolean =
        groupManager.removeMember(groupId, memberPublicKey)

    /** Clear the group picture — iOS `removeGroupPicture`, GroupMessaging.swift:1921. */
    fun removeGroupPicture(groupId: String) {
        viewModelScope.launch { groupManager.setGroupPicture(groupId, null) }
    }

    /** Clear the synced group wallpaper — iOS `updateGroupWallpaper(nil)`, GroupMessaging.swift:1974. */
    fun removeGroupWallpaper(groupId: String) {
        viewModelScope.launch { groupManager.setGroupWallpaper(groupId, null) }
    }

    /**
     * Delete a group
     */
    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            groupManager.deleteGroup(groupId)
            dismissGroupInfo()
        }
    }

    /**
     * Leave a group
     */
    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            groupManager.leaveGroup(groupId)
            dismissGroupInfo()
        }
    }

    /**
     * __MERGED_CHATS_2026_11_18__ Mute / unmute from the merged chat list's row
     * sheet — the same toggle the group chat's own menu uses
     * (`GroupChatViewModel.toggleMute`), so the bell and the other devices agree.
     */
    fun toggleGroupMute(groupId: String): Boolean = groupManager.toggleGroupMute(groupId)

    /**
     * Group typing ping, sent inside the group message envelope — the only ingress iOS has
     * (GroupMessaging.swift:2905-2912). The 1:1 `⌨️TYPING⌨️` path Android used cannot ever work:
     * iOS posts `ReceivedTypingStatus` from nowhere, and the payload was missing the
     * non-optional `senderPublicKey`/`timestamp` fields anyway.
     */
    fun sendGroupTyping(groupId: String, isTyping: Boolean) {
        groupManager.sendGroupTypingStatus(groupId, isTyping)
    }

    /**
     * Check if user is admin of a group
     */
    fun isAdmin(group: Group): Boolean {
        return group.adminPublicKeys.contains(myPublicKey) ||
               group.creatorPublicKey == myPublicKey
    }

    /** iOS parity: permission for add/remove/rename/wallpaper respects groupType. */
    fun canManageGroup(group: Group): Boolean = group.canAddMembers(myPublicKey)

    /**
     * Get unread count for a group
     */
    fun getUnreadCount(groupId: String): Int {
        return _uiState.value.groups
            .find { it.group.id == groupId }
            ?.unreadCount ?: 0
    }

    // =========================================================================
    // SYNC
    // =========================================================================

    /**
     * Request group sync from all known contacts.
     * Called manually (refresh button) or automatically on screen open.
     */
    fun requestGroupSync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val peers = messageRepository.getAllContacts().first().map { it.publicKey }
            if (peers.isNotEmpty()) {
                groupManager.requestGroupSync(peers)
            }
            // Clear loading after short delay (sync is async)
            kotlinx.coroutines.delay(2_000)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // =========================================================================
    // DISCOVER & JOIN
    // =========================================================================

    /**
     * Join a discovered public group
     */
    fun joinDiscoveredGroup(discovered: GroupManager.DiscoveredGroup) {
        viewModelScope.launch {
            groupManager.joinDiscoveredGroup(discovered)
        }
    }

    // =========================================================================
    // UTILITY FUNCTIONS
    // =========================================================================

    /**
     * Format address for display (truncate long addresses)
     */
    private fun formatAddress(address: String): String {
        return if (address.length > 12) {
            "${address.take(6)}...${address.takeLast(4)}"
        } else {
            address
        }
    }

    /**
     * The picture-less gradient of a group.
     *
     * __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ This hashed
     * `String.hashCode()` into its own six-colour palette, while the header
     * INSIDE the group (`GroupChatScreen`, via `DefaultAvatar`) used the
     * fourteen-hue `AvatarIdentity` — so a room was one colour in the list and
     * another a tap later, and neither matched iOS (`GroupViews.swift`, which
     * hashes `group.id.uuidString` with `AvatarIdentity`). One source now.
     */
    fun getGradientColors(groupId: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
        com.oshi.messenger.ui.theme.AvatarIdentity.groupGradient(groupId)

    /** __AVATAR_PALETTE_V2_2026_11_17__ gradient + ink, same slot as [getGradientColors]. */
    fun getAvatarEntry(groupId: String): com.oshi.messenger.ui.theme.AvatarIdentity.Entry =
        com.oshi.messenger.ui.theme.AvatarIdentity.groupEntry(groupId)

    fun addContact(publicKey: String) {
        viewModelScope.launch {
            messageRepository.addContact(publicKey, "${publicKey.take(8)}...")
        }
    }
}
