package io.github.minuus69.galleryocr.scan

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.minuus69.galleryocr.app
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Starts, pauses and observes the background OCR work. */
object ScanScheduler {
    private const val WORK_NAME = "ocr-scan"

    /**
     * Starts scanning now unless paused or already running. Work that is merely queued
     * (e.g. waiting out a retry delay after the app was force-stopped) is replaced, so the
     * scan resumes as soon as the user opens the app.
     */
    suspend fun ensureRunning(context: Context) {
        if (context.app.settings.paused.value) return
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        enqueue(context, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
    }

    /** Restarts scanning, e.g. after OCR settings changed. */
    fun restart(context: Context) {
        if (context.app.settings.paused.value) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    fun pause(context: Context) {
        context.app.settings.setPaused(true)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    suspend fun resume(context: Context) {
        context.app.settings.setPaused(false)
        ensureRunning(context)
    }

    fun observeRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OcrWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
