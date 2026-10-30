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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OpenInNew
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.teal.downloader.data.db.HistoryEntity
import com.teal.downloader.data.model.DownloadRequest
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.component.EmptyState
import com.teal.downloader.ui.component.SectionCard
import com.teal.downloader.ui.component.TealTag
import com.teal.downloader.util.OpenUtil
import com.teal.downloader.util.TextUtil
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text("History", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            },
            actions = {
                if (history.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearHistory) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (history.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.History,
                title = "Nothing downloaded yet",
                body = "Finished downloads are listed here so you can reopen, share or fetch them again.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(history, key = { it.id }) { entry ->
                    HistoryCard(
                        entry = entry,
                        onOpen = { OpenUtil.open(context, entry.contentUri ?: entry.filePath, null) },
                        onShare = { OpenUtil.share(context, entry.contentUri ?: entry.filePath, entry.title) },
                        onDelete = { viewModel.deleteHistory(entry.id) },
                        onRedownload = { viewModel.downloadEntry(entry.url, entry.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRedownload: () -> Unit,
) {
    val hasFile = !entry.contentUri.isNullOrBlank() || !entry.filePath.isNullOrBlank()
    SectionCard {
        Row {
            AsyncImage(
                model = entry.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TealTag(if (entry.succeeded) "saved" else "failed")
                    entry.ext?.takeIf { it.isNotBlank() }?.let { TealTag(it) }
                    entry.sizeBytes?.let { TealTag(TextUtil.formatBytes(it)) }
                    if (entry.isAudioOnly) TealTag("audio")
                }
            }
        }

        if (!entry.succeeded && entry.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(entry.error, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onRedownload) {
                Icon(Icons.Rounded.Download, contentDescription = "Download again",
                    tint = MaterialTheme.colorScheme.primary)
            }
            if (hasFile) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onOpen) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = "Open",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
