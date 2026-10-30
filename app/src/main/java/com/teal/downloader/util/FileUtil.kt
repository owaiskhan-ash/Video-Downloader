package com.teal.downloader.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where files are written, and how they become visible to the rest of the phone.
 *
 * yt-dlp is a native process that needs a real filesystem path, which rules out
 * writing straight into MediaStore. So downloads land in an app-private staging
 * directory that needs no permission on any API level, and are then *published*
 * to public Downloads once complete.
 */
object FileUtil {

    private const val APP_DIR_NAME = "Teal"

    /** Always writable without a runtime permission, on every API level. */
    fun stagingDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        return File(base, APP_DIR_NAME).apply { mkdirs() }
    }

    /** Pre-Q location for a direct write; needs WRITE_EXTERNAL_STORAGE. */
    @Suppress("DEPRECATION")
    fun legacyDownloadsDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), APP_DIR_NAME)
            .apply { mkdirs() }

    /**
     * Copies [file] into the user's Downloads folder and returns its Uri.
     *
     * @return the published Uri, or null if publishing failed (the staged file is
     *   left in place either way, so the download is never lost).
     */
    suspend fun publishToDownloads(context: Context, file: File): Uri? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    publishViaMediaStore(context, file)
                } else {
                    publishViaLegacyCopy(file)
                }
            }.getOrNull()
        }

    private fun publishViaMediaStore(context: Context, file: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + APP_DIR_NAME)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(file.name))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishViaLegacyCopy(file: File): Uri {
        val target = File(legacyDownloadsDir(), file.name)
        file.copyTo(target, overwrite = true)
        return Uri.fromFile(target)
    }

    fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/ogg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "srt", "vtt" -> "text/plain"
                else -> "application/octet-stream"
            }
    }

    /** The file yt-dlp actually produced: newest thing in the dir since [since]. */
    fun newestFileSince(dir: File, since: Long): File? =
        dir.listFiles()?.filter { it.isFile && it.lastModified() >= since - 1000 }
            ?.maxByOrNull { it.lastModified() }

    fun deleteQuietly(file: File?) {
        runCatching { file?.takeIf { it.exists() }?.delete() }
    }
}
