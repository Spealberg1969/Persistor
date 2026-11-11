package com.persistor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImageItem(val uri: Uri, val name: String, val size: Long, val modified: Long)

class ImageRepository(private val context: Context) {

    /** Собирает изображения из всех выбранных папок, новые сверху. */
    suspend fun list(sources: Set<Uri>): List<ImageItem> = withContext(Dispatchers.IO) {
        sources.flatMap { tree ->
            val dir = DocumentFile.fromTreeUri(context, tree) ?: return@flatMap emptyList()
            dir.listFiles()
                .filter { it.isFile && it.type?.startsWith("image/") == true }
                .map { ImageItem(it.uri, it.name ?: "?", it.length(), it.lastModified()) }
        }.sortedByDescending { it.modified }
    }

    fun folderName(uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "?"
}
