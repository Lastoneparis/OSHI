package com.oshi.messenger.ui.components

import android.graphics.BitmapFactory
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi

// Brand color
private val BrandColor = Color(0xFF5616EC)

/**
 * Wallpaper types matching iOS
 */
sealed class ChatWallpaper {
    data object Default : ChatWallpaper()
    data class SolidColor(val color: Color) : ChatWallpaper()
    data class Gradient(val colors: List<Color>, val name: String) : ChatWallpaper()
    data class EmojiPattern(val emoji: String, val backgroundColor: Color) : ChatWallpaper()

    /**
     * One of the six animated OSHI backdrops. [styleId] is the ordinal of
     * [OshiDynamicStyle], which is also the wire value iOS encodes, so the two
     * catalogues must stay in the same order.
     *
     * 🔴 THIS CASE WAS MISSING, AND iOS'S DEFAULT IS ONE OF THEM. iOS ships
     * `.oshiDynamic(constellation)` — Mesh — as its app-wide default
     * (`ChatWallpaperManager.swift:31`). Android's decoder had no branch for
     * `{"oshiDynamic":{"_0":n}}`, so it fell to `else -> Default`: every iPhone
     * on the shipped default that synced a wallpaper handed its Android peer
     * FLAT BEIGE, and nothing said so.
     */
    data class OshiDynamic(val styleId: Int) : ChatWallpaper()
    data class ImageWallpaper(val imageData: ByteArray) : ChatWallpaper() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageWallpaper) return false
            return imageData.contentEquals(other.imageData)
        }
        override fun hashCode(): Int = imageData.contentHashCode()
    }
}

/**
 * Preset solid colors (matching iOS)
 */
val presetColors = listOf(
    Color(0xFFE8E0D5), // Cream
    Color(0xFFD4E4ED), // Light Blue
    Color(0xFFD5ECD4), // Mint
    Color(0xFFF5D5D8), // Pink
    Color(0xFFE5D5F5), // Lavender
    Color(0xFFF5ECD5), // Peach
    Color(0xFF2C3E50), // Dark Blue
    Color(0xFF1A1A2E), // Dark Purple
    Color(0xFF0D0D0D), // Black
    Color(0xFFFFFFFF), // White
)

/**
 * Preset gradients (matching iOS)
 */
val presetGradients = listOf(
    ChatWallpaper.Gradient(listOf(Color(0xFFFF6B6B), Color(0xFFFFE66D)), "Sunset"),
    ChatWallpaper.Gradient(listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)), "Ocean"),
    ChatWallpaper.Gradient(listOf(Color(0xFF43E97B), Color(0xFF38F9D7)), "Forest"),
    ChatWallpaper.Gradient(listOf(Color(0xFF0F0C29), Color(0xFF302B63)), "Night Sky"),
    ChatWallpaper.Gradient(listOf(Color(0xFF00C9FF), Color(0xFF92FE9D)), "Aurora"),
    ChatWallpaper.Gradient(listOf(Color(0xFFFF416C), Color(0xFFFF4B2B)), "Fire"),
)

/**
 * Preset emoji patterns
 */
val presetEmojiPatterns = listOf(
    ChatWallpaper.EmojiPattern("💬", Color(0xFFE8E0D5)),
    ChatWallpaper.EmojiPattern("❤️", Color(0xFFF5D5D8)),
    ChatWallpaper.EmojiPattern("⭐", Color(0xFFF5ECD5)),
    ChatWallpaper.EmojiPattern("🌸", Color(0xFFE5D5F5)),
    ChatWallpaper.EmojiPattern("🌿", Color(0xFFD5ECD4)),
    ChatWallpaper.EmojiPattern("🔒", Color(0xFF2C3E50)),
)

