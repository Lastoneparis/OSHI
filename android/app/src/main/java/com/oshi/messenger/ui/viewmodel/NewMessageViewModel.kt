package com.oshi.messenger.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.MeshPeer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@HiltViewModel
class NewMessageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val meshManager: MeshNetworkManager,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    companion object {
        private const val TAG = "NewMessageVM"

        /** Longest edge kept when re-encoding a picked photo, same cap as ChatScreen. */
        private const val MAX_IMAGE_DIMENSION = 1920

        /**
         * Is [address] a recipient we can actually send to?
         *
         * The rule used to be the iOS one (`NewMessageView.swift:650-653`): at
         * least 20 characters after trimming, and not our own key. That accepted
         * things that are not keys — a key with an autocompleted word and two
         * spaces in it, a key with a character typed after the `=`, a key whose
         * padding had been eaten — showed a green "Valid address" for each, and
         * then failed at send with nothing on screen to explain it.
         *
         * The real rule is in [RecipientKey.validate]: base64 in either spelling,
         * decoding to exactly 32 bytes, and not us. Use [checkRecipient] where the
         * *reason* matters (i.e. anywhere a user can see it); this boolean is kept
         * for the call sites that only need yes/no.
         *
         * Pure and in the companion so it can be unit-tested without Hilt.
         */
        fun isValidRecipient(address: String, myPublicKey: String): Boolean =
            RecipientKey.validate(address, myPublicKey) is RecipientKey.Result.Valid
    }

    /**
     * Outcome of the "send my first message" action.
     *
     * iOS drives the same three outcomes with `showingSuccess` / `showingError` /
     * `errorMessage` (`NewMessageView.swift:24-26`, raised in `sendMessage()`
     * at `:740-779`). Modelling them as one state instead of three booleans keeps
     * "sending" from being representable at the same time as "sent".
     */
    sealed interface SendState {
        data object Idle : SendState
        data object Sending : SendState
        /** Every part of the message reached a send path. */
        data class Sent(val recipientPublicKey: String) : SendState
        /** Nothing further was attempted; [failedAttachments] were never sent. */
        data class Failed(val failedAttachments: Int) : SendState
    }

    // My public key for QR code display
    private val _myPublicKey = MutableStateFlow(cryptoManager.getPublicKeyString())
    val myPublicKey: StateFlow<String> = _myPublicKey.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    val contacts: StateFlow<List<Contact>> = messageRepository
        .getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nearbyPeers: StateFlow<List<MeshPeer>> = meshManager.connectedPeers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** @see [Companion.isValidRecipient] */
    fun isValidRecipient(address: String): Boolean =
        isValidRecipient(address, _myPublicKey.value)

    /**
     * Classify [address] against our own key, so the screen can say *what* is
     * wrong with a paste instead of only that something is.
     *
     * @see RecipientKey.validate
     */
    fun checkRecipient(address: String): RecipientKey.Result =
        RecipientKey.validate(address, _myPublicKey.value)

    /**
     * Send the composed message from the New Message screen.
     *
     * Mirrors iOS `NewMessageView.sendMessage()` (`NewMessageView.swift:740`):
     * the text goes first, then each attachment carries the screen's view-once
     * flag (`:758-761`). Both halves go through the SAME repository entry points
     * the chat screen uses (`MessageRepository.sendTextMessage` /
     * `sendMediaMessage`) — no second send path was invented for this screen,
     * so retry, receipts, V2 routing and mesh fallback all behave identically.
     *
     * Attachment bytes are resolved here rather than in the composable so the
     * decode never runs on the Main dispatcher.
     */
    fun sendFirstMessage(
        recipientPublicKey: String,
        text: String,
        attachments: List<Uri> = emptyList(),
        isViewOnce: Boolean = false
    ) {
        // The CANONICAL spelling, not the paste. A key reaches this screen as
        // standard base64 or as base64url (the VPS paths and iOS's VoIP push both
        // hand out the latter), and filing the conversation under whichever
        // spelling was pasted is how a message goes to a peer id nobody else in
        // the app uses. `RecipientKey.canonicalize` folds both onto the one form
        // `CryptoManager.getPublicKeyString()` emits.
        val recipient = RecipientKey.canonicalRecipientOrNull(recipientPublicKey, _myPublicKey.value)
        if (recipient == null) {
            // iOS refuses with "new.error.invalidRecipient" (NewMessageView.swift:742).
            _sendState.value = SendState.Failed(attachments.size)
            return
        }
        if (_sendState.value is SendState.Sending) return

        _sendState.value = SendState.Sending
        viewModelScope.launch {
            var failed = 0

            val trimmedText = text.trim()
            if (trimmedText.isNotEmpty()) {
                val result = messageRepository.sendTextMessage(recipient, trimmedText)
                if (result.isFailure) {
                    Log.e(TAG, "❌ First text message failed: ${result.exceptionOrNull()?.message}")
                    _sendState.value = SendState.Failed(attachments.size)
                    return@launch
                }
            }

            for (uri in attachments) {
                val bytes = loadImageBytes(uri)
                if (bytes == null) {
                    failed++
                    continue
                }
                val result = messageRepository.sendMediaMessage(
                    recipientPublicKey = recipient,
                    mediaType = MediaType.IMAGE,
                    mediaData = bytes,
                    caption = null,
                    isViewOnce = isViewOnce
                )
                if (result.isFailure) {
                    Log.e(TAG, "❌ Attachment failed: ${result.exceptionOrNull()?.message}")
                    failed++
                }
            }

            _sendState.value =
                if (failed > 0) SendState.Failed(failed) else SendState.Sent(recipient)
        }
    }

    /**
     * __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18) A scanned code is
     * announced as "Contact added!" — make that true, so the person is in the
     * conversation list before the first message.
     */
    fun saveScannedContact(publicKey: String) = messageRepository.saveContactIfMissing(publicKey)

    /** Clear the one-shot result once the UI has shown it. */
    fun acknowledgeSendState() {
        _sendState.value = SendState.Idle
    }

    /**
     * Decode a picked image, downsampling to [MAX_IMAGE_DIMENSION] on the longest
     * edge before re-encoding as JPEG. Full-resolution modern camera images are a
     * reliable OOM on mid-range devices, which is why the bounds pass runs first.
     */
    private suspend fun loadImageBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) return@withContext null

            if (origW <= MAX_IMAGE_DIMENSION && origH <= MAX_IMAGE_DIMENSION) {
                return@withContext context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }

            var inSampleSize = 1
            while (origW / (inSampleSize * 2) >= MAX_IMAGE_DIMENSION &&
                origH / (inSampleSize * 2) >= MAX_IMAGE_DIMENSION
            ) {
                inSampleSize *= 2
            }
            val sampled = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(
                    stream, null,
                    BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                )
            } ?: return@withContext null

            val longest = maxOf(sampled.width, sampled.height)
            val bitmap = if (longest > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    sampled,
                    (sampled.width * scale).toInt().coerceAtLeast(1),
                    (sampled.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== sampled) sampled.recycle()
                scaled
            } else sampled

            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bitmap.recycle()
                out.toByteArray()
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding attachment: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode attachment: ${e.message}")
            null
        }
    }
}
