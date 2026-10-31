package com.teal.downloader

import android.app.Application
import com.teal.downloader.downloader.DownloadManager
import com.teal.downloader.util.NotificationUtil

class TealApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationUtil.createChannels(this)
        // Starts the queue and its settings collectors.
        DownloadManager.init(this)
        // Then unpacks yt-dlp in the background. This used to be deferred to
        // first use, which meant the first paste paid for it: seconds of
        // unzipping the Python runtime, plus a GitHub download whenever an
        // engine refresh was due. Both now overlap with the user finding a link.
        DownloadManager.prepareEngine()
    }
}
