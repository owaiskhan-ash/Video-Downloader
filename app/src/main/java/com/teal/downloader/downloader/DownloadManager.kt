package com.teal.downloader.downloader

import android.content.Context
import com.teal.downloader.data.db.HistoryEntity
import com.teal.downloader.data.db.TealDatabase
import com.teal.downloader.data.model.DownloadRequest
import com.teal.downloader.data.model.DownloadTask
import com.teal.downloader.data.model.ProgressUpdate
import com.teal.downloader.data.model.TaskStatus
import com.teal.downloader.data.prefs.SettingsRepository
import com.teal.downloader.util.FileUtil
import com.teal.downloader.util.NotificationUtil
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The download queue.
 *
 * A process-wide singleton rather than something owned by the service: the
 * service is a notification host that comes and goes, while the queue has to
 * survive it being recreated. State lives in a single [StateFlow] so Compose can
 * observe it directly, with no callback plumbing.
 */
object DownloadManager {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** Kept apart from the UI state: it holds settings, which are not UI. */
    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private var ytdlp: YtdlpManager? = null
    private var settingsRepo: SettingsRepository? = null

    /** Room DAO, created on first use (needs Context, so not a plain `lazy`). */
    private var historyDao: com.teal.downloader.data.db.HistoryDao? = null

    private fun dao(): com.teal.downloader.data.db.HistoryDao? =
        historyDao ?: runCatching { TealDatabase.get(appContext).historyDao() }
            .getOrNull()?.also { historyDao = it }

    /** Called once from [com.teal.downloader.TealApp.onCreate]. */
    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        ytdlp = YtdlpManager(appContext)
        settingsRepo = SettingsRepository(appContext)
        NotificationUtil.createChannels(appContext)

