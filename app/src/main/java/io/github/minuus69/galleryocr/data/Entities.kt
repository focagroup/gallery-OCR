package io.github.minuus69.galleryocr.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object OcrStatus {
    const val PENDING = 0
    const val DONE = 1
    const val FAILED = 2
}

/** One image known to the index. [id] is the MediaStore id. */
@Entity(
    tableName = "images",
    indices = [
        Index(value = ["inScope", "dateTaken"]),
        Index(value = ["inScope", "status"]),
        Index(value = ["bucketId"]),
    ],
)
data class ImageEntity(
    @PrimaryKey val id: Long,
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
    /** True if the image lies in a folder the user chose to scan. */
    val inScope: Boolean,
    val status: Int,
    /** [io.github.minuus69.galleryocr.ocr.OcrConfig.key] used for the last scan. */
    val ocrConfig: String,
    /** Normalized recognized text without whitespace, used for substring search. */
    val searchText: String,
)

/** Recognized text and boxes; kept separate so the search table stays small. */
@Entity(
    tableName = "ocr_text",
    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OcrTextEntity(
    @PrimaryKey val imageId: Long,
    val text: String,
    /** JSON, see [BoxCodec]. */
    val boxes: String,
    val error: String?,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "imageId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["imageId"])],
)
data class CollectionItemEntity(
    val collectionId: Long,
    val imageId: Long,
    val addedAt: Long,
)

// ---- projections

data class ImageRow(
    val id: Long,
    val dateTaken: Long,
    val mimeType: String,
    val orientation: Int,
    val bucketName: String,
    val displayName: String,
    val status: Int,
)

data class ImageState(
    val id: Long,
    val dateModified: Long,
    val size: Long,
    val bucketId: String,
    val volume: String,
)

data class ScanCounts(val total: Int, val done: Int, val failed: Int) {
    val pending: Int get() = total - done - failed
    val processed: Int get() = done + failed
}

data class CollectionInfo(val id: Long, val name: String, val count: Int, val coverId: Long?)
