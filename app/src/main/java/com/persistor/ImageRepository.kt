package com.persistor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


data class ImageItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long,
    val mimeType: String = "image/jpeg",
) {
    val isPdf: Boolean get() = mimeType.equals("application/pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)
}

class ImageRepository(private val context: Context) {

    /** Collects supported images and PDFs from SAF folders, newest first. */
    suspend fun list(sources: Set<Uri>): List<ImageItem> = withContext(Dispatchers.IO) {
        sources.flatMap { tree ->
            val dir = DocumentFile.fromTreeUri(context, tree) ?: return@flatMap emptyList()
            dir.listFiles().mapNotNull { document ->
                if (!document.isFile) return@mapNotNull null
                val name = document.name ?: "?"
                val mimeType = supportedMimeType(document.type, name) ?: return@mapNotNull null
                ImageItem(document.uri, name, document.length(), document.lastModified(), mimeType)
            }
        }.distinctBy { documentIdentity(it.uri) }.sortedByDescending { it.modified }
    }

    fun item(uri: Uri): ImageItem? {
        val document = DocumentFile.fromSingleUri(context, uri) ?: return null
        if (!document.isFile) return null
        val name = document.name ?: "?"
        val mimeType = supportedMimeType(document.type, name) ?: return null
        return ImageItem(uri, name, document.length(), document.lastModified(), mimeType)
    }

    fun folderName(uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "?"

    private fun supportedMimeType(providerType: String?, name: String): String? {
        if (providerType.equals("application/pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)) {
            return "application/pdf"
        }
        if (providerType?.startsWith("image/", ignoreCase = true) == true) return providerType
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "jxl" -> "image/jxl"
            else -> null
        }
    }

    private fun documentIdentity(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return if (documentId != null) "${uri.authority}:$documentId" else uri.normalizeScheme().toString()
    }
}
