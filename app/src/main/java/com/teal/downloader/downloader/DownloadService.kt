package com.teal.downloader.downloader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.teal.downloader.util.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a foreground notification alive while downloads run.
 *
 * It owns no download state — [DownloadManager] does — so the service being
 * stopped and started again by the system costs nothing but a notification.
 * That is also why this is START_NOT_STICKY: there is nothing to restore, and
 * re-enqueueing is the manager's job.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen before returning, or the system kills us for not going
        // foreground within the timeout after startForegroundService().
        goForeground()
        observeJob?.cancel()
        observeJob = scope.launch {
            DownloadManager.tasks.collectLatest { tasks ->
                val active = tasks.filter { it.isActive }
                if (active.isEmpty()) {
                    NotificationUtil.cancelProgress(this@DownloadService)
                    stopSelf()
                } else {
                    NotificationUtil.notifyProgress(this@DownloadService, active)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val notification = NotificationUtil.buildProgressNotification(this, DownloadManager.tasks.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NotificationUtil.DOWNLOAD_NOTIFICATION_ID, notification, type)
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopIfIdle(context: Context) {
            if (DownloadManager.activeCount == 0) {
                runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
            }
        }
    }
}
