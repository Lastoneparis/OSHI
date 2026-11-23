package com.oshi.messenger.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * AIDocumentProcessor — Extracts text from images (OCR) and documents (PDF, TXT, DOCX, PPTX)
 * for use as context in the OSHI AI assistant.
 *
 * Works with all AI backends: Gemini Nano, OSHI AI offline model, and NLP fallback.
 */
object AIDocumentProcessor {
    private const val TAG = "AIDocumentProcessor"
    private const val MAX_CONTEXT_LENGTH = 4000
    private const val MAX_FILE_SIZE = 50L * 1024 * 1024 // 50MB

    // =========================================================================
    // IMAGE OCR (ML Kit Text Recognition)
    // =========================================================================

    suspend fun extractTextFromImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return@withContext null
            extractTextFromBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Image OCR failed: ${e.message}")
            null
        }
    }

    suspend fun extractTextFromBitmap(bitmap: Bitmap): String? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = recognizer.process(image).await()
            val text = result.text.trim()
            if (text.isEmpty()) null else text
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed: ${e.message}")
            null
        }
    }

    // =========================================================================
    // PDF TEXT EXTRACTION
    // =========================================================================

    suspend fun extractTextFromPDF(context: Context, uri: Uri): String? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val renderer = PdfRenderer(pfd)
            val sb = StringBuilder()

            for (i in 0 until minOf(renderer.pageCount, 20)) { // Max 20 pages
                val page = renderer.openPage(i)
                // Render page to bitmap for OCR (PdfRenderer doesn't extract text directly)
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // OCR the rendered page
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val result = recognizer.process(image).await()
                    if (result.text.isNotBlank()) {
                        sb.appendLine("--- Page ${i + 1} ---")
                        sb.appendLine(result.text)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PDF page $i OCR failed: ${e.message}")
                }
                bitmap.recycle()

                if (sb.length > MAX_CONTEXT_LENGTH) break
            }

            renderer.close()
            pfd.close()
            val text = sb.toString().trim()
            if (text.isEmpty()) null else text
        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction failed: ${e.message}")
            null
        }
    }

    // =========================================================================
    // PLAIN TEXT
    // =========================================================================

    fun extractTextFromTXT(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val reader = BufferedReader(InputStreamReader(inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
                if (sb.length > MAX_CONTEXT_LENGTH) break
            }
            reader.close()
            val text = sb.toString().trim()
            if (text.isEmpty()) null else text
        } catch (e: Exception) {
            Log.e(TAG, "TXT extraction failed: ${e.message}")
            null
        }
    }

    // =========================================================================
    // DOCX (ZIP → word/document.xml → strip XML tags)
    // =========================================================================

    fun extractTextFromDocx(context: Context, uri: Uri): String? {
        return extractTextFromZipXml(context, uri, "word/document.xml")
    }

    // =========================================================================
    // PPTX (ZIP → ppt/slides/slide*.xml → strip XML tags)
    // =========================================================================

    fun extractTextFromPptx(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val zip = ZipInputStream(inputStream)
            val sb = StringBuilder()
            var entry = zip.nextEntry

            while (entry != null) {
                if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                    val xmlContent = zip.bufferedReader().readText()
                    val text = stripXmlTags(xmlContent)
                    if (text.isNotBlank()) {
                        sb.appendLine(text)
                    }
                }
                if (sb.length > MAX_CONTEXT_LENGTH) break
                entry = zip.nextEntry
            }

            zip.close()
            val text = sb.toString().trim()
            if (text.isEmpty()) null else text
        } catch (e: Exception) {
            Log.e(TAG, "PPTX extraction failed: ${e.message}")
            null
        }
    }

    // =========================================================================
    // AUTO-DETECT AND EXTRACT
    // =========================================================================

    /**
     * Auto-detect file type and extract text.
     * @return Pair(extractedText, sourceLabel) — text is null if extraction failed
     */
    suspend fun extractText(context: Context, uri: Uri, mimeType: String?): Pair<String?, String> {
        // Check file size
        val size = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0
        } catch (e: Exception) { 0L }

        if (size > MAX_FILE_SIZE) {
            return null to "File too large"
        }

        val fileName = getFileName(context, uri)
        val type = mimeType ?: inferMimeType(fileName)

        val text = when {
            type.startsWith("image/") -> extractTextFromImage(context, uri)
            type == "application/pdf" -> withContext(Dispatchers.IO) { extractTextFromPDF(context, uri) }
            type == "text/plain" -> withContext(Dispatchers.IO) { extractTextFromTXT(context, uri) }
            fileName.endsWith(".docx") || type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                withContext(Dispatchers.IO) { extractTextFromDocx(context, uri) }
            fileName.endsWith(".pptx") || type == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                withContext(Dispatchers.IO) { extractTextFromPptx(context, uri) }
            fileName.endsWith(".doc") || fileName.endsWith(".ppt") ->
                null // Legacy binary formats not supported
            else -> withContext(Dispatchers.IO) { extractTextFromTXT(context, uri) } // Try as plain text
        }

        return text to fileName
    }

    // =========================================================================
    // FORMAT FOR LLM CONTEXT
    // =========================================================================

    fun formatAsContext(text: String, sourceType: String): String {
        val truncated = if (text.length > MAX_CONTEXT_LENGTH) {
            text.take(MAX_CONTEXT_LENGTH) + "\n[... content truncated ...]"
        } else text

        return "[Extracted from $sourceType]\n$truncated\n[End of document]"
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun extractTextFromZipXml(context: Context, uri: Uri, targetEntry: String): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val zip = ZipInputStream(inputStream)
            var result: String? = null
            var entry = zip.nextEntry

            while (entry != null) {
                if (entry.name == targetEntry) {
                    val xmlContent = zip.bufferedReader().readText()
                    result = stripXmlTags(xmlContent)
                    break
                }
                entry = zip.nextEntry
            }

            zip.close()
            result?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "ZIP/XML extraction failed for $targetEntry: ${e.message}")
            null
        }
    }

    private fun stripXmlTags(xml: String): String {
        return xml.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "document"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: "document"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get filename: ${e.message}")
        }
        return name
    }

    private fun inferMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".pdf", true) -> "application/pdf"
            fileName.endsWith(".txt", true) -> "text/plain"
            fileName.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            fileName.endsWith(".pptx", true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
