package com.teal.downloader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teal.downloader.data.model.AudioFormat
import com.teal.downloader.data.model.DownloadSettings
import com.teal.downloader.data.model.VideoFormatPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "teal_settings")

/**
 * Persisted [DownloadSettings].
 *
 * Each key is stored individually rather than serialising the whole object, so
 * adding a setting later does not invalidate what is already on disk.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<DownloadSettings> = context.dataStore.data.map(::settingsFromPrefs)

    /** Where finished files are published: public Downloads, or app-private. */
    val publishToDownloads: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PUBLISH_TO_DOWNLOADS] ?: true }

    val maxConcurrentDownloads: Flow<Int> =
        context.dataStore.data.map { (it[Keys.MAX_CONCURRENT] ?: 2).coerceIn(1, 5) }

    /**
     * Read-modify-write. `first()` matters here: `dataStore.data` is an endless
     * Flow, so collecting it to read one value would suspend forever.
     */
    suspend fun update(transform: (DownloadSettings) -> DownloadSettings) {
        val current = settingsFromPrefs(context.dataStore.data.first())
        write(transform(current))
    }

    private fun settingsFromPrefs(p: Preferences) = DownloadSettings(
        audioOnly = p[Keys.AUDIO_ONLY] ?: false,
        audioFormat = p[Keys.AUDIO_FORMAT]?.let { runCatching { AudioFormat.valueOf(it) }.getOrNull() }
            ?: AudioFormat.MP3,
        videoPreference = p[Keys.VIDEO_PREFERENCE]?.let {
            runCatching { VideoFormatPreference.valueOf(it) }.getOrNull()
        } ?: VideoFormatPreference.COMPATIBLE,
        preferredHeight = p[Keys.PREFERRED_HEIGHT]?.takeIf { it > 0 },
        embedMetadata = p[Keys.EMBED_METADATA] ?: true,
        embedThumbnail = p[Keys.EMBED_THUMBNAIL] ?: false,
        writeSubtitles = p[Keys.WRITE_SUBTITLES] ?: false,
        embedSubtitles = p[Keys.EMBED_SUBTITLES] ?: false,
        subtitleLanguages = p[Keys.SUBTITLE_LANGUAGES] ?: "en",
        removeSponsorSegments = p[Keys.SPONSORBLOCK] ?: false,
        useAria2c = p[Keys.USE_ARIA2C] ?: false,
        aria2cConnections = p[Keys.ARIA2C_CONNECTIONS] ?: 8,
        concurrentFragments = p[Keys.CONCURRENT_FRAGMENTS] ?: 4,
        retries = p[Keys.RETRIES] ?: 3,
        restrictFilenames = p[Keys.RESTRICT_FILENAMES] ?: false,
        noPlaylist = p[Keys.NO_PLAYLIST] ?: true,
        playlistItems = p[Keys.PLAYLIST_ITEMS] ?: "",
        rateLimit = p[Keys.RATE_LIMIT] ?: "",
        proxy = p[Keys.PROXY] ?: "",
        userAgent = p[Keys.USER_AGENT] ?: "",
        cookiesFile = p[Keys.COOKIES_FILE] ?: "",
        outputTemplate = p[Keys.OUTPUT_TEMPLATE] ?: DownloadSettings.DEFAULT_OUTPUT_TEMPLATE,
        customArguments = p[Keys.CUSTOM_ARGUMENTS] ?: "",
    )

    private suspend fun write(s: DownloadSettings) {
        context.dataStore.edit { p ->
            p[Keys.AUDIO_ONLY] = s.audioOnly
            p[Keys.AUDIO_FORMAT] = s.audioFormat.name
            p[Keys.VIDEO_PREFERENCE] = s.videoPreference.name
            s.preferredHeight?.let { p[Keys.PREFERRED_HEIGHT] = it } ?: p.remove(Keys.PREFERRED_HEIGHT)
            p[Keys.EMBED_METADATA] = s.embedMetadata
            p[Keys.EMBED_THUMBNAIL] = s.embedThumbnail
            p[Keys.WRITE_SUBTITLES] = s.writeSubtitles
            p[Keys.EMBED_SUBTITLES] = s.embedSubtitles
            p[Keys.SUBTITLE_LANGUAGES] = s.subtitleLanguages
            p[Keys.SPONSORBLOCK] = s.removeSponsorSegments
            p[Keys.USE_ARIA2C] = s.useAria2c
            p[Keys.ARIA2C_CONNECTIONS] = s.aria2cConnections
            p[Keys.CONCURRENT_FRAGMENTS] = s.concurrentFragments
            p[Keys.RETRIES] = s.retries
            p[Keys.RESTRICT_FILENAMES] = s.restrictFilenames
            p[Keys.NO_PLAYLIST] = s.noPlaylist
            p[Keys.PLAYLIST_ITEMS] = s.playlistItems
            p[Keys.RATE_LIMIT] = s.rateLimit
            p[Keys.PROXY] = s.proxy
            p[Keys.USER_AGENT] = s.userAgent
            p[Keys.COOKIES_FILE] = s.cookiesFile
            p[Keys.OUTPUT_TEMPLATE] = s.outputTemplate
            p[Keys.CUSTOM_ARGUMENTS] = s.customArguments
        }
    }

    suspend fun setPublishToDownloads(value: Boolean) {
        context.dataStore.edit { it[Keys.PUBLISH_TO_DOWNLOADS] = value }
    }

    suspend fun setMaxConcurrentDownloads(value: Int) {
        context.dataStore.edit { it[Keys.MAX_CONCURRENT] = value.coerceIn(1, 5) }
    }

    private object Keys {
        val AUDIO_ONLY = booleanPreferencesKey("audio_only")
        val AUDIO_FORMAT = stringPreferencesKey("audio_format")
        val VIDEO_PREFERENCE = stringPreferencesKey("video_preference")
        val PREFERRED_HEIGHT = intPreferencesKey("preferred_height")
        val EMBED_METADATA = booleanPreferencesKey("embed_metadata")
        val EMBED_THUMBNAIL = booleanPreferencesKey("embed_thumbnail")
        val WRITE_SUBTITLES = booleanPreferencesKey("write_subtitles")
        val EMBED_SUBTITLES = booleanPreferencesKey("embed_subtitles")
        val SUBTITLE_LANGUAGES = stringPreferencesKey("subtitle_languages")
        val SPONSORBLOCK = booleanPreferencesKey("sponsorblock")
        val USE_ARIA2C = booleanPreferencesKey("use_aria2c")
        val ARIA2C_CONNECTIONS = intPreferencesKey("aria2c_connections")
        val CONCURRENT_FRAGMENTS = intPreferencesKey("concurrent_fragments")
        val RETRIES = intPreferencesKey("retries")
        val RESTRICT_FILENAMES = booleanPreferencesKey("restrict_filenames")
        val NO_PLAYLIST = booleanPreferencesKey("no_playlist")
        val PLAYLIST_ITEMS = stringPreferencesKey("playlist_items")
        val RATE_LIMIT = stringPreferencesKey("rate_limit")
        val PROXY = stringPreferencesKey("proxy")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val COOKIES_FILE = stringPreferencesKey("cookies_file")
        val OUTPUT_TEMPLATE = stringPreferencesKey("output_template")
        val CUSTOM_ARGUMENTS = stringPreferencesKey("custom_arguments")
        val PUBLISH_TO_DOWNLOADS = booleanPreferencesKey("publish_to_downloads")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
    }
}
