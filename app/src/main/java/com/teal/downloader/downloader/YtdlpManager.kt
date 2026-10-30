package com.teal.downloader.downloader

import android.content.Context
import com.teal.downloader.data.model.DownloadRequest
import com.teal.downloader.data.model.DownloadSettings
import com.teal.downloader.data.model.ProgressParser
import com.teal.downloader.data.model.ProgressUpdate
import com.teal.downloader.data.model.TealJson
import com.teal.downloader.data.model.YtdlInfo
import com.teal.downloader.util.FileUtil
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the library reports while a download runs. */
private typealias ProgressCallback = (Float, Long, String) -> Unit

/**
 * The only place the yt-dlp library is touched.
 *
 * Everything above this layer deals in [DownloadRequest], [YtdlInfo] and
 * callbacks, so the JNI-backed library stays swappable and the rest of the app
 * stays testable.
 */
class YtdlpManager(private val context: Context) {

    private val initMutex = Mutex()

    @Volatile
    private var initialised = false

    /**
     * Extracts Python, yt-dlp, ffmpeg and aria2c from the APK on first call.
     * Slow (a few seconds) and must not run on the main thread; idempotent
     * afterwards.
     */
    suspend fun ensureInitialised() {
        if (initialised) return
        initMutex.withLock {
            if (initialised) return
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.init(context)
                    FFmpeg.init(context)
                    Aria2c.init(context)
                } catch (e: Throwable) {
                    // The library reports every unpacking failure as a bare
                    // "failed to initialize" with the real reason buried in the
                    // cause chain, which tells the user nothing. Unwrap it, and
                    // say so plainly when the cause is the one that actually
                    // bites: the Python runtime never being extracted.
                    throw DownloadException(describeInitFailure(e))
                }
            }
            initialised = true
        }
    }

    private fun describeInitFailure(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.take(6).last()
        val detail = root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
        val runtime = File(context.applicationInfo.nativeLibraryDir, "libpython.zip.so")
        return if (runtime.exists()) {
            "Could not start the download engine: $detail"
        } else {
            "Could not start the download engine ($detail). The bundled Python runtime was " +
                "not extracted into this install — reinstalling usually fixes it."
        }
    }

    fun ytDlpVersion(): String? = runCatching { YoutubeDL.version(context) }.getOrNull()

    /**
     * Replaces the bundled yt-dlp with the newest stable release.
     *
     * The copy inside the APK is frozen at build time, and YouTube changes its
     * player often enough that a months-old yt-dlp stops being able to extract
     * from it. This is the way back to a working download without waiting on a
     * new app release.
     */
    suspend fun updateYtDlp(): String = withContext(Dispatchers.IO) {
        ensureInitialised()
        val status = runCatching {
            YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        }.getOrElse {
            throw DownloadException(
                "Could not update yt-dlp: ${it.message ?: it.javaClass.simpleName}"
            )
        }
        val version = ytDlpVersion() ?: "the latest version"
        when (status) {
            YoutubeDL.UpdateStatus.DONE -> "Updated yt-dlp to $version"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date ($version)"
            null -> "Could not check for updates"
        }
    }

    /** Reads metadata for [url] without downloading anything. */
    suspend fun fetchInfo(
        url: String,
        settings: DownloadSettings = DownloadSettings(),
        flatPlaylist: Boolean = false,
    ): YtdlInfo = withContext(Dispatchers.IO) {
        ensureInitialised()
        val request = YoutubeDLRequest(url)
            .addCommands(CommandBuilder.infoArgs(settings, flatPlaylist))
        val response = runCatching { YoutubeDL.execute(request, null, null) }
            .getOrElse { throw DownloadException.describe(it, url) }
        if (response.exitCode != 0) {
            throw DownloadException(
                firstMeaningfulLine(response.err) ?: "yt-dlp exited with ${response.exitCode}",
            )
        }
        runCatching { TealJson.decodeFromString<YtdlInfo>(response.out) }
            .getOrElse { throw DownloadException("Could not read the video information") }
    }

    /**
     * Runs a download to completion.
     *
     * @param processId also the cancel handle: pass it to [cancel].
     * @return the file yt-dlp produced.
     */
    suspend fun download(
        url: String,
        request: DownloadRequest,
        outputDir: File,
        processId: String,
        onProgress: (ProgressUpdate) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureInitialised()
        outputDir.mkdirs()
        val startedAt = System.currentTimeMillis()
        val ytdlRequest = YoutubeDLRequest(url)
            .addCommands(CommandBuilder.downloadArgs(request, outputDir.absolutePath))

        val callback: ProgressCallback = { progress, eta, line ->
            onProgress(
                ProgressUpdate(
                    percent = progress.coerceIn(0f, 100f),
                    etaSeconds = eta.takeIf { it > 0 },
                    speedBytesPerSecond = ProgressParser.parseSpeed(line),
                    totalBytes = ProgressParser.parseTotalBytes(line),
                    fragmentPercent = ProgressParser.parseFragmentProgress(line),
                    postProcessing = ProgressParser.isPostProcessing(line),
                    rawLine = line,
                ),
            )
        }

        val response = try {
            YoutubeDL.execute(ytdlRequest, processId, callback)
        } catch (canceled: YoutubeDL.CanceledException) {
            throw CanceledException()
        } catch (e: Exception) {
            throw DownloadException.describe(e, url)
        }

        if (response.exitCode != 0) {
            throw DownloadException(firstMeaningfulLine(response.err) ?: "Download failed")
        }

        FileUtil.newestFileSince(outputDir, startedAt)
            ?: throw DownloadException("yt-dlp finished but produced no file")
    }

    /** Kills the running process behind [processId]; yt-dlp's own child too. */
    fun cancel(processId: String): Boolean =
        runCatching { YoutubeDL.destroyProcessById(processId) }.getOrDefault(false)

    /** yt-dlp writes a lot to stderr; the last real line is the useful one. */
    private fun firstMeaningfulLine(stderr: String): String? =
        stderr.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull { it.startsWith("ERROR") || it.contains("ERROR:") }
            ?: stderr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
}

