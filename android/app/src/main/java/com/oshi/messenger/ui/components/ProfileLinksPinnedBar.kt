package com.oshi.messenger.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.oshi.messenger.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.service.ProfileLinks
import com.oshi.messenger.service.ProfileLinksManager
import com.oshi.messenger.ui.theme.OSHIPurple

/**
 * ProfileLinksPinnedBar - Displays profile link icons in a horizontal bar
 * below the pinned message banner in the chat screen.
 *
 * Matches the iOS ProfileLinksPinnedBar behavior:
 * - Centered horizontal row of link icons
 * - Each icon opens the URL in the browser
 * - Small X button to hide the bar locally
 * - Shows only when links are visible and not hidden by user
 */
@Composable
fun ProfileLinksPinnedBar(
    profileLinksManager: ProfileLinksManager,
    conversationKey: String
) {
    // Observe state flows for reactivity
    val linksVersion by profileLinksManager.linksVersion.collectAsState()
    val contactLinks by profileLinksManager.contactLinks.collectAsState()
    val contactLinksVisible by profileLinksManager.contactLinksVisible.collectAsState()
    val hiddenByMe by profileLinksManager.hiddenByMe.collectAsState()

    // Derive visibility from the observed states (linksVersion forces recheck)
    val visibleLinks = remember(linksVersion, contactLinks, contactLinksVisible, hiddenByMe, conversationKey) {
        profileLinksManager.getVisibleLinks(conversationKey)
    }

    val context = LocalContext.current

    AnimatedVisibility(
        visible = visibleLinks != null && visibleLinks.hasAnyLink,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        visibleLinks?.let { links ->
            Surface(
                color = OSHIPurple.copy(alpha = 0.06f),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Link icons centered
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        links.filledLinks.forEachIndexed { index, (linkType, url) ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            Text(
                                text = linkType.icon,
                                fontSize = 22.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .clickable {
                                        try {
                                            val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                                                url
                                            } else {
                                                "https://$url"
                                            }
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            // Silently ignore if no browser available
                                        }
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }

                    // Close button to hide locally
                    IconButton(
                        onClick = { profileLinksManager.hideLinksFor(conversationKey) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.accessibility_hide_links),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
