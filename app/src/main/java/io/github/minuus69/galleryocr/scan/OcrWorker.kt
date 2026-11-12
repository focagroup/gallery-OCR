package io.github.minuus69.galleryocr.scan

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.minuus69.galleryocr.app
import io.github.minuus69.galleryocr.data.BoxCodec
import io.github.minuus69.galleryocr.data.ImageEntity
import io.github.minuus69.galleryocr.data.MediaStoreSource
import io.github.minuus69.galleryocr.data.ScanCounts
import io.github.minuus69.galleryocr.data.SearchText
import io.github.minuus69.galleryocr.ocr.OcrEngine
import io.github.minuus69.galleryocr.smart.ClipImageEncoder
import io.github.minuus69.galleryocr.smart.Embeddings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException

/**
 * Background scan with a progress notification: first OCR over all pending images (newest
 * first), then smart search embeddings. (The class keeps its name because WorkManager stores
 * it with queued work.)
 */
class OcrWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var foreground = false

    override suspend fun doWork(): Result {
        val dao = applicationContext.app.database.images()
        val ocrCounts = dao.counts()
        val smartCounts = dao.smartCounts()
        if (ocrCounts.pending == 0 && smartCounts.pending == 0) return Result.success()

        try {
            setForeground(
                if (ocrCounts.pending > 0) foregroundInfo(ocrCounts, smart = false) else foregroundInfo(smartCounts, smart = true),
            )
            foreground = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground service when started from the background;
            // the work then runs as normal background work and is resumed later if stopped.
            Log.w(TAG, "could not start foreground service", e)
        }

        val ocrOk = ocrCounts.pending == 0 || runOcr()
        val smartOk = dao.smartCounts().pending == 0 || runSmart()
        return if (ocrOk && smartOk) Result.success() else Result.failure()
    }

    /** OCR phase; returns false if the models could not be loaded. */
    private suspend fun runOcr(): Boolean {
        val app = applicationContext.app
        val dao = app.database.images()
        val config = app.settings.ocrConfig()
        val started = SystemClock.elapsedRealtime()
        val engine = try {
            OcrEngine(applicationContext, config)
        } catch (t: Throwable) {
            Log.e(TAG, "could not load OCR models", t)
            return false
        }
        Log.i(TAG, "models loaded in ${SystemClock.elapsedRealtime() - started} ms (${config.key})")

        var processed = 0
        var lastUpdate = 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = dao.nextPending(8)
                if (batch.isEmpty()) break
                for (image in batch) {
                    currentCoroutineContext().ensureActive()
                    process(engine, image)
                    processed++
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 1500) {
                        lastUpdate = now
                        if (foreground) updateNotification(dao.counts(), smart = false)
                    }
                }
            }
        } finally {
            engine.close()
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i(TAG, "processed $processed images in $seconds s")
        }
        return true
    }

    /** Smart search phase; returns false if the model could not be loaded. */
    private suspend fun runSmart(): Boolean {
        val app = applicationContext.app
        val dao = app.database.images()
        val mode = app.settings.smartMode.value
        val started = SystemClock.elapsedRealtime()
        val encoder = try {
            ClipImageEncoder(applicationContext, mode)
        } catch (t: Throwable) {
            Log.e(TAG, "could not load CLIP model", t)
            return false
        }
        Log.i(TAG, "CLIP model loaded in ${SystemClock.elapsedRealtime() - started} ms (${mode.key})")
        if (foreground) updateNotification(dao.smartCounts(), smart = true)

        var processed = 0
        var lastUpdate = 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = dao.nextSmartPending(8)
                if (batch.isEmpty()) break
                for (image in batch) {
                    currentCoroutineContext().ensureActive()
                    embed(encoder, image)
                    processed++
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 1500) {
                        lastUpdate = now
                        if (foreground) updateNotification(dao.smartCounts(), smart = true)
                    }
                }
            }
        } finally {
            encoder.close()
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i(TAG, "analyzed $processed images in $seconds s")
        }
        return true
    }

    private suspend fun embed(encoder: ClipImageEncoder, image: ImageEntity) {
        val dao = applicationContext.app.database.images()
        val mode = encoder.mode
        val t0 = SystemClock.elapsedRealtime()
        try {
            val vector = encoder.embed(
                applicationContext.contentResolver,
                MediaStoreSource.imageUri(image.id),
                image.orientation,
            )
            dao.saveEmbedding(image.id, mode.key, mode.id, Embeddings.toBytes(vector))
            Log.d(TAG, "image ${image.id}: embedded in ${SystemClock.elapsedRealtime() - t0} ms [${encoder.lastTiming}]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "image ${image.id}: out of memory")
            dao.saveSmartFailure(image.id, mode.key)
        } catch (e: FileNotFoundException) {
            dao.saveSmartFailure(image.id, mode.key)
        } catch (e: Exception) {
            Log.w(TAG, "image ${image.id} failed", e)
            dao.saveSmartFailure(image.id, mode.key)
        }
    }

    private suspend fun process(engine: OcrEngine, image: ImageEntity) {
        val dao = applicationContext.app.database.images()
        val key = engine.config.key
        val t0 = SystemClock.elapsedRealtime()
        try {
            val result = engine.recognize(
                applicationContext.contentResolver,
                MediaStoreSource.imageUri(image.id),
                image.orientation,
            )
            dao.saveResult(
                id = image.id,
                config = key,
                text = result.text,
                boxes = BoxCodec.encode(result.boxes),
                searchText = SearchText.normalize(result.text),
            )
            Log.d(TAG, "image ${image.id}: ${result.boxes.size} boxes in ${SystemClock.elapsedRealtime() - t0} ms [${engine.lastTiming}]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "image ${image.id}: out of memory")
            dao.saveFailure(image.id, key, "Out of memory")
        } catch (e: FileNotFoundException) {
            dao.saveFailure(image.id, key, "File not found")
        } catch (e: Exception) {
            Log.w(TAG, "image ${image.id} failed", e)
            dao.saveFailure(image.id, key, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun updateNotification(counts: ScanCounts, smart: Boolean) {
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(Notifications.SCAN_ID, Notifications.scanProgress(applicationContext, counts, smart))
        } catch (e: SecurityException) {
            // Notifications not allowed; scanning continues silently.
        }
    }

    private fun foregroundInfo(counts: ScanCounts, smart: Boolean): ForegroundInfo {
        val notification = Notifications.scanProgress(applicationContext, counts, smart)
        return when {
            Build.VERSION.SDK_INT >= 35 ->
                ForegroundInfo(Notifications.SCAN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                ForegroundInfo(Notifications.SCAN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(Notifications.SCAN_ID, notification)
        }
    }

    private companion object {
        const val TAG = "OcrWorker"
    }
}
