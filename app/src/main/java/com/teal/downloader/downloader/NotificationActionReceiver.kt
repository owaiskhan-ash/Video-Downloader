package com.teal.downloader.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.teal.downloader.util.NotificationUtil

/** Handles the notification's cancel actions; the UI has its own paths. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NotificationUtil.ACTION_CANCEL_ALL -> DownloadManager.cancelAll()
            NotificationUtil.ACTION_CANCEL_ONE ->
                intent.getStringExtra(NotificationUtil.EXTRA_TASK_ID)?.let(DownloadManager::cancel)
        }
        NotificationUtil.cancelProgress(context)
    }
}