/**
 * Wallpaper Picker Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperPickerSheet(
    conversationId: String,
    currentWallpaper: ChatWallpaper,
    onSelectWallpaper: (ChatWallpaper) -> Unit,
    onDismiss: () -> Unit,
    // Optional so the sheet stays usable where there is nothing to apply
    // globally (and so existing 4-argument call sites keep compiling). When it
    // is supplied, the sheet grows a confirmed "apply everywhere" action under
    // the per-chat save button — the only entry point to the app-wide default.
    onSetAsDefaultForAllChats: ((ChatWallpaper) -> Unit)? = null
) {
    // Opens on OSHI, as iOS does (`ChatWallpaperManager.swift:730`): the animated
    // backdrops are the app's own look, and the tab that shows them should not be
    // the one nobody scrolls to.
    var selectedTab by remember { mutableStateOf(0) }
    // Overwriting every conversation's background is not undoable, so it asks first.
    var confirmApplyToAllChats by remember { mutableStateOf(false) }
    val tabs = listOf(
        stringResource(R.string.wallpaper_tab_oshi),
        stringResource(R.string.wallpaper_tab_colors),
        stringResource(R.string.wallpaper_tab_gradients),
        stringResource(R.string.wallpaper_tab_emoji),
        stringResource(R.string.wallpaper_tab_photo)
    )
    val context = LocalContext.current

    // Image picker for custom wallpaper
    val wallpaperImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val imageData = inputStream?.readBytes()
                inputStream?.close()
                if (imageData != null) {
                    onSelectWallpaper(ChatWallpaper.ImageWallpaper(imageData))
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.wallpaper_load_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 🔧 P2 FIX (device walkthrough #2, 2026-08-13): the swatch grid ran under
        // the gesture bar, so the last visible row was cut (93 px of 126 px). The
        // grids scroll internally, so the content was reachable — but the sheet had
        // no bottom safe-area inset, which is what made the cut look like a bug.
        // 🔴 SHORT-WINDOW FIX (2026-08-23). The safe-area inset above fixed the
        // CUT, not the OVERFLOW. This sheet is ~520 dp of fixed content — a
        // 150 dp preview, a tab row, a 200 dp grid and the "Done" button — and it
        // does not scroll. The grids scroll INTERNALLY, so on a landscape phone
        // the swatches stay reachable and the button that APPLIES them does not:
        // the sheet looks fully functional right up to the moment you try to
        // finish. One `verticalScroll` is the whole fix.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.wallpaper_chat_wallpaper),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // __WALLPAPER_TONE_2026_09_24__ this chat's tone (light / dark /
            // automatic). Applied on tap, like the wallpaper tiles below.
            val chatTone = WallpaperTonePrefs.tone(context, conversationId)
            val previewDark = WallpaperToneResolver.resolve(
                currentWallpaper, chatTone, isDarkScheme()
            ).contentDark

            // Preview
            WithChatScheme(dark = previewDark) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        when (currentWallpaper) {
                            is ChatWallpaper.Default -> Modifier.background(Color(0xFFE8E0D5))
                            is ChatWallpaper.SolidColor -> Modifier.background(currentWallpaper.color)
                            is ChatWallpaper.Gradient -> Modifier.background(
                                Brush.linearGradient(currentWallpaper.colors)
                            )
                            is ChatWallpaper.EmojiPattern -> Modifier.background(currentWallpaper.backgroundColor)
                            is ChatWallpaper.OshiDynamic -> Modifier
                            is ChatWallpaper.ImageWallpaper -> Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // The preview animates, unlike the tiles: this is the one place
                // the user is deciding whether they like the motion.
                if (currentWallpaper is ChatWallpaper.OshiDynamic) {
                    OshiDynamicWallpaperView(
                        style = OshiDynamicStyle.fromId(currentWallpaper.styleId)
                            ?: OshiDynamicStyle.DEFAULT,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Image wallpaper background
                if (currentWallpaper is ChatWallpaper.ImageWallpaper) {
                    val bitmap = remember(currentWallpaper.imageData) {
                        BitmapFactory.decodeByteArray(currentWallpaper.imageData, 0, currentWallpaper.imageData.size)
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                // Sample chat bubbles for preview
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Received message
                    Box(
                        modifier = Modifier
                            .align(Alignment.Start)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Text(stringResource(R.string.wallpaper_preview_incoming), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    // Sent message
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .background(BrandColor, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        Text(stringResource(R.string.wallpaper_preview_outgoing), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            }

            // Tone row — only for the backdrops that have a light and a dark version.
            if (currentWallpaper is ChatWallpaper.OshiDynamic) {
                Spacer(modifier = Modifier.height(12.dp))
                WallpaperToneRow(
                    style = OshiDynamicStyle.fromId(currentWallpaper.styleId) ?: OshiDynamicStyle.DEFAULT,
                    selected = chatTone,
                    cardHeight = 76.dp
                ) { WallpaperTonePrefs.setTone(context, conversationId, it) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab bar
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content based on selected tab
            when (selectedTab) {
                0 -> {
                    // 🌌 The six animated OSHI backdrops.
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(OshiDynamicStyle.entries.toList()) { style ->
                            val selected = (currentWallpaper as? ChatWallpaper.OshiDynamic)
                                ?.styleId == style.ordinal
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        onSelectWallpaper(ChatWallpaper.OshiDynamic(style.ordinal))
                                    }
                            ) {
                                // A STILL frame in the picker: six looping canvases
                                // animating at once is a lot of work for a chooser,
                                // and iOS renders its tiles still for the same
                                // reason (`OSHIWallpapers.swift:53`).
                                OshiDynamicWallpaperThumbnail(
                                    style = style,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                )
                                Text(
                                    text = style.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // Solid colors
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(200.dp)
                    ) {
                        // Default option
                        item {
                            ColorOption(
                                color = Color(0xFFE8E0D5),
                                isSelected = currentWallpaper is ChatWallpaper.Default,
                                label = stringResource(R.string.wallpaper_default)
                            ) {
                                onSelectWallpaper(ChatWallpaper.Default)
                            }
                        }
                        items(presetColors) { color ->
                            ColorOption(
                                color = color,
                                isSelected = currentWallpaper is ChatWallpaper.SolidColor &&
                                        currentWallpaper.color == color
                            ) {
                                onSelectWallpaper(ChatWallpaper.SolidColor(color))
                            }
                        }
                    }
                }
                2 -> {
                    // Gradients
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(presetGradients) { gradient ->
                            GradientOption(
                                gradient = gradient,
                                isSelected = currentWallpaper is ChatWallpaper.Gradient &&
                                        currentWallpaper.name == gradient.name
                            ) {
                                onSelectWallpaper(gradient)
                            }
                        }
                    }
                }
                3 -> {
                    // Emoji patterns
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(presetEmojiPatterns) { pattern ->
                            EmojiOption(
                                pattern = pattern,
                                isSelected = currentWallpaper is ChatWallpaper.EmojiPattern &&
                                        currentWallpaper.emoji == pattern.emoji
                            ) {
                                onSelectWallpaper(pattern)
                            }
                        }
                    }
                }
                4 -> {
                    // Custom photo wallpaper
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (currentWallpaper is ChatWallpaper.ImageWallpaper) {
                            // Show current image with change option
                            val bitmap = remember(currentWallpaper.imageData) {
                                BitmapFactory.decodeByteArray(
                                    currentWallpaper.imageData, 0, currentWallpaper.imageData.size
                                )
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { wallpaperImagePicker.launch("image/*") },
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { wallpaperImagePicker.launch("image/*") }) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.wallpaper_change_photo))
                                }
                                OutlinedButton(onClick = { onSelectWallpaper(ChatWallpaper.Default) }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.wallpaper_remove))
                                }
                            }
                        } else {
                            // No image selected yet
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = brandInk()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.wallpaper_choose_photo_gallery),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { wallpaperImagePicker.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandColor)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.wallpaper_choose_photo))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandColor)
            ) {
                Text(stringResource(R.string.wallpaper_save_chat))
            }

            // 🌍 The app-wide default. WallpaperManager.setGlobalWallpaper existed
            // from the start with no caller in the UI, so the only way to put one
            // background behind every chat was to open this sheet once per contact.
            // It sits UNDER the per-chat save — same column, secondary weight — so
            // it reads as the deliberate, wider version of what the user just did,
            // not as a sixth tab and not as a second primary button.
            if (onSetAsDefaultForAllChats != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { confirmApplyToAllChats = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Wallpaper,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.wallpaper_set_default_all))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (confirmApplyToAllChats && onSetAsDefaultForAllChats != null) {
        OshiAlertDialog(
            onDismissRequest = { confirmApplyToAllChats = false },
            icon = { Icon(Icons.Default.Wallpaper, contentDescription = null) },
            title = { Text(stringResource(R.string.wallpaper_set_default_confirm_title)) },
            text = { Text(stringResource(R.string.wallpaper_set_default_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmApplyToAllChats = false
                    onSetAsDefaultForAllChats?.invoke(currentWallpaper)
                    WallpaperTonePrefs.setDefaultTone(
                        context, WallpaperTonePrefs.tone(context, conversationId)
                    )
                    // The preview already shows this wallpaper, so nothing on screen
                    // would move on success — without a word back, a working button
                    // gets reported as a dead one.
                    Toast.makeText(
                        context,
                        context.getString(R.string.wallpaper_set_default_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(stringResource(R.string.wallpaper_set_default_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmApplyToAllChats = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ColorOption(
    color: Color,
    isSelected: Boolean,
    label: String? = null,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, BrandColor, CircleShape)
                    } else {
                        Modifier.border(1.dp, Color.Gray.copy(alpha = 0.3f), CircleShape)
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        label?.let {
            Text(
                it,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GradientOption(
    gradient: ChatWallpaper.Gradient,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(gradient.colors))
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, BrandColor, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            gradient.name,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmojiOption(
    pattern: ChatWallpaper.EmojiPattern,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(pattern.backgroundColor)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, BrandColor, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(pattern.emoji, fontSize = 24.sp)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .background(BrandColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

/**
 * Chat background composable that displays the selected wallpaper
 */
