package com.oshi.messenger.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileLinksEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("oshi_profile_links", android.content.Context.MODE_PRIVATE) }

    // Pre-fill social link fields with their canonical URL prefix when no value
    // is stored yet, so the user only has to type their handle.
    fun loadOrPrefix(prefKey: String, prefix: String): String =
        prefs.getString(prefKey, "")?.takeIf { it.isNotEmpty() } ?: prefix

    var website by remember { mutableStateOf(prefs.getString("profile_link_website", "") ?: "") }
    var linkedin by remember { mutableStateOf(loadOrPrefix("profile_link_linkedin", "https://www.linkedin.com/in/")) }
    var twitter by remember { mutableStateOf(loadOrPrefix("profile_link_twitter", "https://x.com/")) }
    var instagram by remember { mutableStateOf(loadOrPrefix("profile_link_instagram", "https://www.instagram.com/")) }
    var github by remember { mutableStateOf(loadOrPrefix("profile_link_github", "https://github.com/")) }

    val savedMessage = stringResource(R.string.profile_links_saved)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_links_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            ProfileLinkField(
                value = website,
                onValueChange = { website = it },
                label = stringResource(R.string.profile_links_website),
                placeholder = stringResource(R.string.profile_links_url_placeholder),
                icon = Icons.Default.Language,
                urlPrefix = null
            )

            ProfileLinkField(
                value = linkedin,
                onValueChange = { linkedin = it },
                label = stringResource(R.string.profile_links_linkedin),
                placeholder = stringResource(R.string.profile_links_url_placeholder),
                icon = Icons.Default.Work,
                urlPrefix = "https://www.linkedin.com/in/"
            )

            ProfileLinkField(
                value = twitter,
                onValueChange = { twitter = it },
                label = stringResource(R.string.profile_links_twitter),
                placeholder = stringResource(R.string.profile_links_url_placeholder),
                icon = Icons.Default.Tag,
                urlPrefix = "https://x.com/"
            )

            ProfileLinkField(
                value = instagram,
                onValueChange = { instagram = it },
                label = stringResource(R.string.profile_links_instagram),
                placeholder = stringResource(R.string.profile_links_url_placeholder),
                icon = Icons.Default.CameraAlt,
                urlPrefix = "https://www.instagram.com/"
            )

            ProfileLinkField(
                value = github,
                onValueChange = { github = it },
                label = stringResource(R.string.profile_links_github),
                placeholder = stringResource(R.string.profile_links_url_placeholder),
                icon = Icons.Default.Code,
                urlPrefix = "https://github.com/"
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    // If the user left a field as the bare prefix (no handle typed),
                    // persist it as empty so we don't store a half-built URL.
                    fun normalize(value: String, prefix: String?): String =
                        if (prefix != null && value.trim() == prefix) "" else value
                    prefs.edit()
                        .putString("profile_link_website", website)
                        .putString("profile_link_linkedin", normalize(linkedin, "https://www.linkedin.com/in/"))
                        .putString("profile_link_twitter", normalize(twitter, "https://x.com/"))
                        .putString("profile_link_instagram", normalize(instagram, "https://www.instagram.com/"))
                        .putString("profile_link_github", normalize(github, "https://github.com/"))
                        // Stamp last-edit time so multi-device sync can resolve
                        // conflicts last-write-wins between two devices on the
                        // same identity.
                        .putLong("profile_links_updated_at", System.currentTimeMillis())
                        .apply()
                    Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
            ) {
                Text(stringResource(R.string.profile_links_save))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProfileLinkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    urlPrefix: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            // While the user is editing, restore the prefix if they trim into it.
            // If they fully clear the field, allow the empty state.
            if (urlPrefix != null && newValue.isNotEmpty() &&
                !newValue.startsWith(urlPrefix) && urlPrefix.startsWith(newValue)) {
                onValueChange(urlPrefix)
            } else {
                onValueChange(newValue)
            }
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (focus.isFocused && value.isEmpty() && urlPrefix != null) {
                    onValueChange(urlPrefix)
                }
            }
    )
}
