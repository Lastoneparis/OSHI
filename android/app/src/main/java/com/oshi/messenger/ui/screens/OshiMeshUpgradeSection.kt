package com.oshi.messenger.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple

/** The web flasher needs a computer (Chrome/Edge over USB), so the phone only hands the link on. */
private const val OSHI_MESH_FLASHER_URL = "https://oshi-messenger.com/lora/flash/"
private const val OSHI_MESH_WHY_URL = "https://oshi-messenger.com/lora/why-oshi-mesh"

/**
 * Stock Meshtastic radio -> offer OSHI Mesh (the flasher keeps the node's settings). Port of the
 * `oshimesh.upgrade` section of iOS MeshtasticSettingsView.swift. The caller shows it only when the
 * radio is ready, the OSHI Mesh probe is done, and the family is still MESHTASTIC.
 */
@Composable
internal fun OshiMeshUpgradeSection() {
    val context = LocalContext.current
    LoRaSection(footer = stringResource(R.string.oshimesh_upgrade_footer)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowCircleUp, null, Modifier.size(22.dp), tint = OSHIPurple)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.oshimesh_upgrade_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(R.string.oshimesh_upgrade_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Divider(Modifier.padding(vertical = 8.dp))
        val shareLabel = stringResource(R.string.oshimesh_upgrade_share)
        TextButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, OSHI_MESH_FLASHER_URL)
            }
            runCatching { context.startActivity(Intent.createChooser(send, shareLabel)) }
        }) {
            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(shareLabel)
        }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OSHI_MESH_WHY_URL))) }
        }) {
            Icon(Icons.Default.Info, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.oshimesh_upgrade_why))
        }
    }
}