@Composable
fun ChatWallpaperBackground(
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier
) {
    // __OLED_GLASS_2026_11_16__ OLED black keeps the user's wallpaper — it is
    // their choice — but on a black ground and dimmed so its AVERAGE luminance
    // stays near-black. A dark wallpaper is left alone; a white one is pulled
    // down to a dim grey, never erased. See [OledWallpaperDim].
    val oled = com.oshi.messenger.ui.theme.isOledScheme()
    val oledDim = rememberOledWallpaperDim(wallpaper)
    Box(
        modifier = modifier
            .then(if (oled) Modifier.background(Color.Black) else Modifier)
            .then(
            when (wallpaper) {
                is ChatWallpaper.Default -> Modifier.background(Color(0xFFE8E0D5))
                is ChatWallpaper.SolidColor -> Modifier.background(wallpaper.color)
                is ChatWallpaper.Gradient -> Modifier.background(
                    Brush.linearGradient(wallpaper.colors)
                )
                is ChatWallpaper.EmojiPattern -> Modifier.background(wallpaper.backgroundColor)
                is ChatWallpaper.OshiDynamic -> Modifier
                is ChatWallpaper.ImageWallpaper -> Modifier
            }
        )
    ) {
        if (wallpaper is ChatWallpaper.OshiDynamic) {
            OshiDynamicWallpaperView(
                style = OshiDynamicStyle.fromId(wallpaper.styleId) ?: OshiDynamicStyle.DEFAULT,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (wallpaper is ChatWallpaper.ImageWallpaper) {
            val bitmap = remember(wallpaper.imageData) {
                BitmapFactory.decodeByteArray(wallpaper.imageData, 0, wallpaper.imageData.size)
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // (N18) A custom photo wallpaper was drawn at full brightness in
            // both themes — the dark-mode toggle changed everything else
            // around it but left someone's own photo as bright as in light
            // mode. A flat, uniform scrim (distinct from the EDGE_SCRIM
            // below, which only darkens the top/bottom seam) — dark enough
            // to read as "this screen is in dark mode" without turning the
            // photo unrecognisable.
            if (isDarkScheme() && !oled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = DARK_MODE_WALLPAPER_SCRIM_ALPHA))
                )
            }
        }
        if (oledDim > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = oledDim))
            )
        }

        // __WALLPAPER_EDGES_FADE_2026_10_28__ (N17, reported by a user: "si un fond d'écran
        // est ajouté pour une conversation un dégradé progressif sur les bordures hautes et
        // basses")
        //
        // A photograph runs edge to edge under the top bar and the composer, so a busy
        // corner of it sits directly behind their controls. The scrim fades the wallpaper
        // into the chrome at both ends.
        //
        // ⚠️ IT DARKENS, NEVER LIGHTENS, AND ONLY AT THE EDGES — and that is a contrast
        // decision, not a taste one. `ChatPaletteContrastTest` measures every meta ink
        // (`Oshi.Chat.metaInk`, the timestamp and transport row) against the WORST wallpaper
        // ground it can sit on. Those inks are chosen to clear 4.5:1 on that ground; a
        // lightening scrim would move the ground the other way and could drop a token below
        // the floor with nothing to catch it, because the test measures the palette, not
        // this overlay.
        //
        // Applied for a picture only. A solid colour or a gradient is already flat behind
        // the chrome, and fading it would read as a smudge rather than a blend.
        if (wallpaper is ChatWallpaper.ImageWallpaper || wallpaper is ChatWallpaper.OshiDynamic) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            // Stops rather than a plain top-to-bottom ramp: the middle must
                            // stay untouched, or the whole wallpaper is veiled and the
                            // person who chose a photo no longer has one.
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = EDGE_SCRIM_ALPHA),
                                EDGE_SCRIM_SPAN to Color.Transparent,
                                1f - EDGE_SCRIM_SPAN to Color.Transparent,
                                1.00f to Color.Black.copy(alpha = EDGE_SCRIM_ALPHA),
                            )
                        )
                    )
            )
        }
    }
}

