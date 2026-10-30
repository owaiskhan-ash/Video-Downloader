package com.teal.downloader.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teal.downloader.BuildConfig
import com.teal.downloader.data.model.AudioFormat
import com.teal.downloader.data.model.DownloadSettings
import com.teal.downloader.data.model.VideoFormatPreference
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.component.ClickableRow
import com.teal.downloader.ui.component.Divider
import com.teal.downloader.ui.component.SectionCard
import com.teal.downloader.ui.component.SectionHeader
import com.teal.downloader.ui.component.TealTextField
import com.teal.downloader.ui.component.ToggleRow

/** Which dialog is open, if any. One nullable field beats a dozen booleans. */
private sealed interface SettingsDialog {
    data class TextInput(
        val title: String,
        val value: String,
        val label: String,
        val placeholder: String,
        val multiline: Boolean = false,
        val onConfirm: (String) -> Unit,
    ) : SettingsDialog

    data class Choice(
        val title: String,
        val options: List<Pair<String, String>>,
        val selectedKey: String?,
        val onSelect: (String?) -> Unit,
    ) : SettingsDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenTemplates: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val publishToDownloads by viewModel.publishToDownloads.collectAsStateWithLifecycle()
    val maxConcurrent by viewModel.maxConcurrentDownloads.collectAsStateWithLifecycle()
    val ytDlpVersion by viewModel.ytDlpVersion.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text("Settings", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("DOWNLOAD") }
            item {
                SectionCard {
                    ToggleRow(
                        title = "Download audio only",
                        subtitle = "Extract the audio track and convert it",
                        checked = settings.audioOnly,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(audioOnly = v) } },
                    )
                    Divider()
                    ClickableRow(
                        title = "Audio format",
                        value = settings.audioFormat.label,
                        onClick = {
                            dialog = SettingsDialog.Choice(
                                title = "Audio format",
                                options = AudioFormat.entries.map { it.name to it.label },
                                selectedKey = settings.audioFormat.name,
                                onSelect = { key ->
                                    key?.let { picked ->
                                        val format = AudioFormat.entries.first { it.name == picked }
                                        viewModel.updateSettings { it.copy(audioFormat = format) }
                                    }
                                    dialog = null
                                },
                            )
                        },
                    )
                    Divider()
                    ClickableRow(
                        title = "Video format preference",
                        subtitle = settings.videoPreference.label,
                        onClick = {
                            dialog = SettingsDialog.Choice(
                                title = "Video format preference",
                                options = VideoFormatPreference.entries.map { it.name to it.label },
                                selectedKey = settings.videoPreference.name,
                                onSelect = { key ->
                                    key?.let { picked ->
                                        val pref = VideoFormatPreference.entries.first { it.name == picked }
                                        viewModel.updateSettings { it.copy(videoPreference = pref) }
                                    }
                                    dialog = null
                                },
                            )
                        },
                    )
                    Divider()
                    ClickableRow(
                        title = "Maximum resolution",
                        subtitle = "Caps the height; falls back if unavailable",
                        value = settings.preferredHeight?.let { "${it}p" } ?: "Best",
                        onClick = {
                            val heights = listOf(null, 2160, 1440, 1080, 720, 480, 360)
                            dialog = SettingsDialog.Choice(
                                title = "Maximum resolution",
                                options = heights.map { (it?.toString() ?: "") to (it?.let { h -> "${h}p" } ?: "Best available") },
                                selectedKey = settings.preferredHeight?.toString() ?: "",
                                onSelect = { key ->
                                    viewModel.updateSettings {
                                        it.copy(preferredHeight = key?.toIntOrNull()?.takeIf { h -> h > 0 })
                                    }
                                    dialog = null
                                },
                            )
                        },
                    )
                }
            }

            item { SectionHeader("POST-PROCESSING") }
            item {
                SectionCard {
                    ToggleRow(
                        title = "Embed metadata",
                        subtitle = "Title, uploader and date inside the file",
                        checked = settings.embedMetadata,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(embedMetadata = v) } },
                    )
                    Divider()
                    ToggleRow(
                        title = "Embed thumbnail",
                        subtitle = "Needs ffmpeg; adds cover art",
                        checked = settings.embedThumbnail,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(embedThumbnail = v) } },
                    )
                    Divider()
                    ToggleRow(
                        title = "Save subtitles",
                        subtitle = "Downloads subtitle tracks as separate files",
                        checked = settings.writeSubtitles,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(writeSubtitles = v) } },
                    )
                    Divider()
                    ToggleRow(
                        title = "Embed subtitles",
                        subtitle = "Muxes them into the video (mp4/mkv only)",
                        checked = settings.embedSubtitles,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(embedSubtitles = v) } },
                    )
                    Divider()
                    ClickableRow(
                        title = "Subtitle languages",
                        subtitle = "Comma separated, or \"all\"",
                        value = settings.subtitleLanguages.ifBlank { "—" },
                        enabled = settings.writeSubtitles || settings.embedSubtitles,
                        onClick = {
                            dialog = SettingsDialog.TextInput(
                                title = "Subtitle languages",
                                value = settings.subtitleLanguages,
                                label = "Languages",
                                placeholder = "en,hi,ar",
                                onConfirm = { v ->
                                    viewModel.updateSettings { it.copy(subtitleLanguages = v.trim()) }
                                    dialog = null
                                },
                            )
                        },
                    )
                    Divider()
                    ToggleRow(
                        title = "Remove sponsor segments",
                        subtitle = "SponsorBlock: cuts out intros, sponsors and reminders",
                        checked = settings.removeSponsorSegments,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(removeSponsorSegments = v) } },
                    )
                }
            }

            item { SectionHeader("TRANSFER") }
            item {
                SectionCard {
                    ToggleRow(
                        title = "Use aria2c",
                        subtitle = "Multi-connection external downloader",
                        checked = settings.useAria2c,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(useAria2c = v) } },
                    )
                    Divider()
                    ClickableRow(
                        title = "aria2c connections",
                        value = settings.aria2cConnections.toString(),
                        enabled = settings.useAria2c,
                        onClick = {
                            dialog = intChoiceDialog("aria2c connections", listOf(4, 8, 12, 16),
                                settings.aria2cConnections) { picked ->
                                viewModel.updateSettings { it.copy(aria2cConnections = picked) }
                            }
                        },
                    )
                    Divider()
                    ClickableRow(
                        title = "Concurrent fragments",
                        subtitle = "Parallel chunks for HLS/DASH streams",
                        value = settings.concurrentFragments.toString(),
                        onClick = {
                            dialog = intChoiceDialog("Concurrent fragments", listOf(1, 2, 4, 8, 16),
                                settings.concurrentFragments) { picked ->
                                viewModel.updateSettings { it.copy(concurrentFragments = picked) }
                            }
                        },
                    )
                    Divider()
                    ClickableRow(
                        title = "Retries",
                        value = settings.retries.toString(),
                        onClick = {
                            dialog = intChoiceDialog("Retries", listOf(0, 3, 5, 10), settings.retries) { picked ->
                                viewModel.updateSettings { it.copy(retries = picked) }
                            }
                        },
                    )
                    Divider()
                    ClickableRow(
                        title = "Simultaneous downloads",
                        subtitle = "How many downloads run at once",
                        value = maxConcurrent.toString(),
                        onClick = {
                            dialog = intChoiceDialog("Simultaneous downloads", listOf(1, 2, 3, 4, 5), maxConcurrent) { picked ->
                                viewModel.setMaxConcurrentDownloads(picked)
                            }
                        },
                    )
                    Divider()
                    ClickableRow(
                        title = "Speed limit",
                        subtitle = "Empty means unlimited",
                        value = settings.rateLimit.ifBlank { "—" },
                        onClick = {
                            dialog = SettingsDialog.TextInput(
                                title = "Speed limit",
                                value = settings.rateLimit,
                                label = "Limit",
                                placeholder = "2M",
                                onConfirm = { v ->
                                    viewModel.updateSettings { it.copy(rateLimit = v.trim()) }
                                    dialog = null
                                },
                            )
                        },
                    )
                }
            }

            item { SectionHeader("STORAGE") }
            item {
                SectionCard {
                    ToggleRow(
                        title = "Save to public Downloads",
                        subtitle = "Copies finished files into Downloads/Teal so other apps can see them. " +
                            "Off keeps them in app-private storage only.",
                        checked = publishToDownloads,
                        onCheckedChange = viewModel::setPublishToDownloads,
                    )
                    Divider()
                    ClickableRow(
                        title = "Filename template",
                        subtitle = "yt-dlp output template",
                        value = settings.outputTemplate,
                        onClick = {
                            dialog = SettingsDialog.TextInput(
                                title = "Filename template",
                                value = settings.outputTemplate,
                                label = "Template",
                                placeholder = DownloadSettings.DEFAULT_OUTPUT_TEMPLATE,
                                onConfirm = { v ->
                                    viewModel.updateSettings {
                                        it.copy(outputTemplate = v.trim().ifBlank { DownloadSettings.DEFAULT_OUTPUT_TEMPLATE })
                                    }
                                    dialog = null
                                },
                            )
                        },
                    )
                }
            }

            item { SectionHeader("PLAYLISTS") }
            item {
                SectionCard {
                    ToggleRow(
                        title = "Ignore playlists",
                        subtitle = "Only take the single video from a playlist URL",
                        checked = settings.noPlaylist,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(noPlaylist = v) } },
                    )
                    Divider()
                    ClickableRow(
                        title = "Playlist items",
                        subtitle = "Overrides the above, e.g. 1-5,8",
                        value = settings.playlistItems.ifBlank { "—" },
                        onClick = {
                            dialog = SettingsDialog.TextInput(
                                title = "Playlist items",
                                value = settings.playlistItems,
                                label = "Items",
                                placeholder = "1-10",
                                onConfirm = { v ->
                                    viewModel.updateSettings { it.copy(playlistItems = v.trim()) }
                                    dialog = null
                                },
                            )
                        },
                    )
                }
            }

            item { SectionHeader("ADVANCED") }
            item {
                SectionCard {
                    ClickableRow(
                        title = "Command templates",
                        subtitle = "Reusable raw yt-dlp arguments",
                        leadingIcon = Icons.Rounded.Terminal,
                        onClick = onOpenTemplates,
                    )
                    Divider()
                    ToggleRow(
                        title = "Restrict filenames",
                        subtitle = "ASCII only, no spaces",
                        checked = settings.restrictFilenames,
                        onCheckedChange = { v -> viewModel.updateSettings { it.copy(restrictFilenames = v) } },
                    )
                    Divider()
                    textSettingRow(viewModel, "Proxy", settings.proxy, "socks5://127.0.0.1:1080") { v ->
                        it.copy(proxy = v)
                    }
                    Divider()
                    textSettingRow(viewModel, "User agent", settings.userAgent, "Custom UA string") { v ->
                        it.copy(userAgent = v)
                    }
                    Divider()
                    textSettingRow(viewModel, "Cookies file", settings.cookiesFile,
                        "/sdcard/Download/cookies.txt") { v -> it.copy(cookiesFile = v) }
                    Divider()
                    textSettingRow(viewModel, "Extra arguments", settings.customArguments,
                        "--write-thumbnail", multiline = true) { v -> it.copy(customArguments = v) }
                }
            }

            item { SectionHeader("ABOUT") }
            item {
                SectionCard {
                    InfoRow(title = "Teal", value = BuildConfig.VERSION_NAME)
                    Divider()
                    InfoRow(
                        title = "yt-dlp",
                        value = ytDlpVersion ?: "unpacked on first download",
                    )
                    Divider()
                    Text(
                        "Teal is free software under the GNU GPL-3.0, because the yt-dlp, ffmpeg and " +
                            "aria2c libraries it embeds are GPL-licensed. It is an independent " +
                            "implementation and is not affiliated with yt-dlp or Seal.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    when (val current = dialog) {
        is SettingsDialog.TextInput -> InputDialog(
            title = current.title,
            value = current.value,
            label = current.label,
            placeholder = current.placeholder,
            multiline = current.multiline,
            onConfirm = current.onConfirm,
            onDismiss = { dialog = null },
        )
        is SettingsDialog.Choice -> ChoiceDialog(
            title = current.title,
            options = current.options,
            selectedKey = current.selectedKey,
            onSelect = current.onSelect,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** A row whose dialog is a free-text field, factored out of the long list above. */
@Composable
private fun textSettingRow(
    viewModel: MainViewModel,
    title: String,
    value: String,
    placeholder: String,
    multiline: Boolean = false,
    transform: (DownloadSettings, String) -> DownloadSettings,
) {
    var editing by remember { mutableStateOf(false) }
    ClickableRow(
        title = title,
        value = value.ifBlank { "—" },
        onClick = { editing = true },
    )
    if (editing) {
        InputDialog(
            title = title,
            value = value,
            label = title,
            placeholder = placeholder,
            multiline = multiline,
            onConfirm = { newValue ->
                viewModel.updateSettings { transform(it, newValue.trim()) }
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

private fun intChoiceDialog(
    title: String,
    values: List<Int>,
    selected: Int,
    onPick: (Int) -> Unit,
): SettingsDialog = SettingsDialog.Choice(
    title = title,
    options = values.map { it.toString() to it.toString() },
    selectedKey = selected.toString(),
    onSelect = { key -> key?.toIntOrNull()?.let(onPick) },
)

/** A row that only displays something; no dialog behind it. */
@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InputDialog(
    title: String,
    value: String,
    label: String,
    placeholder: String,
    multiline: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title, color = MaterialTheme.colorScheme.primary) },
        text = {
            TealTextField(
                value = text,
                onValueChange = { text = it },
                label = label,
                placeholder = placeholder,
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Save", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title, color = MaterialTheme.colorScheme.primary) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                options.forEach { (key, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = key == selectedKey,
                                onClick = { onSelect(key) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = key == selectedKey,
                            onClick = { onSelect(key) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}
