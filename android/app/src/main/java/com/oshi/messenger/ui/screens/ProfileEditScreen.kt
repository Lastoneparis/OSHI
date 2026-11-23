package com.oshi.messenger.ui.screens

import android.Manifest
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.viewmodel.ProfileEditViewModel
import java.io.ByteArrayOutputStream

/**
 * ProfileEditScreen - Edit profile picture and display name
 * Matches iOS SettingsView profile editing
 */

private val BrandColor = OSHIPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    viewModel: ProfileEditViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val displayName by viewModel.displayName.collectAsState()
    val profilePicture by viewModel.profilePicture.collectAsState()
    val myPublicKey by viewModel.myPublicKey.collectAsState()
    var editedName by remember { mutableStateOf(displayName) }
    // __PROFILE_BIO_2026_11_02__ (N4) one bio with an optional end time;
    // "ephemeral or permanent" is one field, not two.
    var editedBio by remember {
        mutableStateOf(com.oshi.messenger.data.model.OwnProfileExtras.bio(context).orEmpty())
    }
    var bioEphemeral by remember {
        mutableStateOf(com.oshi.messenger.data.model.OwnProfileExtras.bioUntil(context) != null)
    }
    var showImagePicker by remember { mutableStateOf(false) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // NOTE — the "Social Links" editor that used to live on this screen was
    // DEAD. It wrote `link_telegram` / `link_twitter` / `link_instagram` /
    // `link_website` into the "profile" SharedPreferences, and nothing on either
    // platform ever read those keys: the profile-links wire format
    // (ProfileLinksManager, MainViewModel.kt:578-582,
    // MultiDeviceSyncManager.kt:688-692) is keyed `profile_link_*` and has no
    // Telegram slot at all. The app already ships a working editor —
    // ProfileLinksEditorScreen, reachable from Settings → Profile links → Edit —
    // so the second, silently-discarding one is removed rather than duplicated.
    // `ProfileEditViewModel.telegramLink/twitterLink/instagramLink/websiteLink`
    // and `updateLinks(...)` have since been DELETED (2026-08-13), and the four
    // orphaned `link_*` prefs they wrote are purged once on first construction of
    // that ViewModel.

    // Update edited name when loaded
    LaunchedEffect(displayName) {
        editedName = displayName
    }

    val scope = rememberCoroutineScope()

    // __IMAGE_CROP_2026_11_02__ (N13) The picked photo now goes to the crop
    // screen instead of straight to the profile. The window is LOCKED SQUARE:
    // the avatar is drawn in a circle everywhere in the app, so a free crop
    // would be cropped a second time — off-centre, and without being asked.
    var cropCandidate by remember { mutableStateOf<Bitmap?>(null) }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // OFF THE MAIN THREAD. This ran inline in the launcher callback, so a large
            // photo blocked the UI through both the decode AND the compress. Subsampling
            // alone would have shortened the freeze, not removed it.
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val decoded = loadBitmapFromUri(context, it) ?: return@withContext null
                    // Upright BEFORE the crop window is drawn over it.
                    com.oshi.messenger.ui.components.ImageOrientation.upright(context, it, decoded)
                }
                if (bmp != null) cropCandidate = bmp
            }
        }
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        // A preview capture is already upright and already a Bitmap; it only
        // needs the same crop step as a gallery pick.
        bitmap?.let { bmp -> cropCandidate = bmp }
    }

    cropCandidate?.let { candidate ->
        com.oshi.messenger.ui.components.ImageCropDialog(
            source = candidate,
            aspect = 1f,
            onCancel = { cropCandidate = null },
            onCropped = { cropped ->
                cropCandidate = null
                selectedBitmap = cropped
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { compressBitmap(cropped) }.getOrNull()
                    }
                    if (bytes != null) viewModel.updateProfilePicture(bytes)
                }
            },
        )
    }

    // Camera permission
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraLauncher.launch(null)
        } else {
            Toast.makeText(context, context.getString(R.string.profile_camera_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back_button))
                    }
                },
                title = { Text(stringResource(R.string.profile_edit_title)) },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.updateDisplayName(editedName)
                            // __PROFILE_BIO_2026_11_02__ (N4) 24 h for an
                            // ephemeral bio. Written before the name update's
                            // broadcast so both ride the SAME profile payload.
                            com.oshi.messenger.data.model.OwnProfileExtras.set(
                                context,
                                editedBio,
                                if (bioEphemeral) System.currentTimeMillis() + 24 * 60 * 60 * 1000L else null,
                                null,
                            )
                            onBack()
                        }
                    ) {
                        Text(
                            stringResource(R.string.save),
                            color = brandInk(),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Profile Picture
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                val currentBitmap = selectedBitmap ?: profilePicture?.let { bytes ->
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.profile_picture),
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(3.dp, BrandColor, CircleShape)
                            .clickable { showImagePicker = true },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Default avatar
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(BrandColor.copy(alpha = 0.15f))
                            .border(3.dp, BrandColor, CircleShape)
                            .clickable { showImagePicker = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(R.string.accessibility_default_avatar),
                            modifier = Modifier.size(60.dp),
                            tint = brandInk()
                        )
                    }
                }

                // Edit badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(BrandColor)
                        .clickable { showImagePicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.accessibility_change_picture),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = { showImagePicker = true }) {
                Text(
                    stringResource(R.string.profile_change_photo),
                    color = brandInk()
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Display Name
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_display_name),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandColor,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.profile_name_placeholder)) }
                    )

                    // __PROFILE_BIO_2026_11_02__ (N4)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.profile_bio_label),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editedBio,
                        onValueChange = {
                            // Capped at the wire limit as you type, so the text
                            // that is saved is the text that was shown.
                            editedBio = com.oshi.messenger.data.model.ProfileBioWire
                                .sanitize(it)?.takeIf { _ -> it.isNotBlank() } ?: it.take(0)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandColor,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        maxLines = 3,
                        supportingText = {
                            Text("${editedBio.length} / ${com.oshi.messenger.data.model.ProfileBioWire.MAX_CODE_POINTS}")
                        },
                        placeholder = { Text(stringResource(R.string.profile_bio_placeholder)) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(checked = bioEphemeral, onCheckedChange = { bioEphemeral = it })
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.profile_bio_ephemeral))
                            // Says "hidden after", never "deleted": the peer holds
                            // the text and nothing here can reach their device.
                            Text(
                                stringResource(R.string.profile_bio_ephemeral_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Public Key (read-only)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.profile_public_key),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        IconButton(
                            onClick = {
                                copyToClipboard(context, myPublicKey)
                                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.accessibility_copy_key),
                                tint = brandInk(),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = myPublicKey,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(24.dp))

            // Remove photo button (if has photo)
            if (profilePicture != null || selectedBitmap != null) {
                OutlinedButton(
                    onClick = {
                        selectedBitmap = null
                        viewModel.removeProfilePicture()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.Red
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color.Red.copy(alpha = 0.5f))
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.profile_remove_photo))
                }
            }
        }
    }

    // Image picker dialog
    if (showImagePicker) {
        OshiAlertDialog(
            onDismissRequest = { showImagePicker = false },
            title = { Text(stringResource(R.string.profile_choose_photo)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.profile_take_photo)) },
                        leadingContent = {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = BrandColor)
                        },
                        modifier = Modifier.clickable {
                            showImagePicker = false
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.profile_choose_gallery)) },
                        leadingContent = {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = BrandColor)
                        },
                        modifier = Modifier.clickable {
                            showImagePicker = false
                            imagePickerLauncher.launch("image/*")
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImagePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 🔴 THIS USED TO DECODE THE PICKED PHOTO AT FULL RESOLUTION, AND CATCH THE WRONG THING.
 *
 * Two defects in nine lines:
 *
 *  1. No bounds pass and no `inSampleSize`, so a modern camera photo was materialised
 *     whole. A 50 MP image is ~200 MB as ARGB_8888 — far past the heap of a phone with
 *     no `largeHeap`. `compressBitmap` only shrinks to 512 px AFTER the full bitmap
 *     already exists, so it cannot help.
 *  2. `catch (e: Exception)` does NOT catch `OutOfMemoryError`, which is an `Error`.
 *     The very failure this code was most likely to hit passed straight through it.
 *
 * Settings → Edit profile → change photo is an ordinary first-run action, which made
 * this the most likely everyday crash in the build. The app already had the right
 * pattern in `NewMessageViewModel.loadImageBytes` — bounds first, then a power-of-two
 * `inSampleSize`, then decode — it simply was not applied here.
 *
 * Still returns null on failure, so every caller behaves as before.
 */
private fun loadBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 1920): Bitmap? {
    return try {
        // Pass 1: read the dimensions only. `inJustDecodeBounds` allocates nothing.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        // Pass 2: decode subsampled. `||` not `&&` — with `&&` a 4032x3024 photo never
        // samples at all, because the SHORT side stops the loop while the long side is
        // still oversized.
        var sample = 1
        while (w / (sample * 2) >= maxDimension || h / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(
                stream, null,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    } catch (e: OutOfMemoryError) {
        // An Error, not an Exception. The catch below would never have seen it.
        Log.w("ProfileEdit", "photo too large to decode even subsampled: ${e.message}")
        null
    } catch (e: Exception) {
        Log.w("ProfileEdit", "could not read the picked photo: ${e.message}")
        null
    }
}

// 512px longest edge / JPEG 80 — big enough to stay sharp in the 120dp edit-screen
// avatar and the ~150dp call-screen avatar (CallScreen.kt, VoiceCallScreen.kt), small
// enough that a profile picture sent over a LoRa link stays a reasonable size. Matches
// the iOS target (`SettingsView.resizeProfileImage`) so the same photo looks the same
// on whichever side receives it.
//
// `MessageRepository.setMyProfilePicture` used to re-decode and re-compress this
// output down to 200x200/quality 50 "to match iOS" before persisting/broadcasting it —
// a second lossy pass that threw away this resize's resolution/quality bump. Fixed to
// 512/80 there too; keep the two in sync if either changes.
private fun compressBitmap(bitmap: Bitmap, maxSize: Int = 512): ByteArray {
    // Resize if too large
    val ratio = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
    val newWidth = (bitmap.width * ratio).toInt()
    val newHeight = (bitmap.height * ratio).toInt()

    val scaledBitmap = if (ratio < 1) {
        Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    } else {
        bitmap
    }

    // Compress to JPEG
    val outputStream = ByteArrayOutputStream()
    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
    return outputStream.toByteArray()
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText(context.getString(R.string.clipboard_label_oshi_key), text)
    clipboard.setPrimaryClip(clip)
}