/**
 * __WALLPAPER_EDGES_FADE_2026_10_28__ How far the edge fade reaches, as a fraction of the
 * chat's height, and how dark it gets at the very edge.
 *
 * Deliberately small on both counts. The request is for the wallpaper to MEET the chrome
 * without a hard seam, not for a vignette: past about a tenth of the height the fade stops
 * reading as an edge and starts reading as a filter over someone's photograph.
 */
private const val EDGE_SCRIM_SPAN = 0.10f

/** (N18) Uniform dark-mode darkening for a custom photo wallpaper. */
internal const val DARK_MODE_WALLPAPER_SCRIM_ALPHA = 0.35f

/**
 * __OLED_GLASS_2026_11_16__ The black scrim alpha for [wallpaper] under OLED
 * black (0 otherwise). A photo is measured off the main thread; until the
 * measurement lands it is held at [OledWallpaperDim.MAX] so a bright photo can
 * never flash at full brightness on a black screen.
 */
@Composable
fun rememberOledWallpaperDim(wallpaper: ChatWallpaper): Float {
    if (!com.oshi.messenger.ui.theme.isOledScheme()) return 0f
    return when (wallpaper) {
        is ChatWallpaper.ImageWallpaper -> {
            val lum by produceState(initialValue = Double.NaN, wallpaper.imageData) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    OledWallpaperDim.imageLuminance(wallpaper.imageData)
                }
            }
            if (lum.isNaN()) OledWallpaperDim.MAX
            else OledWallpaperDim.scrimAlpha(lum, floor = DARK_MODE_WALLPAPER_SCRIM_ALPHA)
        }
        is ChatWallpaper.OshiDynamic -> 0f // already drawn on a black ground in OLED
        else -> OledWallpaperDim.scrimAlpha(OledWallpaperDim.flatLuminance(wallpaper))
    }
}

