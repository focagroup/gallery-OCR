package io.github.minuus69.galleryocr.scan

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import io.github.minuus69.galleryocr.app
import io.github.minuus69.galleryocr.data.ImageEntity
import io.github.minuus69.galleryocr.data.MediaImage
import io.github.minuus69.galleryocr.data.MediaStoreSource
import io.github.minuus69.galleryocr.data.OcrStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps the index in sync with MediaStore: adds new images, resets changed ones, drops deleted ones. */
object Indexer {
    private const val TAG = "Indexer"
    private val mutex = Mutex()

    /**
     * @param fullAccess false when the user granted access to selected photos only (Android 14+);
     * then images missing from MediaStore are not treated as deleted.
     */
    suspend fun sync(context: Context, fullAccess: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.app
            val db = app.database
            val dao = db.images()
            val settings = app.settings
            val media = try {
                MediaStoreSource.queryImages(context)
            } catch (e: SecurityException) {
                Log.w(TAG, "no media permission", e)
                return@withContext
            }
            val existing = dao.allStates().associateBy { it.id }
            val inserts = ArrayList<ImageEntity>()
            val changed = ArrayList<MediaImage>()
            val moved = ArrayList<MediaImage>()
            val seen = HashSet<Long>(media.size * 2)
            for (m in media) {
                seen += m.id
                val old = existing[m.id]
                when {
                    old == null -> {
                        val ocr = settings.isInScope(m.bucketId)
                        val smart = settings.isInSmartScope(m.bucketId)
                        if (ocr || smart) inserts += m.toEntity(ocr, smart)
                    }
                    old.dateModified != m.dateModified || old.size != m.size -> changed += m
                    old.bucketId != m.bucketId || old.volume != m.volume -> moved += m
                }
            }
            val mounted = MediaStoreSource.mountedVolumes(context)
            val gone = if (!fullAccess) {
                emptyList()
            } else {
                existing.values
                    .filter { it.id !in seen && (mounted == null || it.volume.isEmpty() || it.volume in mounted) }
                    .map { it.id }
            }
            db.withTransaction {
                inserts.chunked(500).forEach { dao.insertAll(it) }
                for (m in changed) {
                    dao.markChanged(
                        m.id, m.bucketId, m.bucketName, m.displayName, m.relativePath, m.volume,
                        m.mimeType, m.dateTaken, m.dateModified, m.size, m.orientation,
                    )
                }
                for (m in moved) {
                    dao.updateLocation(m.id, m.bucketId, m.bucketName, m.displayName, m.relativePath, m.volume)
                }
                gone.chunked(500).forEach { dao.deleteIds(it) }
                if (settings.allFolders.value) dao.setAllInScope() else dao.setScope(settings.folders.value.toList())
                if (settings.smartAllFolders.value) {
                    dao.setAllSmartScope()
                } else {
                    dao.setSmartScope(settings.smartFolders.value.toList())
                }
            }
            Log.i(TAG, "sync: ${media.size} in MediaStore, +${inserts.size} ~${changed.size} -${gone.size}")
        }
    }

    private fun MediaImage.toEntity(ocr: Boolean, smart: Boolean) = ImageEntity(
        id = id,
        bucketId = bucketId,
        bucketName = bucketName,
        displayName = displayName,
        relativePath = relativePath,
        volume = volume,
        mimeType = mimeType,
        dateTaken = dateTaken,
        dateModified = dateModified,
        size = size,
        orientation = orientation,
        inScope = ocr,
        status = OcrStatus.PENDING,
        ocrConfig = "",
        searchText = "",
        smartScope = smart,
    )
}
