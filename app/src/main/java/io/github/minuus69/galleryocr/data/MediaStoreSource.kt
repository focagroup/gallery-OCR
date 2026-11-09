package io.github.minuus69.galleryocr.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

data class MediaImage(
    val id: Long,
    val bucketId: String,
    val bucketName: String,
    val displayName: String,
    val relativePath: String,
    val volume: String,
    val mimeType: String,
    val dateTaken: Long,
    val dateModified: Long,
    val size: Long,
    val orientation: Int,
)

data class MediaFolder(
    val bucketId: String,
    val name: String,
    val path: String,
    val count: Int,
    val coverId: Long,
    val latest: Long,
) {
    val looksLikeScreenshots: Boolean
        get() = name.contains("screenshot", ignoreCase = true) || path.contains("screenshot", ignoreCase = true)
}

/** Read-only access to the images Android's MediaStore knows about. */
object MediaStoreSource {
    val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun imageUri(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    fun queryImages(context: Context): List<MediaImage> {
        val modern = Build.VERSION.SDK_INT >= 29
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.ORIENTATION,
        )
        if (modern) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
            projection += MediaStore.Images.Media.VOLUME_NAME
        } else {
            @Suppress("DEPRECATION")
            projection += MediaStore.Images.Media.DATA
        }
        val result = ArrayList<MediaImage>()
        context.contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val iBucketName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iModified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iOrientation = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val iPath = if (modern) {
                c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            }
            val iVolume = if (modern) c.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME) else -1
            while (c.moveToNext()) {
                val modified = c.getLong(iModified)
                var taken = if (c.isNull(iTaken)) 0L else c.getLong(iTaken)
                if (taken <= 0L) taken = c.getLong(iAdded) * 1000L
                if (taken <= 0L) taken = modified * 1000L
                val rawPath = c.getString(iPath) ?: ""
                val path = if (modern) rawPath else File(rawPath).parent?.let { "$it/" } ?: ""
                result += MediaImage(
                    id = c.getLong(iId),
                    bucketId = c.getString(iBucket) ?: "",
                    bucketName = c.getString(iBucketName) ?: "",
                    displayName = c.getString(iName) ?: "",
                    relativePath = path,
                    volume = if (iVolume >= 0) c.getString(iVolume) ?: "" else "",
                    mimeType = c.getString(iMime) ?: "image/*",
                    dateTaken = taken,
                    dateModified = modified,
                    size = c.getLong(iSize),
                    orientation = if (c.isNull(iOrientation)) 0 else c.getInt(iOrientation),
                )
            }
        }
        return result
    }

    fun folders(images: List<MediaImage>): List<MediaFolder> =
        images.groupBy { it.bucketId }.map { (bucketId, list) ->
            val latest = list.maxBy { it.dateTaken }
            MediaFolder(
                bucketId = bucketId,
                name = latest.bucketName.ifEmpty { latest.relativePath.trimEnd('/').substringAfterLast('/') },
                path = latest.relativePath,
                count = list.size,
                coverId = latest.id,
                latest = latest.dateTaken,
            )
        }.sortedWith(compareByDescending<MediaFolder> { it.looksLikeScreenshots }.thenByDescending { it.latest })

    /** Storage volumes that are currently attached, or null if unknown (Android 9 and older). */
    fun mountedVolumes(context: Context): Set<String>? =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context) else null
}
