package com.teal.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teal.downloader.data.db.CommandTemplateEntity
import com.teal.downloader.data.db.HistoryEntity
import com.teal.downloader.data.db.TealDatabase
import com.teal.downloader.data.model.DownloadRequest
import com.teal.downloader.data.model.DownloadSettings
import com.teal.downloader.data.model.DownloadTask
import com.teal.downloader.data.model.YtdlInfo
import com.teal.downloader.data.prefs.SettingsRepository
import com.teal.downloader.downloader.DownloadException
import com.teal.downloader.downloader.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the home screen knows about the URL currently being looked at. */
data class HomeUiState(
    val url: String = "",
    val info: YtdlInfo? = null,
    val isFetching: Boolean = false,
    val error: String? = null,
    /** Null means "use my default format preference", not "no format". */
    val selectedVideoFormatId: String? = null,
    val selectedAudioFormatId: String? = null,
    val audioOnly: Boolean = false,
) {
    val hasInfo: Boolean get() = info != null
    val isPlaylist: Boolean get() = info?.isPlaylist == true
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepository = SettingsRepository(app)
    private val database = TealDatabase.get(app)

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _ytDlpVersion = MutableStateFlow<String?>(null)
    val ytDlpVersion: StateFlow<String?> = _ytDlpVersion.asStateFlow()

    /** Result of the last yt-dlp update attempt, shown under the version row. */
    private val _ytDlpUpdateState = MutableStateFlow<String?>(null)
    val ytDlpUpdateState: StateFlow<String?> = _ytDlpUpdateState.asStateFlow()

    private val _ytDlpUpdating = MutableStateFlow(false)
    val ytDlpUpdating: StateFlow<Boolean> = _ytDlpUpdating.asStateFlow()

    val tasks: StateFlow<List<DownloadTask>> = DownloadManager.tasks

    val settings: StateFlow<DownloadSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSettings())

    val publishToDownloads: StateFlow<Boolean> = settingsRepository.publishToDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val maxConcurrentDownloads: StateFlow<Int> = settingsRepository.maxConcurrentDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    val history: StateFlow<List<HistoryEntity>> = database.historyDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val templates: StateFlow<List<CommandTemplateEntity>> = database.commandTemplateDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { _ytDlpVersion.value = DownloadManager.ytDlpVersion() }
    }

    // -- URL handling -------------------------------------------------------

    fun onUrlChange(value: String) {
        _home.update { it.copy(url = value, error = null) }
    }

    fun onSharedLink(value: String) {
        // A shared link replaces whatever was typed: the user just came from
        // another app and means this one.
        _home.update { it.copy(url = value.trim(), info = null, error = null,
            selectedVideoFormatId = null, selectedAudioFormatId = null) }
        fetchInfo()
    }

    fun clearUrl() {
        _home.update { HomeUiState() }
    }

    fun fetchInfo() {
        val url = _home.value.url.trim()
        if (url.isBlank()) {
            _home.update { it.copy(error = "Enter a link first") }
            return
        }
        if (!looksLikeUrl(url)) {
            _home.update { it.copy(error = "That does not look like a link") }
            return
        }
        _home.update { it.copy(isFetching = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                // Flat: for a playlist URL this returns the entry list without
                // resolving every video first, which on a long playlist is the
                // difference between a card appearing and a minute of spinner.
                // The entry list is all this screen renders, and each entry is
                // resolved individually when it is downloaded. For a single
                // video URL the flag is a no-op, so formats still come back.
                DownloadManager.fetchInfo(url, flatPlaylist = true)
            }
            _home.update { state ->
                result.fold(
                    onSuccess = { info ->
                        state.copy(isFetching = false, info = info, error = null)
                    },
                    onFailure = { error ->
                        state.copy(
                            isFetching = false,
                            info = null,
                            // fetchInfo already translated this into something a
                            // person can act on. Classifying it a second time
                            // would read Teal's own wording as if it were
                            // yt-dlp's and could land on the wrong branch.
                            error = (error as? DownloadException)?.message
                                ?: DownloadException
                                    .classify(error.message ?: "Could not read that link", url)
                                    .message,
                        )
                    },
                )
            }
        }
    }

    private fun looksLikeUrl(value: String): Boolean {
        val scheme = value.substringBefore("://", "").lowercase()
        if (scheme !in setOf("http", "https")) return false
        val rest = value.substringAfter("://")
        return rest.isNotBlank() && rest.contains('.')
    }

    // -- format selection ---------------------------------------------------

    fun selectVideoFormat(formatId: String?) =
        _home.update { it.copy(selectedVideoFormatId = formatId, audioOnly = false) }

    fun selectAudioFormat(formatId: String?) =
        _home.update { it.copy(selectedAudioFormatId = formatId, audioOnly = true) }

    fun setAudioOnly(value: Boolean) = _home.update { it.copy(audioOnly = value) }

    // -- downloading --------------------------------------------------------

    fun download(audioOnly: Boolean = _home.value.audioOnly) {
        val state = _home.value
        val url = state.url.trim()
        if (url.isBlank()) {
            _home.update { it.copy(error = "Enter a link first") }
            return
        }
        viewModelScope.launch {
            val base = settings.value
            val effective = base.copy(audioOnly = audioOnly || base.audioOnly)
            val request = DownloadRequest(
                url = url,
                formatId = if (audioOnly) state.selectedAudioFormatId else state.selectedVideoFormatId,
                audioFormatId = if (audioOnly) null else state.selectedAudioFormatId,
                title = state.info?.displayTitle,
                settings = effective,
            )
            DownloadManager.enqueue(request, state.info?.displayTitle, state.info?.thumbnail)
        }
        // Leave the field ready for the next link; the queue tab shows progress.
        _home.update { HomeUiState() }
    }

    /** Downloads a specific entry out of a playlist. */
    fun downloadEntry(url: String, title: String?) {
        viewModelScope.launch {
            DownloadManager.enqueue(
                DownloadRequest(url = url, settings = settings.value, title = title),
                title,
                null,
            )
        }
    }

    fun cancelTask(id: String) = DownloadManager.cancel(id)
    fun cancelAll() = DownloadManager.cancelAll()
    fun retryTask(id: String) = DownloadManager.retry(id)
    fun removeTask(id: String) = DownloadManager.remove(id)
    fun clearFinishedTasks() = DownloadManager.clearFinished()

    // -- history ------------------------------------------------------------

    fun deleteHistory(id: String) = viewModelScope.launch { database.historyDao().deleteById(id) }
    fun clearHistory() = viewModelScope.launch { database.historyDao().clear() }

    // -- settings -----------------------------------------------------------

    fun updateSettings(transform: (DownloadSettings) -> DownloadSettings) =
        viewModelScope.launch { settingsRepository.update(transform) }

    fun setPublishToDownloads(value: Boolean) =
        viewModelScope.launch { settingsRepository.setPublishToDownloads(value) }

    fun setMaxConcurrentDownloads(value: Int) =
        viewModelScope.launch { settingsRepository.setMaxConcurrentDownloads(value) }

    /**
     * Fetches the newest stable yt-dlp.
     *
     * Worth having because the bundled copy is frozen at build time and YouTube
     * breaks older extractors often; without it the only fix is a new APK.
     */
    fun updateYtDlp() = viewModelScope.launch {
        if (_ytDlpUpdating.value) return@launch
        _ytDlpUpdating.value = true
        _ytDlpUpdateState.value = try {
            DownloadManager.updateYtDlp()
        } catch (e: Exception) {
            // Surfaced inline: a failed update is not fatal, and a dialog would
            // be more intrusive than the row the action belongs to.
            e.message ?: "Could not update yt-dlp"
        } finally {
            _ytDlpUpdating.value = false
        }
        _ytDlpVersion.value = DownloadManager.ytDlpVersion()
    }

    // -- command templates --------------------------------------------------

    fun saveTemplate(name: String, content: String, existingId: Long? = null) =
        viewModelScope.launch {
            if (name.isBlank() || content.isBlank()) return@launch
            database.commandTemplateDao().upsert(
                CommandTemplateEntity(
                    id = existingId ?: 0,
                    name = name.trim(),
                    content = content.trim(),
                ),
            )
        }

    fun deleteTemplate(id: Long) =
        viewModelScope.launch { database.commandTemplateDao().deleteById(id) }

    fun runTemplate(template: CommandTemplateEntity, url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            DownloadManager.enqueue(
                DownloadRequest(
                    url = url,
                    settings = settings.value.copy(customArguments = template.content),
                    title = template.name,
                ),
                template.name,
                null,
            )
        }
    }
}
