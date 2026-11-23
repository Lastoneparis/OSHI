package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.service.GroupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ForwardPickerViewModel @Inject constructor(
    private val groupManager: GroupManager,
    private val messageRepository: MessageRepository
) : ViewModel() {

    data class ForwardTarget(
        val id: String,
        val name: String,
        val avatarBytes: ByteArray?,
        val isGroup: Boolean,
        /**
         * The contact's public key, or null for a group.
         *
         * __FORWARD_SEARCH_BY_KEY_2026_11_05__ Added so the picker can be
         * searched by key. For a contact this equals [id]; it is a separate
         * field rather than a reuse of [id] because [id] is also a group id,
         * and a group has no key — making the distinction explicit stops a
         * group ever being matched against pasted key material.
         */
        val publicKey: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ForwardTarget
            return id == other.id
        }
        override fun hashCode(): Int = id.hashCode()
    }

    val targets: StateFlow<List<ForwardTarget>> = combine(
        groupManager.groupConversations,
        messageRepository.getAllContacts()
    ) { groups, contacts ->
        val groupTargets = groups.map { conv ->
            ForwardTarget(
                id = conv.group.id,
                name = conv.group.name,
                avatarBytes = conv.group.groupImage,
                isGroup = true,
                publicKey = null
            )
        }
        val contactTargets = contacts.map { contact ->
            ForwardTarget(
                id = contact.publicKey,
                // __SHARED_NICKNAME_2026_09_22__ alias > shared nickname > placeholder.
                name = com.oshi.messenger.data.model.PeerNicknameStore.displayName(contact.alias, contact.publicKey)
                    .ifEmpty { contact.publicKey.take(12) },
                avatarBytes = contact.profilePicture,
                isGroup = false,
                publicKey = contact.publicKey
            )
        }
        groupTargets + contactTargets
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * @param fileName the attachment's ORIGINAL name, when the caller knows it (the
     *   inbound share sheet does; forwarding an existing bubble does not, because
     *   `Message` has no column for it). 🔧 FIX (2026-08-24 feature check): it used
     *   not to be a parameter at all, so a PDF shared into OSHI from another app
     *   reached the peer as `<messageId>.dat` on the 1:1 path and with
     *   `mediaFileName = null` on the group path — even though both transports have
     *   carried the field for as long as they have existed
     *   (`GroupMessage.mediaFileName`, `V2FileKeyMessage.filename`).
     */
    fun forward(
        target: ForwardTarget,
        content: String,
        mediaType: MediaType?,
        mediaData: ByteArray?,
        fileName: String? = null,
        /**
         * __VIDEO_NOTE_2026_09_24__ non-null ⇒ the forwarded video is a round note and
         * stays one (docs/VIDEO_NOTE_SPEC.md §4 "Forwarding keeps videoNote and
         * durationMs"). 0 = duration unknown, the flag still travels.
         */
        videoNoteDurationMs: Long? = null
    ) {
        val isNote = videoNoteDurationMs != null && mediaType == MediaType.VIDEO && mediaData != null
        // __FORWARD_MOST_USED_FIRST_2026_11_05__ Recorded HERE rather than in the
        // row's onClick, because this is where a forward actually happens — both
        // the groups list and the contacts list call this one function, and a tap
        // that failed to send should not count as a forward.
        com.oshi.messenger.service.ForwardAffinityStore.record(target.id)
        viewModelScope.launch {
            if (target.isGroup) {
                if (isNote) {
                    groupManager.sendGroupMessage(
                        groupId = target.id, content = "", mediaType = mediaType, mediaData = mediaData,
                        mediaFileName = fileName,
                        mediaDurationMs = videoNoteDurationMs?.takeIf { it > 0L },
                        isVideoNote = true
                    )
                } else if (mediaType != null && mediaData != null) {
                    // __WIRE_CAPTION_2026_09_27__ A forwarded 1:1 attachment's row text is a
                    // local marker ("[AUDIO] 0:05", "[DOCUMENT] x.pdf", "[IMAGE]"); in a
                    // group it becomes `plaintextContent`, which iPhones print under the
                    // bubble. Send only a real caption, and a document under its name.
                    val caption = com.oshi.messenger.data.repository.MessageRepository
                        .wireCaption(mediaType, content) ?: ""
                    val name = fileName ?: if (mediaType == MediaType.DOCUMENT) {
                        com.oshi.messenger.data.repository.MessageRepository.documentNameFromCaption(content)
                    } else null
                    groupManager.sendGroupMessage(target.id, caption, mediaType, mediaData, name)
                } else {
                    groupManager.sendGroupMessage(target.id, content, mediaType, mediaData, fileName)
                }
            } else {
                if (mediaType != null && mediaData != null) {
                    messageRepository.sendMediaMessage(
                        recipientPublicKey = target.id,
                        mediaType = mediaType,
                        mediaData = mediaData,
                        // A note carries no caption; its local "[VIDEO]" marker must not
                        // travel as one.
                        caption = if (isNote) null else content,
                        attachmentFileName = fileName,
                        videoNoteDurationMs = if (isNote) videoNoteDurationMs else null
                    )
                } else {
                    messageRepository.sendTextMessage(target.id, content)
                }
            }
        }
    }
}
