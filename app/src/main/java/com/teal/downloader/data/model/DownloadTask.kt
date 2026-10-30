package com.teal.downloader.data.model

/** Lifecycle of one download, as the UI sees it. */
enum class TaskStatus {
    QUEUED,
    RUNNING,
    POST_PROCESSING,
    COMPLETED,
    FAILED,
    CANCELED,
}

/**
 * In-memory state for a queued or running download.
 *
 * [id] doubles as the yt-dlp process id passed to `execute()`, which is what
 * makes cancellation possible: `YoutubeDL.destroyProcessById(id)`.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val status: TaskStatus = TaskStatus.QUEUED,
    /** 0f..100f. yt-dlp reports whole percentages. */
    val progress: Float = 0f,
    val speedBytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long? = null,
    /** Where yt-dlp wrote the file (app-private staging directory). */
    val filePath: String? = null,
    /** Where it ended up after publishing to Downloads, if that is enabled. */
    val contentUri: String? = null,
    val error: String? = null,
    val isAudioOnly: Boolean = false,
    val ext: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val isActive: Boolean
        get() = status == TaskStatus.QUEUED || status == TaskStatus.RUNNING ||
            status == TaskStatus.POST_PROCESSING
}

/**
 * yt-dlp prints its own progress lines; the library hands them to us raw.
 * Speed and size are in the text, so parse them rather than guess.
 *
 * Typical line:
 * `[download]  42.3% of  ~123.45MiB at    1.23MiB/s ETA 00:41`
 */
object ProgressParser {
    private val SPEED = Regex("""at\s+([0-9]*\.?[0-9]+)\s*([KMGTP]?i?B)/s""")
    private val SIZE = Regex("""of\s+~?\s*([0-9]*\.?[0-9]+)\s*([KMGTP]?i?B)""")
    /**
     * Fragment counters arrive as a suffix on an ordinary progress line, not on
     * a line of their own — `(frag 5/10)` when yt-dlp knows the total, `(frag 5)`
     * when it does not. See yt_dlp/downloader/common.py, report_progress.
     */
    private val FRAGMENT = Regex("""frag(?:ment)?\s+(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parseSpeed(line: String?): Double? {
        val m = SPEED.find(line ?: return null) ?: return null
        return toBytesPerSecond(m.groupValues[1].toDoubleOrNull() ?: return null, m.groupValues[2])
    }

    fun parseTotalBytes(line: String?): Long? {
        val m = SIZE.find(line ?: return null) ?: return null
        return toBytesPerSecond(m.groupValues[1].toDoubleOrNull() ?: return null, m.groupValues[2])
            .toLong()
    }

    /** Fallback for fragment-based (HLS/DASH) downloads with no byte totals. */
    fun parseFragmentProgress(line: String?): Float? {
        val m = FRAGMENT.find(line ?: return null) ?: return null
        val done = m.groupValues[1].toFloatOrNull() ?: return null
        val total = m.groupValues[2].toFloatOrNull() ?: return null
        if (total <= 0f) return null
        return (done / total * 100f).coerceIn(0f, 100f)
    }

    /** True once yt-dlp moves past downloading into ffmpeg work. */
    fun isPostProcessing(line: String?): Boolean {
        val l = line ?: return false
        return l.contains("[Merger]") || l.contains("[ExtractAudio]") ||
            l.contains("[EmbedSubtitle]") || l.contains("[Metadata]") ||
            l.contains("[EmbedThumbnail]") || l.contains("[SponsorBlock]") ||
            l.contains("[Fixup")
    }

    private fun toBytesPerSecond(value: Double, unit: String): Double {
        val multiplier = when (unit.lowercase().removeSuffix("b")) {
            "", "i" -> 1.0
            "k", "ki" -> 1024.0
            "m", "mi" -> 1024.0 * 1024
            "g", "gi" -> 1024.0 * 1024 * 1024
            "t", "ti" -> 1024.0 * 1024 * 1024 * 1024
            "p", "pi" -> 1024.0 * 1024 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return value * multiplier
    }
}

/** One progress report from a running yt-dlp process. */
data class ProgressUpdate(
    /** yt-dlp's own percentage, 0f..100f. */
    val percent: Float,
    val etaSeconds: Long?,
    val speedBytesPerSecond: Double?,
    val totalBytes: Long?,
    /**
     * HLS/DASH downloads report fragment counts instead of bytes, so percent can
     * stay at 0 while this advances. The UI prefers [percent] when it moves.
     */
    val fragmentPercent: Float?,
    val postProcessing: Boolean,
    val rawLine: String?,
) {
    /** Whichever of the two progress signals is actually informative. */
    val effectivePercent: Float
        get() = when {
            percent > 0f -> percent
            fragmentPercent != null -> fragmentPercent
            else -> 0f
        }
}
