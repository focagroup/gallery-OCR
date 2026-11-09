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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException

/** Runs OCR over all pending images, newest first, with a progress notification. */
class OcrWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val dao = app.database.images()
        var counts = dao.counts()
        if (counts.pending == 0) return Result.success()

        var foreground = false
        try {
            setForeground(foregroundInfo(counts))
            foreground = true
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground service when started from the background;
            // the work then runs as normal background work and is resumed later if stopped.
            Log.w(TAG, "could not start foreground service", e)
        }

        val config = app.settings.ocrConfig()
        val started = SystemClock.elapsedRealtime()
        val engine = try {
            OcrEngine(applicationContext, config)
        } catch (t: Throwable) {
            Log.e(TAG, "could not load OCR models", t)
            return Result.failure()
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
                        counts = dao.counts()
                        if (foreground) updateNotification(counts)
                    }
                }
            }
        } finally {
            engine.close()
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i(TAG, "processed $processed images in $seconds s")
        }
        return Result.success()
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

    private fun updateNotification(counts: ScanCounts) {
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(Notifications.SCAN_ID, Notifications.scanProgress(applicationContext, counts))
        } catch (e: SecurityException) {
            // Notifications not allowed; scanning continues silently.
        }
    }

    private fun foregroundInfo(counts: ScanCounts): ForegroundInfo {
        val notification = Notifications.scanProgress(applicationContext, counts)
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
