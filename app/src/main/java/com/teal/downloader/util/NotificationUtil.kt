package com.teal.downloader.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.teal.downloader.MainActivity
import com.teal.downloader.R
import com.teal.downloader.data.model.DownloadTask
import com.teal.downloader.downloader.NotificationActionReceiver

object NotificationUtil {

    const val CHANNEL_DOWNLOADS = "teal_downloads"
    const val CHANNEL_RESULTS = "teal_results"
    const val DOWNLOAD_NOTIFICATION_ID = 1001
    private const val RESULT_NOTIFICATION_BASE = 2000
    const val ACTION_CANCEL_ALL = "com.teal.downloader.CANCEL_ALL"
    const val ACTION_CANCEL_ONE = "com.teal.downloader.CANCEL_ONE"
    const val EXTRA_TASK_ID = "task_id"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                context.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_downloads_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.notification_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_results_desc) },
        )
    }

    /** One ongoing notification summarising the whole queue. */
    fun buildProgressNotification(context: Context, active: List<DownloadTask>): Notification {
        val total = active.size
        val average = if (total == 0) 0f else active.sumOf { it.progress.toDouble() }.toFloat() / total
        val current = active.firstOrNull { it.status == com.teal.downloader.data.model.TaskStatus.RUNNING }
            ?: active.firstOrNull()

        val title = when (total) {
            0 -> context.getString(R.string.notification_downloading)
            1 -> current?.title ?: context.getString(R.string.notification_downloading)
            else -> context.getString(R.string.notification_downloading_count, total)
        }
        val text = current?.let { task ->
            val speed = TextUtil.formatSpeed(task.speedBytesPerSecond)
            val eta = TextUtil.formatEta(task.etaSeconds)
            if (total == 1) "$speed · $eta" else context.getString(R.string.notification_multi_body, speed, eta)
        } ?: ""

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, average.toInt(), average <= 0f)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.notification_cancel), cancelAllIntent(context))
            .build()
    }

    fun notifyProgress(context: Context, active: List<DownloadTask>) {
        post(context, DOWNLOAD_NOTIFICATION_ID, buildProgressNotification(context, active))
    }

    fun notifyResult(context: Context, task: DownloadTask, succeeded: Boolean) {
        val text = if (succeeded) {
            context.getString(R.string.notification_complete)
        } else {
            task.error ?: context.getString(R.string.notification_failed)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .apply {
                if (succeeded) {
                    setCategory(NotificationCompat.CATEGORY_STATUS)
                } else {
                    setCategory(NotificationCompat.CATEGORY_ERROR)
                }
            }
            .build()
        val id = RESULT_NOTIFICATION_BASE + (task.id.hashCode() and 0x7FFFFFFF % 100000)
        post(context, id, notification)
    }

    fun cancelProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(DOWNLOAD_NOTIFICATION_ID)
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        // POST_NOTIFICATIONS is a runtime permission on API 33+ and the user can
        // decline it. Check rather than lean on runCatching to swallow the
        // SecurityException: the download does not need notifications to succeed,
        // so a denial should cost the progress display and nothing else.
        if (!notificationsAllowed(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelAllIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_CANCEL_ALL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