/** The OLED scrim as a layer, for a wallpaper drawn outside [ChatWallpaperBackground]. */
@Composable
fun OledWallpaperDimLayer(wallpaper: ChatWallpaper, modifier: Modifier = Modifier) {
    val a = rememberOledWallpaperDim(wallpaper)
    if (a > 0f) Box(modifier.background(Color.Black.copy(alpha = a)))
}

/**
 * __OLED_GLASS_2026_11_16__ How much black to lay over a user-picked wallpaper
 * under OLED black. Pure; held by `OledGlassTest`.
 *
 * A black scrim of alpha `a` scales linear luminance by `(1 - a)`, so the alpha
 * that brings a wallpaper of mean luminance `L` down to [TARGET] is
 * `1 - TARGET / L`. Clamped to [MAX] so a white wallpaper ends as a dim grey
 * (L ≈ 0.08, about #4F4F4F) rather than vanishing — the user picked it, and it
 * must still be recognisably theirs.
 */
object OledWallpaperDim {
    /** Mean relative luminance the dimmed wallpaper should not exceed (≈ #383838). */
    const val TARGET = 0.04
    const val MAX = 0.92f

    fun scrimAlpha(meanLuminance: Double, floor: Float = 0f): Float {
        if (meanLuminance.isNaN() || meanLuminance <= 0.0) return floor
        val a = (1.0 - TARGET / meanLuminance).toFloat()
        return a.coerceIn(floor, MAX).coerceAtLeast(floor)
    }

