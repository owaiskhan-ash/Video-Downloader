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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the library reports while a download runs. */
private typealias ProgressCallback = (Float, Long, String) -> Unit

/** yt-dlp writes a lot to stderr; the last real line is the useful one. */
internal fun firstMeaningfulLine(stderr: String): String? =
    stderr.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .lastOrNull { it.startsWith("ERROR") || it.contains("ERROR:") }
        ?: stderr.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()

/**
 * The only place the yt-dlp library is touched.
 *
 * Everything above this layer deals in [DownloadRequest], [YtdlInfo] and
 * callbacks, so the JNI-backed library stays swappable and the rest of the app
 * stays testable.
 *
 * The engine also looks after itself here. The yt-dlp binary inside the APK is
 * frozen at build time while sites keep changing their players, so an app that
 * cannot refresh it starts failing weeks after release and every failure looks
 * like the user did something wrong. Refreshing is automatic and a stale engine
 * is retried once without being asked.
 */
class YtdlpManager(private val context: Context) {

    private val initMutex = Mutex()

    @Volatile
    private var initialised = false

    /** Recently fetched metadata; see [fetchInfo]. */
    private val infoCache = ConcurrentHashMap<InfoKey, CachedInfo>()

    private data class InfoKey(val url: String, val flatPlaylist: Boolean, val network: String)

    private data class CachedInfo(val at: Long, val info: YtdlInfo)

