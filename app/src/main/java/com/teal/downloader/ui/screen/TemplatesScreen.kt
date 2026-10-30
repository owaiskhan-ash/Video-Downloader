package com.teal.downloader.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teal.downloader.data.db.CommandTemplateEntity
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.component.EmptyState
import com.teal.downloader.ui.component.PrimaryButton
import com.teal.downloader.ui.component.SectionCard
import com.teal.downloader.ui.component.TealTextField

/**
 * Reusable raw yt-dlp argument sets, like Seal's command templates.
 *
 * The arguments are appended verbatim to the generated command, after everything
 * the UI produced, so a template can override earlier options — which is exactly
 * what makes it an escape hatch for anything the settings do not expose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(viewModel: MainViewModel) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CommandTemplateEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<CommandTemplateEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text("Command templates", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            },
            actions = {
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New template",
                        tint = MaterialTheme.colorScheme.primary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (templates.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Terminal,
                title = "No templates yet",
                body = "Save a set of raw yt-dlp arguments and run it against any URL. " +
                    "Arguments are appended last, so they override what the UI generated.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(templates, key = { it.id }) { template ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(template.name, style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(4.dp))
                                Text(template.content, style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { running = template }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Run",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { editing = template }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.deleteTemplate(template.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        TemplateEditorDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, content ->
                viewModel.saveTemplate(name, content, editing?.id)
                creating = false
                editing = null
            },
        )
    }

    running?.let { template ->
        RunTemplateDialog(
            template = template,
            onDismiss = { running = null },
            onRun = { url ->
                viewModel.runTemplate(template, url)
                running = null
            },
        )
    }
}

@Composable
private fun TemplateEditorDialog(
    initial: CommandTemplateEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(if (initial == null) "New template" else "Edit template",
                color = MaterialTheme.colorScheme.primary)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TealTextField(value = name, onValueChange = { name = it }, label = "Name",
                    placeholder = "Audio at 320k")
                Spacer(Modifier.height(10.dp))
                TealTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = "Arguments",
                    placeholder = "--audio-quality 0 --embed-thumbnail",
                    singleLine = false,
                    minLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Space separated, double quotes honoured. The URL is added for you.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, content) },
                enabled = name.isNotBlank() && content.isNotBlank(),
            ) { Text("Save", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun RunTemplateDialog(
    template: CommandTemplateEntity,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(template.name, color = MaterialTheme.colorScheme.primary) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(template.content, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                TealTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "Video URL",
                    placeholder = "https://…",
                    keyboardType = KeyboardType.Uri,
                    onImeAction = { if (url.isNotBlank()) onRun(url.trim()) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(url.trim()) }, enabled = url.isNotBlank()) {
                Text("Run", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
