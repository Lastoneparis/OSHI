package com.oshi.messenger.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.oshi.messenger.data.model.ContactCardWire
import com.oshi.messenger.ui.viewmodel.ChatViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ContactShareManager - Compatible with iOS ContactShareHandler.swift
 * Handles sharing contacts as vCards with embedded OSHI public keys
 */
@Singleton
class ContactShareManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ContactShareManager"
    }

    /**
     * Export an OSHI contact to vCard format
     * Embeds the OSHI public key in the NOTE field (iOS compatible)
     */
    fun exportToVCard(displayName: String, publicKey: String): String {
        val nameParts = displayName.split(" ", limit = 2)
        val firstName = nameParts.getOrElse(0) { displayName }
        val lastName = nameParts.getOrElse(1) { "" }

        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:$displayName")
            appendLine("N:$lastName;$firstName;;;")
            appendLine("NOTE:OSHI Public Key: $publicKey")
            appendLine("END:VCARD")
        }
    }

    /**
     * Import OSHI public key from a vCard string
     * Looks for "OSHI Public Key:" in the NOTE field
     */
    fun importFromVCard(vCardString: String): String? {
        val notePrefix = "OSHI Public Key: "
        for (line in vCardString.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("NOTE:") && trimmed.contains(notePrefix)) {
                val keyStart = trimmed.indexOf(notePrefix) + notePrefix.length
                return trimmed.substring(keyStart).trim()
            }
        }
        return null
    }

    /**
     * Share contact via Android share sheet
     */
    fun shareContact(displayName: String, publicKey: String) {
        val vCard = exportToVCard(displayName, publicKey)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/vcard"
            putExtra(Intent.EXTRA_TEXT, vCard)
            putExtra(Intent.EXTRA_SUBJECT, "OSHI Contact: $displayName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share Contact").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Log.d(TAG, "Sharing contact: $displayName")
    }

    /**
     * The exact bytes a shared contact card travels as.
     *
     * Single writer for BOTH surfaces (1-on-1 via [shareContactInApp], groups via
     * `GroupChatViewModel.sendContactCard`) so the two can never drift into
     * sending different shapes. See [ContactCardWire] for why the body must carry
     * an Apple reference-date `timestamp` and why it is built key by key.
     */
    fun contactCardBytes(
        displayName: String,
        publicKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ByteArray = ContactCardWire
        .encode(publicKey = publicKey, alias = displayName, timestampMillis = nowMillis)
        .toByteArray(Charsets.UTF_8)

    /**
     * Share contact in-app: builds the iOS-shaped card body and routes it through
     * the existing media-attach pipeline as a CONTACT-typed message. The
     * recipient's chat renders it as a tappable in-app card via
     * [com.oshi.messenger.ui.components.ContactCardBubble]; an iPhone renders it
     * via `ContactCardView` (`OSHI/MediaMessageView.swift:1488`).
     *
     * 🔴 REACHABILITY. This function had **zero call sites** until 2026-08-24: the
     * attachment menu's "Share contact" launched the SYSTEM address book and sent
     * `viewModel.sendMessage("[Contact] Alice")` — a plain text message with no
     * public key in it at all, which is not the same feature iOS ships. The live
     * chain is now: attachment menu → `OshiContactPickerSheet` →
     * `ChatViewModel.shareOshiContact` → here → [ChatViewModel.sendContactCard] →
     * `MessageRepository.sendMediaMessage(mediaType = CONTACT)`.
     *
     * Coexists with [shareContact] (system Intent.ACTION_SEND) — callers pick
     * the surface they prefer.
     */
    fun shareContactInApp(
        chatViewModel: ChatViewModel,
        displayName: String,
        publicKey: String
    ) {
        chatViewModel.sendContactCard(contactCardBytes(displayName, publicKey), displayName)
        Log.d(TAG, "Sharing contact in-app: $displayName")
    }

    /**
     * Copy public key to clipboard
     */
    fun copyPublicKey(publicKey: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("OSHI Public Key", publicKey)
        clipboard.setPrimaryClip(clip)
        Log.d(TAG, "Copied public key to clipboard")
    }
}