    /**
     * Deliberately SharedPreferences rather than the DataStore the settings use:
     * this is engine bookkeeping that no screen binds to, and reading it must
     * not suspend.
     */
    private val enginePrefs by lazy {
        context.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE)
    }

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

    /**
     * Initialises the engine and refreshes yt-dlp if one is due.
     *
     * This is the slow part, and it is deliberately *not* part of
     * [ensureInitialised]: unzipping the Python runtime takes seconds and a due
     * refresh is a download from GitHub bounded at 45s. Both used to run on the
     * first paste, behind a spinner the user was watching. They now run at app
     * start, overlapping with the time spent finding and copying a link.
     *
     * A refresh is still performed on demand by [withEngineRetry], where a
     * download has already failed and the wait is buying something.
     */
    suspend fun prepare() {
        try {
            ensureInitialised()
            refreshEngineIfDue()
        } catch (canceled: CancellationException) {
            // Not ours to swallow: this can be a 45s network call, and eating a
            // cancellation would leave the caller thinking it had finished.
            throw canceled
        } catch (e: Exception) {
            // Anything else is a warm-up that simply did not complete. The next
            // real fetch initialises again and reports properly if the engine is
            // genuinely broken, so there is nothing worth surfacing here.
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
     * @return the library's verdict, or null when the check itself failed (no
     *   network, GitHub unreachable). A failed check leaves the bundled copy in
     *   place and working, so it is never fatal.
     */
    private suspend fun refreshEngine(): YoutubeDL.UpdateStatus? = withContext(Dispatchers.IO) {
        // Bounded, because this can run while the user waits on their first
        // download: withTimeoutOrNull rather than a caught TimeoutCancellation,
        // so a slow or absent network yields null instead of swallowing a
        // cancellation that belongs to structured concurrency.
        val status = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            runCatching {
                YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            }.getOrNull()
        }
        val now = System.currentTimeMillis()
        val editor = enginePrefs.edit().putLong(KEY_LAST_ATTEMPT, now)
        if (status != null) editor.putLong(KEY_LAST_SUCCESS, now)
        editor.apply()
        status
    }

    /**
     * Refresh on first run, then weekly.
     *
     * A first launch with no network would otherwise leave the app on a
     * build-time yt-dlp forever, so until one attempt has succeeded it retries
     * hourly instead. Between those, it waits a week — checking on every cold
     * start would put a network round-trip in front of every download.
     */
    private suspend fun refreshEngineIfDue() {
        val now = System.currentTimeMillis()
        val lastSuccess = enginePrefs.getLong(KEY_LAST_SUCCESS, 0L)
        val lastAttempt = enginePrefs.getLong(KEY_LAST_ATTEMPT, 0L)
        val due = if (lastSuccess > 0L) {
            now - lastSuccess >= REFRESH_INTERVAL_MS
        } else {
            now - lastAttempt >= RETRY_AFTER_FAILURE_MS
        }
        if (due) refreshEngine()
    }

    /**
     * Runs [block], and if it fails because the engine is behind the site,
     * refreshes the engine and runs it once more.
     *
     * This is the difference between an app that works and one that hands the
     * user a message about cookies files and Settings screens.
     */
    private suspend fun <T> withEngineRetry(block: suspend () -> T): T =
        try {
            block()
        } catch (e: DownloadException) {
            if (!e.staleEngine) throw e
            // Only worth a second attempt when the binary actually changed. With
            // the same yt-dlp it fails identically and just costs the user time.
            if (refreshEngine() != YoutubeDL.UpdateStatus.DONE) throw e
            try {
                block()
            } catch (retry: DownloadException) {
                // The retry was the last resort, so the wording must not promise
                // another attempt that is not coming.
                throw if (retry.staleEngine) {
                    DownloadException(DownloadException.STILL_BLOCKED_AFTER_REFRESH)
                } else {
                    retry
                }
            }
        }

    /**
     * Replaces the bundled yt-dlp with the newest stable release, on demand.
     *
     * Automatic refreshes cover the common case; this is the manual button for
     * someone who wants a newer engine right now.
     */
    suspend fun updateYtDlp(): String {
        ensureInitialised()
        val status = refreshEngine()
        val version = ytDlpVersion() ?: "the latest version"
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "Updated yt-dlp to $version"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date ($version)"
            null -> "Could not check for updates — the bundled version is still in place"
        }
    }

    /**
     * Reads metadata for [url] without downloading anything.
     *
     * Results are cached briefly and keyed on everything that could change the
     * answer, so re-pasting a link — or fetching again after a failure — costs
     * no second round trip to the site. A cache hit does not even touch the
     * engine.
     */
    suspend fun fetchInfo(
        url: String,
        settings: DownloadSettings = DownloadSettings(),
        flatPlaylist: Boolean = false,
    ): YtdlInfo {
        val key = InfoKey(
            url = url,
            flatPlaylist = flatPlaylist,
            // The three settings that change what the site returns.
            network = "${settings.proxy}|${settings.cookiesFile}|${settings.userAgent}",
        )
        cachedInfo(key)?.let { return it }

        val info = withContext(Dispatchers.IO) {
            ensureInitialised()
            withEngineRetry { runFetchInfo(url, settings, flatPlaylist) }
        }
        rememberInfo(key, info)
        return info
    }

    private fun cachedInfo(key: InfoKey): YtdlInfo? {
        val entry = infoCache[key] ?: return null
        if (System.currentTimeMillis() - entry.at > INFO_CACHE_TTL_MS) {
            infoCache.remove(key)
            return null
        }
        return entry.info
    }

    private fun rememberInfo(key: InfoKey, info: YtdlInfo) {
        // Bounded, because this lives as long as the process. Evicting the
        // oldest is enough: a handful of entries covers a normal session.
        if (infoCache.size >= INFO_CACHE_MAX) {
            infoCache.keys.minByOrNull { infoCache[it]?.at ?: 0L }?.let { infoCache.remove(it) }
        }
        infoCache[key] = CachedInfo(System.currentTimeMillis(), info)
    }

    private suspend fun runFetchInfo(
        url: String,
        settings: DownloadSettings,
        flatPlaylist: Boolean,
    ): YtdlInfo {
        val request = YoutubeDLRequest(url)
            .addCommands(CommandBuilder.infoArgs(settings, flatPlaylist))
        val response = runCatching { YoutubeDL.execute(request, null, null) }
            .getOrElse { throw DownloadException.describe(it, url) }
        if (response.exitCode != 0) throw DownloadException.forStderr(response.err, url)
        return runCatching { TealJson.decodeFromString<YtdlInfo>(response.out) }
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
        withEngineRetry { runDownload(url, request, outputDir, processId, onProgress) }
    }

    private suspend fun runDownload(
        url: String,
        request: DownloadRequest,
        outputDir: File,
        processId: String,
        onProgress: (ProgressUpdate) -> Unit,
    ): File {
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

        if (response.exitCode != 0) throw DownloadException.forStderr(response.err, url)

        return FileUtil.newestFileSince(outputDir, startedAt)
            ?: throw DownloadException("yt-dlp finished but produced no file")
    }

    /** Kills the running process behind [processId]; yt-dlp's own child too. */
    fun cancel(processId: String): Boolean =
        runCatching { YoutubeDL.destroyProcessById(processId) }.getOrDefault(false)

    private companion object {
        const val ENGINE_PREFS = "teal_engine"
        const val KEY_LAST_SUCCESS = "last_refresh_success"
        const val KEY_LAST_ATTEMPT = "last_refresh_attempt"
        const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        const val RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000
        const val REFRESH_TIMEOUT_MS = 45_000L
        const val INFO_CACHE_TTL_MS = 5L * 60 * 1000
        const val INFO_CACHE_MAX = 16
    }
}

