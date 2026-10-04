package com.shuffleframe.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A Gallery-style album: every photo that shares a folder ("bucket"). id == null means all photos. */
data class Album(val id: String?, val name: String, val count: Int, val cover: Uri)

/** Permission to read the phone's photo library (what the Gallery app shows). */
object MediaAccess {

    fun permissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun fullAccess(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.READ_MEDIA_IMAGES)
        else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Android 14+: the person chose "Allow limited access" and picked specific photos. */
    fun isLimited(ctx: Context): Boolean =
        !fullAccess(ctx) && Build.VERSION.SDK_INT >= 34 &&
            granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun has(ctx: Context): Boolean = fullAccess(ctx) || isLimited(ctx)
}

object MediaLibrary {

    private val collection: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Albums with photo counts and the newest photo as the cover, biggest first, plus "All photos". */
    suspend fun loadAlbums(context: Context): List<Album> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        class Acc(val name: String, val cover: Uri) { var count = 0 }
        val buckets = LinkedHashMap<String, Acc>()
        var total = 0
        var newest: Uri? = null

        context.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                val bucketId = c.getString(1) ?: "unknown"
                val name = c.getString(2) ?: "Unnamed"
                total++
                if (newest == null) newest = uri
                buckets.getOrPut(bucketId) { Acc(name, uri) }.count++
            }
        }

        val cover = newest ?: return@withContext emptyList()
        val albums = buckets.map { (id, acc) -> Album(id, acc.name, acc.count, acc.cover) }
            .sortedByDescending { it.count }
        listOf(Album(null, "All photos", total, cover)) + albums
    }

    /** Every photo in one album (or all photos when [bucketId] is null). */
    suspend fun loadAlbumImages(context: Context, bucketId: String?): List<Uri> = withContext(Dispatchers.IO) {
        val selection = bucketId?.let { "${MediaStore.Images.Media.BUCKET_ID} = ?" }
        val args = bucketId?.let { arrayOf(it) }
        val results = ArrayList<Uri>()
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Images.Media._ID), selection, args, null,
        )?.use { c ->
            while (c.moveToNext()) results.add(ContentUris.withAppendedId(collection, c.getLong(0)))
        }
        results
    }
}
