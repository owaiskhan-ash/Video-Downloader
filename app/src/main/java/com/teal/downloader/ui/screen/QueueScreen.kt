package com.teal.downloader.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.teal.downloader.data.model.DownloadTask
import com.teal.downloader.data.model.TaskStatus
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.component.EmptyState
import com.teal.downloader.ui.component.ProgressBar
import com.teal.downloader.ui.component.SectionCard
import com.teal.downloader.ui.component.TealTag
import com.teal.downloader.util.OpenUtil
import com.teal.downloader.util.TextUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(viewModel: MainViewModel) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text("Downloads", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            },
            actions = {
                if (tasks.any { it.isActive }) {
                    TextButton(onClick = viewModel::cancelAll) {
                        Text("Cancel all", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (tasks.any { !it.isActive }) {
                    TextButton(onClick = viewModel::clearFinishedTasks) {
                        Text("Clear", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.DownloadDone,
                title = "No downloads yet",
                body = "Anything you start from the Download tab shows up here with live progress, " +
                    "and keeps running when you leave the app.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onCancel = { viewModel.cancelTask(task.id) },
                        onRetry = { viewModel.retryTask(task.id) },
                        onRemove = { viewModel.removeTask(task.id) },
                        onOpen = {
                            OpenUtil.open(context, task.contentUri ?: task.filePath, null)
                        },
                        onShare = {
                            OpenUtil.share(context, task.contentUri ?: task.filePath, task.title)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    SectionCard {
        Row {
            AsyncImage(
                model = task.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TealTag(task.status.label())
                    if (task.ext != null) TealTag(task.ext)
                    if (task.isAudioOnly) TealTag("audio")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (task.status) {
            TaskStatus.QUEUED -> ProgressBar(progress = 0f, indeterminate = false)
            TaskStatus.RUNNING, TaskStatus.POST_PROCESSING ->
                ProgressBar(progress = task.progress, indeterminate = task.progress <= 0f)
            TaskStatus.COMPLETED -> ProgressBar(progress = 100f)
            TaskStatus.FAILED, TaskStatus.CANCELED -> Unit
        }

        Spacer(Modifier.height(6.dp))

        val detail = when (task.status) {
            TaskStatus.QUEUED -> "Waiting for a free slot"
            TaskStatus.RUNNING -> listOfNotNull(
                "${task.progress.toInt()}%",
                TextUtil.formatSpeed(task.speedBytesPerSecond).takeIf { task.speedBytesPerSecond > 0 },
                task.etaSeconds?.let { "${TextUtil.formatEta(it)} left" },
                task.totalBytes?.let { "of ${TextUtil.formatBytes(it)}" },
            ).joinToString("  ·  ")
            TaskStatus.POST_PROCESSING -> "Muxing and post-processing with ffmpeg"
            TaskStatus.COMPLETED -> listOfNotNull(
                task.totalBytes?.let { TextUtil.formatBytes(it) },
                if (task.contentUri != null) "Saved to Downloads" else "Saved in app storage",
            ).joinToString("  ·  ")
            TaskStatus.FAILED -> task.error ?: "Download failed"
            TaskStatus.CANCELED -> "Canceled"
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (task.status) {
                TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.POST_PROCESSING -> {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                TaskStatus.COMPLETED -> {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onOpen) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = "Open",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TaskStatus.FAILED, TaskStatus.CANCELED -> {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

internal fun TaskStatus.label(): String = when (this) {
    TaskStatus.QUEUED -> "queued"
    TaskStatus.RUNNING -> "downloading"
    TaskStatus.POST_PROCESSING -> "processing"
    TaskStatus.COMPLETED -> "done"
    TaskStatus.FAILED -> "failed"
    TaskStatus.CANCELED -> "canceled"
}

@Suppress("unused")
private val statusColorHint: Color = Color.Unspecified
