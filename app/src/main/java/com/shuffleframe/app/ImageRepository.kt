package com.shuffleframe.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageRepository {

    private val imageExtensions = setOf("jpg", "jpeg", "png")
    private val imageMimeTypes = setOf("image/jpeg", "image/jpg", "image/png")

    /**
     * Lists the JPEG and PNG files directly inside the chosen folder (not subfolders).
     * Uses a single query rather than one call per file, so thousands of photos are fine.
     */
    suspend fun loadImages(context: Context, treeUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
        )
        val results = ArrayList<Uri>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val mime = cursor.getString(1)?.lowercase() ?: ""
                if (mime == Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(2) ?: ""
                if (name.startsWith(".")) continue // hidden files, e.g. trashed items
                val ext = name.substringAfterLast('.', "").lowercase()
                if (mime in imageMimeTypes || ext in imageExtensions) {
                    results.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                }
            }
        }
        results
    }

    /** "primary:Pictures/Holiday" → "Holiday" */
    fun folderName(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return "Folder"
        val path = id.substringAfter(':')
        return path.substringAfterLast('/').ifBlank { "Folder" }
    }
}
