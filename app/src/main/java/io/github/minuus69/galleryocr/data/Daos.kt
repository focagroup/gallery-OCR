package io.github.minuus69.galleryocr.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT id, dateModified, size, bucketId, volume FROM images")
    suspend fun allStates(): List<ImageState>

    /** Never use REPLACE here: it would delete the row and cascade to texts and collections. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ImageEntity>)

    @Query(
        """UPDATE images SET bucketId = :bucketId, bucketName = :bucketName, displayName = :displayName,
           relativePath = :relativePath, volume = :volume, mimeType = :mimeType, dateTaken = :dateTaken,
           dateModified = :dateModified, size = :size, orientation = :orientation, status = 0, smartStatus = 0
           WHERE id = :id""",
    )
    suspend fun markChanged(
        id: Long, bucketId: String, bucketName: String, displayName: String, relativePath: String,
        volume: String, mimeType: String, dateTaken: Long, dateModified: Long, size: Long, orientation: Int,
    )

    @Query(
        """UPDATE images SET bucketId = :bucketId, bucketName = :bucketName, displayName = :displayName,
           relativePath = :relativePath, volume = :volume WHERE id = :id""",
    )
    suspend fun updateLocation(
        id: Long, bucketId: String, bucketName: String, displayName: String, relativePath: String, volume: String,
    )

    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE images SET inScope = 1")
    suspend fun setAllInScope()

    @Query("UPDATE images SET inScope = (bucketId IN (:buckets))")
    suspend fun setScope(buckets: List<String>)

    @Query("UPDATE images SET smartScope = 1")
    suspend fun setAllSmartScope()

    @Query("UPDATE images SET smartScope = (bucketId IN (:buckets))")
    suspend fun setSmartScope(buckets: List<String>)

    @Query("SELECT * FROM images WHERE inScope = 1 AND status = 0 ORDER BY dateTaken DESC LIMIT :limit")
    suspend fun nextPending(limit: Int): List<ImageEntity>

    @Query(COUNTS)
    fun observeCounts(): Flow<ScanCounts>

    @Query(COUNTS)
    suspend fun counts(): ScanCounts

    @Query("UPDATE images SET status = :status, searchText = :searchText, ocrConfig = :config WHERE id = :id")
    suspend fun setResult(id: Long, status: Int, searchText: String, config: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putText(text: OcrTextEntity)

    @Transaction
    suspend fun saveResult(id: Long, config: String, text: String, boxes: String, searchText: String) {
        putText(OcrTextEntity(id, text, boxes, null))
        setResult(id, OcrStatus.DONE, searchText, config)
    }

    @Transaction
    suspend fun saveFailure(id: Long, config: String, error: String) {
        putText(OcrTextEntity(id, "", "[]", error))
        setResult(id, OcrStatus.FAILED, "", config)
    }

    @Query("SELECT * FROM ocr_text WHERE imageId = :id")
    suspend fun text(id: Long): OcrTextEntity?

    @Query("UPDATE images SET status = 0 WHERE status = 2")
    suspend fun retryFailed()

    @Query("UPDATE images SET status = 0 WHERE status != 0 AND ocrConfig != :config")
    suspend fun rescanOutdated(config: String)

    @Query("UPDATE images SET status = 0")
    suspend fun rescanAll()

    // ---- smart search

    @Query("SELECT * FROM images WHERE smartScope = 1 AND smartStatus = 0 ORDER BY dateTaken DESC LIMIT :limit")
    suspend fun nextSmartPending(limit: Int): List<ImageEntity>

    @Query(SMART_COUNTS)
    fun observeSmartCounts(): Flow<ScanCounts>

    @Query(SMART_COUNTS)
    suspend fun smartCounts(): ScanCounts

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEmbedding(embedding: EmbeddingEntity)

    @Query("DELETE FROM embeddings WHERE imageId = :id")
    suspend fun deleteEmbedding(id: Long)

    @Query("UPDATE images SET smartStatus = :status, smartConfig = :config WHERE id = :id")
    suspend fun setSmartResult(id: Long, status: Int, config: String)

    @Transaction
    suspend fun saveEmbedding(id: Long, config: String, model: String, vector: ByteArray) {
        putEmbedding(EmbeddingEntity(id, model, vector))
        setSmartResult(id, OcrStatus.DONE, config)
    }

    @Transaction
    suspend fun saveSmartFailure(id: Long, config: String) {
        deleteEmbedding(id)
        setSmartResult(id, OcrStatus.FAILED, config)
    }

    @Query("UPDATE images SET smartStatus = 0 WHERE smartStatus = 2")
    suspend fun retrySmartFailed()

    @Query("UPDATE images SET smartStatus = 0 WHERE smartStatus != 0 AND smartConfig != :config")
    suspend fun reanalyzeOutdated(config: String)

    @Query("UPDATE images SET smartStatus = 0")
    suspend fun reanalyzeAll()

    @Query("SELECT * FROM embeddings")
    suspend fun allEmbeddings(): List<EmbeddingEntity>

    /** Changes whenever embeddings are added, replaced or removed. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(SUM(imageId), 0) || ':' || COALESCE(SUM(LENGTH(model)), 0) FROM embeddings")
    fun observeEmbeddingsVersion(): Flow<String>

    @Query(
        """SELECT id, dateTaken, mimeType, orientation, bucketName, displayName, status FROM images
           WHERE smartScope = 1 AND id IN (:ids)""",
    )
    suspend fun smartRows(ids: List<Long>): List<ImageRow>

    @Query(
        """SELECT id, dateTaken, mimeType, orientation, bucketName, displayName, status FROM images
           WHERE id IN (:ids) AND id IN (SELECT imageId FROM collection_items WHERE collectionId = :collectionId)""",
    )
    suspend fun collectionRows(ids: List<Long>, collectionId: Long): List<ImageRow>

    @RawQuery(observedEntities = [ImageEntity::class, CollectionItemEntity::class])
    fun observeRows(query: SupportSQLiteQuery): Flow<List<ImageRow>>

    companion object {
        const val COUNTS = """SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS done,
            COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS failed
            FROM images WHERE inScope = 1"""

        const val SMART_COUNTS = """SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN smartStatus = 1 THEN 1 ELSE 0 END), 0) AS done,
            COALESCE(SUM(CASE WHEN smartStatus = 2 THEN 1 ELSE 0 END), 0) AS failed
            FROM images WHERE smartScope = 1"""

        /**
         * Builds the gallery/text search query. Every search term must occur as a substring of
         * the normalized text; inside a collection, folder selection is ignored. Otherwise only
         * images from the folders chosen for [mode] are shown (text search folders or smart
         * search folders), so the two tabs stay separate.
         */
        fun buildRowsQuery(terms: List<String>, collectionId: Long?, mode: SearchMode): SupportSQLiteQuery {
            val sql = StringBuilder(
                "SELECT id, dateTaken, mimeType, orientation, bucketName, displayName, status FROM images WHERE ",
            )
            val args = ArrayList<Any>()
            if (collectionId != null) {
                sql.append("id IN (SELECT imageId FROM collection_items WHERE collectionId = ?)")
                args += collectionId
            } else if (mode == SearchMode.SMART) {
                sql.append("smartScope = 1")
            } else {
                sql.append("inScope = 1")
            }
            for (term in terms) {
                sql.append(" AND instr(searchText, ?) > 0")
                args += term
            }
            sql.append(" ORDER BY dateTaken DESC, id DESC")
            return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
        }
    }
}

@Dao
interface CollectionDao {
    @Query(
        """SELECT c.id AS id, c.name AS name, COUNT(i.imageId) AS count,
           (SELECT imageId FROM collection_items WHERE collectionId = c.id ORDER BY addedAt DESC LIMIT 1) AS coverId
           FROM collections c LEFT JOIN collection_items i ON i.collectionId = c.id
           GROUP BY c.id ORDER BY c.name COLLATE NOCASE""",
    )
    fun observe(): Flow<List<CollectionInfo>>

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<CollectionItemEntity>)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND imageId IN (:imageIds)")
    suspend fun removeItems(collectionId: Long, imageIds: List<Long>)

    @Query("SELECT collectionId FROM collection_items WHERE imageId = :imageId")
    fun observeMembership(imageId: Long): Flow<List<Long>>
}