/**
 * What a failure means, and whether refreshing yt-dlp is likely to clear it.
 *
 * [staleEngine] drives an automatic retry, so it is deliberately narrow: only
 * failures caused by the extractor being behind the site, never the ones a
 * refresh cannot help (private video, no storage, wrong country).
 */
data class Diagnosis(val message: String, val staleEngine: Boolean = false)

/** A download failed for a reason worth showing the user. */
class DownloadException(
    message: String,
    /** True when a newer yt-dlp would probably fix this; see [withEngineRetry]. */
    val staleEngine: Boolean = false,
) : Exception(message) {
    companion object {

        /** Shown when refreshing yt-dlp did not clear a site-level block. */
        const val STILL_BLOCKED_AFTER_REFRESH =
            "That site is still refusing the download after Teal refreshed its downloader. " +
                "This is usually a temporary block on your network — try again in a little " +
                "while, or on mobile data instead of Wi-Fi."

        fun describe(error: Throwable, url: String): DownloadException {
            val text = (error as? YoutubeDLException)?.message
                ?: error.message ?: error.javaClass.simpleName
            val diagnosis = classify(text, url)
            return DownloadException(diagnosis.message, diagnosis.staleEngine)
        }

        /**
         * yt-dlp reports most real failures by exiting non-zero with the reason
         * on stderr rather than by throwing, so this path needs the same
         * translation as [describe]. Without it the user is handed raw yt-dlp
         * prose and no automatic retry happens.
         */
        fun forStderr(stderr: String, url: String): DownloadException {
            val line = firstMeaningfulLine(stderr) ?: "Download failed"
            val diagnosis = classify(line, url)
            return DownloadException(diagnosis.message, diagnosis.staleEngine)
        }

        /**
         * Map yt-dlp's prose onto short, actionable messages.
         *
         * Branch order is the behaviour here: the most specific reading of a
         * message has to win. YouTube's anti-bot wall says "Sign in", but it is
         * an extractor problem, so it is matched before the generic sign-in
         * branch; "Private video. Sign in if you've been granted access" has to
         * reach the private branch for the same reason.
         */
        fun classify(text: String, url: String): Diagnosis {
            val lower = text.lowercase()
            return when {
                "unsupported url" in lower ->
                    Diagnosis("That link is not a video Teal can download")

                // YouTube's anti-bot wall. Reads like an account problem but is
                // almost always the extractor being behind the site, and it is
                // the failure people actually hit — so it is Teal's to fix, not
                // the user's.
                "not a bot" in lower -> Diagnosis(
                    "YouTube is checking this request. Teal is refreshing its downloader " +
                        "and trying again.",
                    staleEngine = true,
                )

                "unable to extract" in lower || "nsig" in lower ||
                    ("signature" in lower && "extract" in lower) ||
                    "initial data" in lower || "please update" in lower ||
                    "update yt-dlp" in lower -> Diagnosis(
                    "That site has changed since this downloader was built. Teal is " +
                        "refreshing it and trying again.",
                    staleEngine = true,
                )

                // Checked before the sign-in branch: YouTube's private-video
                // message invites you to sign in, which is not the problem.
                "private video" in lower -> Diagnosis("That video is private")
                "members-only" in lower || "members only" in lower ->
                    Diagnosis("That video is for paying channel members only")
                "age" in lower && "restrict" in lower ->
                    Diagnosis("That video is age-restricted")
                "premieres" in lower -> Diagnosis("That video has not premiered yet")

                "sign in" in lower || "login" in lower || "log in" in lower -> Diagnosis(
                    "That video needs an account. If you have one, add a cookies file " +
                        "under Settings › Advanced.",
                )

                "requested format is not available" in lower ||
                    "no video formats" in lower -> Diagnosis(
                    "That quality is not available for this video — try a different one",
                )
                "not available" in lower && "country" in lower ->
                    Diagnosis("That video is not available in your country")
                "unable to download" in lower || "urlopen error" in lower ||
                    "timed out" in lower || "temporary failure" in lower ->
                    Diagnosis("Could not reach $url — check your connection")
                "no space left" in lower -> Diagnosis("Not enough storage space left")
                "ffmpeg" in lower && "not found" in lower ->
                    Diagnosis("ffmpeg is missing from this build")
                "http error 404" in lower || "404" in lower ->
                    Diagnosis("That video no longer exists")
                "http error 403" in lower || "403" in lower ->
                    Diagnosis("That site refused the request (403)")
                else -> Diagnosis(
                    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                        ?: "Download failed",
                )
            }
        }
    }
}

/** The user cancelled; not an error, so it gets its own type. */
class CanceledException : Exception("Canceled by user")