/** A download failed for a reason worth showing the user. */
class DownloadException(message: String) : Exception(message) {
    companion object {
        fun describe(error: Throwable, url: String): DownloadException {
            val text = (error as? YoutubeDLException)?.message ?: error.message ?: error.javaClass.simpleName
            return DownloadException(classify(text, url))
        }

        /** Map yt-dlp's prose onto short, actionable messages. */
        fun classify(text: String, url: String): String {
            val lower = text.lowercase()
            return when {
                "unsupported url" in lower -> "This site or URL is not supported by yt-dlp"
                "sign in" in lower || "login" in lower || "account" in lower ->
                    "This video needs an account — add a cookies file in Settings"
                "private video" in lower -> "That video is private"
                "members-only" in lower || "members only" in lower -> "That video is members-only"
                "age" in lower && "restrict" in lower -> "That video is age-restricted"
                "not available" in lower && "country" in lower -> "That video is not available in your country"
                "premieres" in lower -> "That video has not premiered yet"
                "unable to download" in lower || "urlopen error" in lower || "timed out" in lower ||
                    "temporary failure" in lower -> "Network problem reaching $url"
                "no space left" in lower -> "Not enough storage space left"
                "ffmpeg" in lower && "not found" in lower -> "ffmpeg is missing from this build"
                "http error 404" in lower || "404" in lower -> "That video no longer exists (404)"
                "http error 403" in lower || "403" in lower -> "Access denied by the site (403)"
                else -> text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Download failed"
            }
        }
    }
}

/** The user cancelled; not an error, so it gets its own type. */
class CanceledException : Exception("Canceled by user")