    /** Mean luminance of a flat wallpaper (colour, gradient, pattern ground). */
    fun flatLuminance(wallpaper: ChatWallpaper): Double = when (wallpaper) {
        is ChatWallpaper.Default -> com.oshi.messenger.ui.theme.Contrast.luminance(Color(0xFFE8E0D5))
        is ChatWallpaper.SolidColor -> com.oshi.messenger.ui.theme.Contrast.luminance(wallpaper.color)
        is ChatWallpaper.Gradient -> wallpaper.colors.map { com.oshi.messenger.ui.theme.Contrast.luminance(it) }
            .average().takeUnless { it.isNaN() } ?: 0.0
        is ChatWallpaper.EmojiPattern -> com.oshi.messenger.ui.theme.Contrast.luminance(wallpaper.backgroundColor)
        is ChatWallpaper.OshiDynamic -> 0.0
        is ChatWallpaper.ImageWallpaper -> imageLuminance(wallpaper.imageData)
    }

    /** Mean luminance of a photo, measured on a 24 × 24 thumbnail (cheap, once per image). */
    fun imageLuminance(bytes: ByteArray): Double = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return 0.5
        val small = android.graphics.Bitmap.createScaledBitmap(src, 24, 24, true)
        var sum = 0.0
        for (y in 0 until 24) for (x in 0 until 24) {
            sum += com.oshi.messenger.ui.theme.Contrast.luminance(Color(small.getPixel(x, y)))
        }
        sum / (24 * 24)
    }.getOrDefault(0.5)
}
private const val EDGE_SCRIM_ALPHA = 0.28f

/**
 * Serialize ChatWallpaper to ByteArray (JSON) for sync with iOS.
 * Matches iOS Swift Codable format for cross-platform compatibility.
 */
fun ChatWallpaper.toSyncBytes(): ByteArray {
    val json = when (this) {
        is ChatWallpaper.Default -> """{"none":{}}"""
        is ChatWallpaper.SolidColor -> {
            val r = color.red.toDouble()
            val g = color.green.toDouble()
            val b = color.blue.toDouble()
            val a = color.alpha.toDouble()
            """{"solidColor":{"_0":{"red":$r,"green":$g,"blue":$b,"alpha":$a}}}"""
        }
        is ChatWallpaper.Gradient -> {
            // Try to find matching preset index
            val presetIndex = presetGradients.indexOfFirst { it.name == name }
            if (presetIndex >= 0) {
                """{"gradientPreset":{"_0":$presetIndex}}"""
            } else if (colors.size >= 2) {
                val c1 = colors[0]
                val c2 = colors[1]
                """{"gradient":{"_0":{"red":${c1.red.toDouble()},"green":${c1.green.toDouble()},"blue":${c1.blue.toDouble()},"alpha":${c1.alpha.toDouble()}},"_1":{"red":${c2.red.toDouble()},"green":${c2.green.toDouble()},"blue":${c2.blue.toDouble()},"alpha":${c2.alpha.toDouble()}}}}"""
            } else {
                """{"none":{}}"""
            }
        }
        is ChatWallpaper.EmojiPattern -> {
            val index = presetEmojiPatterns.indexOfFirst { it.emoji == emoji }
            if (index >= 0) {
                """{"emojiPattern":{"_0":$index}}"""
            } else {
                """{"none":{}}"""
            }
        }
        is ChatWallpaper.OshiDynamic -> """{"oshiDynamic":{"_0":$styleId}}"""
        is ChatWallpaper.ImageWallpaper -> {
            // Image data is sent separately as customImageBase64 in the sync payload
            """{"customImage":{"_0":"android_custom.jpg"}}"""
        }
    }
    return json.toByteArray(Charsets.UTF_8)
}

