package io.github.minuus69.galleryocr.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.minuus69.galleryocr.MainActivity
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.ScanCounts
import java.text.NumberFormat

object Notifications {
    const val CHANNEL_SCAN = "scan"
    const val SCAN_ID = 1

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_SCAN,
            context.getString(R.string.notif_channel_scan),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_scan_desc)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** [smart]: the scan is in its smart search phase rather than reading text. */
    fun scanProgress(context: Context, counts: ScanCounts, smart: Boolean = false): Notification {
        val format = NumberFormat.getIntegerInstance()
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PauseReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(context.getString(if (smart) R.string.notif_analyzing_title else R.string.notif_scanning_title))
            .setContentText(
                context.getString(
                    R.string.notif_scanning_progress,
                    format.format(counts.processed),
                    format.format(counts.total),
                ),
            )
            .setProgress(counts.total.coerceAtLeast(1), counts.processed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.pause), pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/** "Pause" button in the scan notification. */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScanScheduler.pause(context)
    }
}