        scope.launch {
            settingsRepo?.maxConcurrentDownloads?.collect { maxConcurrent = it; dispatch() }
        }
        scope.launch {
            settingsRepo?.publishToDownloads?.collect { publishToDownloads = it }
        }
    }

    /**
     * Reads metadata for a URL. Routed through the manager so the app keeps a
     * single YtdlpManager (and therefore a single yt-dlp initialisation) rather
     * than one per ViewModel.
     */
    suspend fun fetchInfo(
        url: String,
        settings: com.teal.downloader.data.model.DownloadSettings = currentSettings(),
        flatPlaylist: Boolean = false,
    ): com.teal.downloader.data.model.YtdlInfo =
        ytdlp?.fetchInfo(url, settings, flatPlaylist)
            ?: throw DownloadException("The downloader is not ready yet")

    suspend fun ytDlpVersion(): String? = ytdlp?.ytDlpVersion()

    /** Queues a download and returns its id. */
    fun enqueue(request: DownloadRequest, title: String?, thumbnail: String?): String {
        val id = UUID.randomUUID().toString()
        requests[id] = request
        val task = DownloadTask(
            id = id,
            url = request.url,
            title = title?.takeIf { it.isNotBlank() } ?: request.url,
            thumbnail = thumbnail,
            isAudioOnly = request.settings.audioOnly,
        )
        _tasks.update { it + task }
        DownloadService.start(appContext)
        dispatch()
        return id
    }

    fun cancel(id: String) {
        jobs[id]?.let { job ->
            ytdlp?.cancel(id)
            job.cancel()
            jobs.remove(id)
        }
        patch(id) { it.copy(status = TaskStatus.CANCELED, finishedAt = System.currentTimeMillis()) }
        dispatch()
    }

    fun cancelAll() {
        _tasks.value.filter { it.isActive }.forEach { cancel(it.id) }
    }

    /** Re-queues a finished task with its original request. */
    fun retry(id: String): String? {
        val request = requests[id] ?: _tasks.value.firstOrNull { it.id == id }?.let { task ->
            DownloadRequest(url = task.url)
        } ?: return null
        val old = _tasks.value.firstOrNull { it.id == id }
        remove(id)
        return enqueue(request, old?.title, old?.thumbnail)
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        requests.remove(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.isActive } }
    }

    val activeCount: Int get() = _tasks.value.count { it.isActive }

    // -- internals ----------------------------------------------------------

    /** Starts queued tasks while there is capacity. Call after any state change. */
    private fun dispatch() {
        if (!started) return
        val running = jobs.size
        var capacity = (maxConcurrent - running).coerceAtLeast(0)
        if (capacity == 0) return
        val queued = _tasks.value
            .filter { it.status == TaskStatus.QUEUED && !jobs.containsKey(it.id) }
            .sortedBy { it.createdAt }
        for (task in queued) {
            if (capacity <= 0) break
            capacity--
            val request = requests[task.id] ?: continue
            val job = scope.launch { runTask(task.id, request) }
            jobs[task.id] = job
            patch(task.id) { it.copy(status = TaskStatus.RUNNING) }
        }
    }

    private suspend fun runTask(id: String, request: DownloadRequest) {
        val manager = ytdlp ?: return
        val staging = FileUtil.stagingDir(appContext)
        val startedAt = System.currentTimeMillis()
        try {
            val file = manager.download(
                url = request.url,
                request = request,
                outputDir = staging,
                processId = id,
                onProgress = { update -> patch(id) { applyProgress(it, update) } },
            )
            finish(id, request, file, startedAt)
        } catch (canceled: CanceledException) {
            patch(id) { it.copy(status = TaskStatus.CANCELED, finishedAt = System.currentTimeMillis()) }
            recordHistory(id, request, null, null, succeeded = false, error = null, canceled = true)
        } catch (error: DownloadException) {
            fail(id, request, error.message ?: "Download failed")
        } catch (error: Exception) {
            fail(id, request, DownloadException.classify(error.message ?: error.javaClass.simpleName, request.url))
        } finally {
            jobs.remove(id)
            dispatch()
            if (activeCount == 0) {
                NotificationUtil.cancelProgress(appContext)
                DownloadService.stopIfIdle(appContext)
            }
        }
    }

    private fun applyProgress(task: DownloadTask, update: ProgressUpdate): DownloadTask {
        val percent = update.effectivePercent
        return task.copy(
            progress = if (percent > task.progress) percent else task.progress,
            speedBytesPerSecond = update.speedBytesPerSecond ?: task.speedBytesPerSecond,
            etaSeconds = update.etaSeconds ?: task.etaSeconds,
            totalBytes = update.totalBytes ?: task.totalBytes,
            downloadedBytes = update.totalBytes?.let { total ->
                (total * percent / 100f).toLong()
            } ?: task.downloadedBytes,
            status = if (update.postProcessing) TaskStatus.POST_PROCESSING else TaskStatus.RUNNING,
        )
    }

    private suspend fun finish(id: String, request: DownloadRequest, file: File, startedAt: Long) {
        val uri = if (publishToDownloads) FileUtil.publishToDownloads(appContext, file) else null
        // Only delete the staged copy once publishing really succeeded.
        if (uri != null) FileUtil.deleteQuietly(file)
        val finalPath = if (uri != null) null else file.absolutePath
        patch(id) {
            it.copy(
                status = TaskStatus.COMPLETED,
                progress = 100f,
                filePath = finalPath,
                contentUri = uri?.toString(),
                ext = file.extension,
                finishedAt = System.currentTimeMillis(),
                error = null,
            )
        }
        recordHistory(id, request, finalPath ?: uri?.toString(), uri?.toString(), succeeded = true, error = null,
            canceled = false, sizeBytes = file.length().takeIf { it > 0 }, ext = file.extension)
        taskById(id)?.let { NotificationUtil.notifyResult(appContext, it, succeeded = true) }
    }

    private suspend fun fail(id: String, request: DownloadRequest, message: String) {
        patch(id) {
            it.copy(status = TaskStatus.FAILED, error = message, finishedAt = System.currentTimeMillis())
        }
        recordHistory(id, request, null, null, succeeded = false, error = message, canceled = false)
        taskById(id)?.let { NotificationUtil.notifyResult(appContext, it, succeeded = false) }
    }

    private suspend fun recordHistory(
        id: String,
        request: DownloadRequest,
        filePath: String?,
        contentUri: String?,
        succeeded: Boolean,
        error: String?,
        canceled: Boolean,
        sizeBytes: Long? = null,
        ext: String? = null,
    ) {
        if (canceled) return
        val task = taskById(id)
        val dao = dao() ?: return
        runCatching {
            dao.upsert(
                HistoryEntity(
                    id = id,
                    url = request.url,
                    title = task?.title ?: request.url,
                    thumbnail = task?.thumbnail,
                    filePath = filePath,
                    contentUri = contentUri,
                    ext = ext ?: task?.ext,
                    sizeBytes = sizeBytes,
                    durationSeconds = null,
                    isAudioOnly = request.settings.audioOnly,
                    succeeded = succeeded,
                    error = error,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun taskById(id: String) = _tasks.value.firstOrNull { it.id == id }

    private fun patch(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    suspend fun currentSettings() = settingsRepo?.settings?.first() ?: com.teal.downloader.data.model.DownloadSettings()
}
