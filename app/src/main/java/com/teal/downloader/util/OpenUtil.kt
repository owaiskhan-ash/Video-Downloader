package com.teal.downloader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Opens and shares finished files, hiding the file:// vs content:// difference. */
object OpenUtil {

    fun uriFor(context: Context, uriOrPath: String?): Uri? {
        if (uriOrPath.isNullOrBlank()) return null
        if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
            return Uri.parse(uriOrPath)
        }
        val file = File(uriOrPath)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull()
    }

    fun open(context: Context, uriOrPath: String?, mimeType: String?) {
        val uri = uriFor(context, uriOrPath) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType ?: FileUtil.mimeTypeOf(uriOrPath ?: ""))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun share(context: Context, uriOrPath: String?, title: String?) {
        val uri = uriFor(context, uriOrPath) ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(FileUtil.mimeTypeOf(uriOrPath ?: ""))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { title?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
