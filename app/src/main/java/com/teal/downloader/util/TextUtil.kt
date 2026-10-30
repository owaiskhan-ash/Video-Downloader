package com.teal.downloader.util

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Display formatting. Pure functions, so they are trivially testable. */
object TextUtil {

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(exp.toDouble())
        val digits = if (exp == 0 || value >= 100) 0 else 1
        return "%.${digits}f %s".format(value, units[exp])
    }

    /** Bytes per second, rendered the same way as a size. */
    fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0) return "—"
        return formatBytes(bytesPerSecond.roundToInt().toLong()) + "/s"
    }

    /** 3:45, or 1:02:03 once an hour is involved. */
    fun formatDuration(totalSeconds: Long?): String {
        if (totalSeconds == null || totalSeconds <= 0) return "—"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatEta(seconds: Long?): String = when {
        seconds == null || seconds <= 0 -> "—"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    /** "1080p", "720p60", or a codec fallback when the height is unknown. */
    fun formatQuality(height: Int?, fps: Double?, fallback: String? = null): String {
        if (height == null || height <= 0) return fallback?.takeIf { it.isNotBlank() } ?: "—"
        val frames = fps?.roundToInt()?.takeIf { it > 0 && it != 30 && it != 25 }
        return if (frames != null) "${height}p$frames" else "${height}p"
    }
}