/**
 * Deserialize ByteArray (JSON) to ChatWallpaper.
 * Handles both iOS Swift Codable format and Android format.
 */
fun chatWallpaperFromBytes(data: ByteArray): ChatWallpaper {
    return try {
        val jsonStr = String(data, Charsets.UTF_8)
        val json = org.json.JSONObject(jsonStr)

        when {
            json.has("none") -> ChatWallpaper.Default

            json.has("solidColor") -> {
                val colorObj = json.getJSONObject("solidColor").getJSONObject("_0")
                val r = colorObj.getDouble("red").toFloat()
                val g = colorObj.getDouble("green").toFloat()
                val b = colorObj.getDouble("blue").toFloat()
                val a = colorObj.optDouble("alpha", 1.0).toFloat()
                ChatWallpaper.SolidColor(Color(r, g, b, a))
            }

            json.has("gradient") -> {
                val gradObj = json.getJSONObject("gradient")
                val c1 = gradObj.getJSONObject("_0")
                val c2 = gradObj.getJSONObject("_1")
                val color1 = Color(
                    c1.getDouble("red").toFloat(),
                    c1.getDouble("green").toFloat(),
                    c1.getDouble("blue").toFloat(),
                    c1.optDouble("alpha", 1.0).toFloat()
                )
                val color2 = Color(
                    c2.getDouble("red").toFloat(),
                    c2.getDouble("green").toFloat(),
                    c2.getDouble("blue").toFloat(),
                    c2.optDouble("alpha", 1.0).toFloat()
                )
                ChatWallpaper.Gradient(listOf(color1, color2), "Custom")
            }

            json.has("gradientPreset") -> {
                val index = json.getJSONObject("gradientPreset").getInt("_0")
                if (index in presetGradients.indices) {
                    presetGradients[index]
                } else ChatWallpaper.Default
            }

            json.has("emojiPattern") -> {
                val index = json.getJSONObject("emojiPattern").getInt("_0")
                if (index in presetEmojiPatterns.indices) {
                    presetEmojiPatterns[index]
                } else ChatWallpaper.Default
            }

            json.has("oshiDynamic") -> {
                val id = json.getJSONObject("oshiDynamic").getInt("_0")
                // An id this build does not know is NOT "no wallpaper": a newer
                // iOS style still means the peer chose a dynamic backdrop, so
                // fall back to the dynamic default rather than to flat beige.
                if (OshiDynamicStyle.fromId(id) != null) ChatWallpaper.OshiDynamic(id)
                else ChatWallpaper.OshiDynamic(OshiDynamicStyle.DEFAULT.ordinal)
            }

            json.has("customImage") -> {
                // Custom image — actual bytes are stored separately via customImageBase64
                ChatWallpaper.Default
            }

            else -> ChatWallpaper.Default
        }
    } catch (e: Exception) {
        // Not valid JSON — check if it's raw image data (JPEG/PNG)
        if (data.size > 100 && isImageData(data)) {
            ChatWallpaper.ImageWallpaper(data)
        } else {
            ChatWallpaper.Default
        }
    }
}

/** Check if bytes look like image data (JPEG or PNG magic bytes) */
private fun isImageData(data: ByteArray): Boolean {
    if (data.size < 4) return false
    // JPEG: FF D8 FF
    if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) return true
    // PNG: 89 50 4E 47
    if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) return true
    return false
}

// Helper extension
private fun Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.299f * r + 0.587f * g + 0.114f * b
}
