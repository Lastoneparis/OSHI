package com.oshi.messenger.service.diag

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * __CALL_LOG_AT_REST_2026_09_23__ Settings → "Share call diagnostics" (iOS
 * `CallDiagnosticsExport`). Decrypts and redacts OFF the main thread, writes the only
 * plaintext copy to `cacheDir/call_diagnostics/` (deleted at the next launch or export —
 * nothing tells us when the receiving app is done reading), and shares it by
 * FileProvider with a one-shot read grant.
 */
object CallDiagnosticsShare {
    /** Cheap enough for composition: file stats, no decryption. */
    fun available(): Boolean = CallDiag.logger?.hasDiagnostics == true

    /** @return false when there is nothing to share or no app can receive it. */
    suspend fun share(context: Context, chooserTitle: String): Boolean {
        val logger = CallDiag.logger ?: return false
        val file = withContext(Dispatchers.IO) { logger.exportRedactedCallLog() } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, CallFileLogger.EXPORT_FILE_NAME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }
}
