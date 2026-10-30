package com.teal.downloader.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.teal.downloader.data.model.YtdlFormat
import com.teal.downloader.data.model.YtdlInfo
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.component.EmptyState
import com.teal.downloader.ui.component.PrimaryButton
import com.teal.downloader.ui.component.SectionCard
import com.teal.downloader.ui.component.SecondaryButton
import com.teal.downloader.ui.component.Spinner
import com.teal.downloader.ui.component.TealTag
import com.teal.downloader.ui.component.TealTextField
import com.teal.downloader.util.TextUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val state by viewModel.home.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showFormatSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Teal", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text("Video and audio downloader",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TealTextField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChange,
                    label = "Video URL",
                    placeholder = "Paste a link to a video",
                    isError = state.error != null,
                    supportingText = state.error,
                    keyboardType = KeyboardType.Uri,
                    onImeAction = viewModel::fetchInfo,
                    trailingIcon = {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { text ->
                                    // Shared text often carries a title around the
                                    // URL; take the first http(s) token in it.
                                    val url = extractUrl(text) ?: text.trim()
                                    viewModel.onUrlChange(url)
                                }
                            }) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            if (state.url.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearUrl) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        text = if (state.isFetching) "Fetching…" else "Fetch",
                        onClick = viewModel::fetchInfo,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isFetching && state.url.isNotBlank(),
                        icon = Icons.Rounded.Search,
                    )
                    PrimaryButton(
                        text = "Download",
                        onClick = { viewModel.download(audioOnly = false) },
                        modifier = Modifier.weight(1f),
                        enabled = state.url.isNotBlank() && !state.isFetching,
                        icon = Icons.Rounded.Download,
                    )
                }
            }

            if (state.isFetching) {
                item {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner()
                            Spacer(Modifier.width(14.dp))
                            Text("Reading the link…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            state.info?.let { info ->
                item { VideoInfoCard(info, state.selectedVideoFormatId, state.selectedAudioFormatId) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(
                            text = "Choose format",
                            onClick = { showFormatSheet = true },
                            modifier = Modifier.weight(1f),
                        )
                        PrimaryButton(
                            text = if (settings.audioOnly) "Download audio" else "Download",
                            onClick = { viewModel.download(audioOnly = settings.audioOnly) },
                            modifier = Modifier.weight(1f),
                            icon = if (settings.audioOnly) Icons.Rounded.GraphicEq else Icons.Rounded.Download,
                        )
                    }
                }

                if (info.isPlaylist) {
                    item {
                        Text(
                            info.playlistTitle ?: "Playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val entries = info.entries.orEmpty().take(100)
                    itemsIndexed(entries, key = { index, entry -> entry.id ?: entry.url ?: index.toString() }) { _, entry ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title ?: entry.url ?: "Untitled",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(2.dp))
                                    Text(TextUtil.formatDuration(entry.duration?.toLong()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    entry.url?.let { viewModel.downloadEntry(it, entry.title) }
                                }) {
                                    Icon(Icons.Rounded.Download, contentDescription = "Download",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            if (state.info == null && !state.isFetching) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Link,
                        title = "Nothing loaded",
                        body = "Paste a link above, or share one to Teal from another app. " +
                            "Fetching first shows the available qualities before you commit.",
                    )
                }
            }
        }
    }

    if (showFormatSheet) {
        FormatSheet(
            info = state.info,
            selectedVideoFormatId = state.selectedVideoFormatId,
            selectedAudioFormatId = state.selectedAudioFormatId,
            onDismiss = { showFormatSheet = false },
            onPickVideo = viewModel::selectVideoFormat,
            onPickAudio = viewModel::selectAudioFormat,
            onDownload = { audioOnly ->
                showFormatSheet = false
                viewModel.download(audioOnly)
            },
        )
    }
}

/** Shares arrive as "Title https://…"; pull the URL out of that. */
internal fun extractUrl(text: String): String? =
    Regex("""https?://\S+""").find(text)?.value

@Composable
private fun VideoInfoCard(info: YtdlInfo, selectedVideo: String?, selectedAudio: String?) {
    SectionCard {
        Row {
            AsyncImage(
                model = info.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.displayTitle, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        info.uploaderName,
                        info.durationSeconds?.let { TextUtil.formatDuration(it) },
                        info.extractorKey ?: info.extractor,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val heights = info.videoFormats.mapNotNull { it.height }.distinct().sortedDescending().take(5)
        if (heights.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                heights.forEach { TealTag("${it}p") }
                info.audioFormats.firstOrNull()?.let { TealTag("audio") }
            }
        }

        val chosen = selectedVideo ?: selectedAudio
        if (chosen != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Selected format: $chosen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(
    info: YtdlInfo?,
    selectedVideoFormatId: String?,
    selectedAudioFormatId: String?,
    onDismiss: () -> Unit,
    onPickVideo: (String?) -> Unit,
    onPickAudio: (String?) -> Unit,
    onDownload: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var audioTab by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(if (audioTab) "Audio tracks" else "Video qualities",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !audioTab,
                    onClick = { audioTab = false },
                    label = { Text("Video") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
                FilterChip(
                    selected = audioTab,
                    onClick = { audioTab = true },
                    label = { Text("Audio only") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))

            if (info == null) {
                Text("Fetch the link first to see its formats.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp))
            } else {
                val options: List<YtdlFormat> = if (audioTab) {
                    info.audioFormats.ifEmpty { info.selectableFormats.filter { it.hasAudio } }
                } else {
                    info.videoFormats
                }
                LazyColumn(
                    Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FormatRow(
                            title = "Best available",
                            subtitle = if (audioTab) "Highest quality audio stream"
                            else "Let yt-dlp pick and merge the best",
                            meta = null,
                            selected = if (audioTab) selectedAudioFormatId == null else selectedVideoFormatId == null,
                            onClick = { if (audioTab) onPickAudio(null) else onPickVideo(null) },
                        )
                    }
                    items(options, key = { it.formatId }) { format ->
                        val selected = if (audioTab) selectedAudioFormatId == format.formatId
                        else selectedVideoFormatId == format.formatId
                        FormatRow(
                            title = formatTitle(format, audioTab),
                            subtitle = format.codecLabel + (format.formatNote?.let { "  ·  $it" } ?: ""),
                            meta = format.sizeBytes?.let { TextUtil.formatBytes(it) },
                            selected = selected,
                            onClick = {
                                if (audioTab) onPickAudio(format.formatId) else onPickVideo(format.formatId)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (audioTab) "Download audio" else "Start download",
                onClick = { onDownload(audioTab) },
                modifier = Modifier.fillMaxWidth(),
                icon = if (audioTab) Icons.Rounded.GraphicEq else Icons.Rounded.Download,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatTitle(format: YtdlFormat, audioTab: Boolean): String =
    if (audioTab) {
        listOfNotNull(
            format.formatNote?.takeIf { it.isNotBlank() },
            format.abr?.let { "${it.toInt()} kbps" },
            format.asr?.let { "${(it / 1000).toInt()} kHz" },
        ).joinToString("  ·  ").ifBlank { format.ext ?: "Audio" }
    } else {
        TextUtil.formatQuality(format.height, format.fps, format.resolution ?: format.formatNote)
    }

@Composable
private fun FormatRow(
    title: String,
    subtitle: String,
    meta: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta != null) {
                Spacer(Modifier.width(10.dp))
                Text(meta, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Download, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
