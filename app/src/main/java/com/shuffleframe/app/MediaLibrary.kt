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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Selection key for the "All photos" entry in the album picker. */
const val ALL_PHOTOS_KEY = "__all__"

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

    /**
     * Photos for the chosen albums, one list per album (in [bucketIds] order).
     * An empty [bucketIds] means all photos, as a single list.
     */
    suspend fun loadAlbumGroups(context: Context, bucketIds: List<String>): List<List<Uri>> =
        withContext(Dispatchers.IO) {
            val selection = if (bucketIds.isEmpty()) null
            else "${MediaStore.Images.Media.BUCKET_ID} IN (${bucketIds.joinToString(",") { "?" }})"
            val args = bucketIds.takeIf { it.isNotEmpty() }?.toTypedArray()
            val byBucket = LinkedHashMap<String, MutableList<Uri>>()
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_ID),
                selection, args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    val bucket = if (bucketIds.isEmpty()) ALL_PHOTOS_KEY else (c.getString(1) ?: "unknown")
                    byBucket.getOrPut(bucket) { ArrayList() }.add(uri)
                }
            }
            if (bucketIds.isEmpty()) listOf(byBucket[ALL_PHOTOS_KEY].orEmpty())
            else bucketIds.map { byBucket[it].orEmpty() }
        }

    /**
     * Photos taken on [today]'s day and month in earlier years, grouped by year (oldest first).
     * Uses the date the photo was taken, not when it was copied to the phone.
     */
    suspend fun loadOnThisDay(context: Context, today: LocalDate): List<List<Uri>> =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val byYear = sortedMapOf<Int, MutableList<Uri>>()
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                "${MediaStore.Images.Media.DATE_TAKEN} IS NOT NULL", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val taken = c.getLong(1)
                    if (taken <= 0L) continue
                    val date = Instant.ofEpochMilli(taken).atZone(zone).toLocalDate()
                    if (date.monthValue == today.monthValue && date.dayOfMonth == today.dayOfMonth && date.year < today.year) {
                        byYear.getOrPut(date.year) { ArrayList() }
                            .add(ContentUris.withAppendedId(collection, c.getLong(0)))
                    }
                }
            }
            byYear.values.toList()
        }
}
