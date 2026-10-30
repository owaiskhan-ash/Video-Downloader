package com.teal.downloader

import android.app.Application
import com.teal.downloader.downloader.DownloadManager
import com.teal.downloader.util.NotificationUtil

class TealApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationUtil.createChannels(this)
        // Starts the queue and its settings collectors. yt-dlp itself is not
        // unpacked here: that takes seconds, so it is deferred to first use.
        DownloadManager.init(this)
    }
}
