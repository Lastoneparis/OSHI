package com.oshi.messenger.ui.components

import android.graphics.Bitmap
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import com.oshi.messenger.ui.theme.brandInk
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.oshi.messenger.R
import com.oshi.messenger.service.ProfileLinksManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * __TAP_THE_FACE_2026_10_27__ The Android half of the reporter's N9: "lors de
 * l'appui sur la photo de profil dans une conversation, afficher la fiche de
 * contact de cette personne avec son QR code et les liens de profils".
 *
 * Android already drew the contact's face in the chat's top bar and did nothing
 * when it was pressed; unlike iOS it had no contact card anywhere to open. This
 * is that card, as a sheet rather than a navigation destination: everything it
 * needs — the picture, the key, the links manager — is already loaded by the
 * chat, so a new route and a new ViewModel would have been ceremony around data
 * already in hand.
 *
 * Reuses [ProfileLinksPinnedBar] for the links rather than restating them, so
 * the card and the pinned bar cannot disagree about what a contact has shared,
 * and so a `waypath://` handle keeps the one tap behaviour it already has.
 *
 * __STATUS_TEXT_WAS_A_RIBBON_2026_10_27__ is why the content scrolls and why
 * nothing here competes for width inside a Row: that bug, found the same day,
 * was a weighted child starved by an unweighted sibling, and a sheet whose
 * button falls below the screen edge is exactly what it produced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileSheet(
    publicKey: String,
    displayName: String,
    profilePicture: ByteArray?,
    profileLinksManager: ProfileLinksManager,
    /**
     * __LAST_SEEN_ON_THE_CARD_2026_10_28__ (N16) Unix millis, or null when nothing is
     * known. Null and 0 both render as "unknown" rather than 1970 — see [PresenceFormat].
     */
    lastSeen: Long? = null,
    /**
     * __RENAME_ON_THE_CARD_2026_11_02__ (N4) Open the alias editor.
     *
     * Asked for in as many words: "a button to change the name (already present
     * in the ... menu)". The action already existed; what it lacked was a home
     * on the one screen that is ABOUT this person. Hunting for a rename inside a
     * fifteen-item overflow is the kind of thing people stop doing.
     *
     * Passed in rather than owned here, so the card and the overflow drive the
     * SAME dialog and cannot disagree about what renaming means. Null hides the
     * button — a caller with no editor shows no affordance for one.
     */
    onRename: (() -> Unit)? = null,
    /** (N16) Voice/video call straight from the card. Null hides the row. */
    onCall: ((video: Boolean) -> Unit)? = null,
    /** (N5) The encryption line the chat's top bar used to carry. Null hides it. */
    encryptionLabel: String? = null,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Both decoded off the main thread: a 200x200 JPEG plus a 512x512 QR matrix
    // is tens of milliseconds, and this opens over a chat someone is reading.
    var avatar by remember(profilePicture) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(profilePicture) {
        avatar = profilePicture?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
        }
    }

    var qr by remember(publicKey) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(publicKey) {
        qr = withContext(Dispatchers.Default) { qrBitmap(publicKey) }
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val a = avatar
                if (a != null) {
                    Image(
                        bitmap = a.asImageBitmap(),
                        contentDescription = stringResource(R.string.profile_picture),
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was a flat
                    // `primaryContainer` — the profile of every contact the same
                    // colour, unlike the list row that opened it.
                    com.oshi.messenger.ui.theme.ContactAvatar(
                        publicKey = publicKey,
                        name = displayName,
                        size = 96.dp,
                    )
                }
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            // __PADLOCK_OFF_THE_TOP_BAR_2026_11_13__ (Anatole N5) The "🔒 E2E" label
            // left the chat's top bar to give the name its width; it lives here, on
            // the page about this person, and in the notice at the top of the thread.
            if (encryptionLabel != null) {
                Text(
                    text = encryptionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // __LAST_SEEN_ON_THE_CARD_2026_10_28__ (N16, reported by a user) The Last Seen
            // switch in Settings gates whether we BROADCAST our own presence, and it always
            // did — but a peer's last connection appeared nowhere in the app. Half a
            // feature: you could choose to share it and never see anyone else's.
            Text(
                text = when (val p = com.oshi.messenger.data.model.PresenceFormat
                    .of(lastSeen, System.currentTimeMillis())) {
                    is com.oshi.messenger.data.model.PresenceFormat.Text.Unknown ->
                        stringResource(R.string.presence_unknown)
                    is com.oshi.messenger.data.model.PresenceFormat.Text.Now ->
                        stringResource(R.string.presence_now)
                    is com.oshi.messenger.data.model.PresenceFormat.Text.MinutesAgo ->
                        stringResource(R.string.presence_minutes_ago, p.minutes)
                    is com.oshi.messenger.data.model.PresenceFormat.Text.HoursAgo ->
                        stringResource(R.string.presence_hours_ago, p.hours)
                    is com.oshi.messenger.data.model.PresenceFormat.Text.At ->
                        java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                        ).format(java.util.Date(p.epochMillis))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // __ACTIONS_BEFORE_THE_KEY_2026_11_13__ (Anatole N9) Call and rename
            // come BEFORE the QR code and the key: they are what someone opens a
            // contact for, while the code and the key are for the rare moment of
            // verifying or sharing it, and were pushing the buttons below the fold.
            // __CONTAINER_AND_ITS_INK_2026_11_08__ `brandInk()`, not the
            // scheme's `primary`. An OutlinedButton defaults its content colour
            // to `colorScheme.primary`, which in the dark scheme is OSHIPurple
            // (#5616EC) — about 2.25:1 on this sheet's #1A1A1A surface, the same
            // measurement iOS already wrote down for the raw brand violet on a
            // dark ground. `brandInk` is the pair that was built for exactly
            // this: the brand in light, a lightened violet (#B899FF) in dark.
            // Applied to the Rename button too, which had the same defect
            // before these call buttons were added next to it.
            if (onCall != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { onDismiss(); onCall(false) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandInk())
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.call_voice))
                    }
                    OutlinedButton(
                        onClick = { onDismiss(); onCall(true) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandInk())
                    ) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.call_video))
                    }
                }
            }

            if (onRename != null) {
                OutlinedButton(
                    onClick = { onDismiss(); onRename() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = brandInk())
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_set_alias))
                }
            }

            val q = qr
            if (q != null) {
                Image(
                    bitmap = q.asImageBitmap(),
                    contentDescription = stringResource(R.string.accessibility_qr_code),
                    modifier = Modifier
                        .size(200.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(8.dp)
                )
            }

            // Tap to copy, same affordance the chat menu already offers.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(publicKey))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.chat_public_key_copied),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.contact_public_key),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = publicKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            ProfileLinksPinnedBar(
                profileLinksManager = profileLinksManager,
                conversationKey = publicKey
            )

            // __PROFILE_BIO_2026_11_02__ (N4) what this person says about
            // themselves. Shown only while it is still in date — the expiry is
            // applied on READ, so a device that was asleep when a bio lapsed
            // does not show it on waking.
            val bio = remember(publicKey) {
                com.oshi.messenger.data.model.PeerBioStore.visibleBio(publicKey)
            }
            if (!bio.isNullOrEmpty()) {
                Text(
                    bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.peers_done))
            }
        }
    }
}

/** Same encoder and size the rest of the app uses (NewMessageScreen:1006). */
private fun qrBitmap(text: String): Bitmap? = try {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
} catch (_: Exception) {
    null
}

/**
 * __OPEN_CONTACT_FROM_THE_LIST_2026_11_13__ (Anatole N8) "Open contact" on a
 * conversation row: the card lives inside the chat (everything it shows is loaded
 * there), so the list asks for it here and then opens the chat, which takes the
 * request once on arrival. A one-shot hand-off rather than a route argument, so
 * none of the many `chat/{publicKey}` navigations and back-stack pops change.
 */
object PendingContactCard {
    private val pending = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun request(publicKey: String) = pending.set(publicKey)

    /** True once for the chat that was asked for; clears the request either way. */
    fun consume(publicKey: String): Boolean = pending.getAndSet(null) == publicKey
}
