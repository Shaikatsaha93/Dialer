package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AppSettings
import com.example.data.repository.CallRecordings
import com.example.platform.formatDateTime
import com.example.ui.theme.CallActionRed
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass
import kotlinx.coroutines.delay
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

@Composable
actual fun CallRecordingCard(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier
) {
    // Re-read the folder every few seconds so a call that just ended shows up
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            refresh++
        }
    }
    val recordings = remember(refresh) { CallRecordings.list() }
    var showAll by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<File?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    val clip = remember { mutableStateOf<Clip?>(null) }

    fun stop() {
        clip.value?.close()
        clip.value = null
        playing = null
    }

    fun play(file: File) {
        stop()
        try {
            val stream = AudioSystem.getAudioInputStream(file)
            clip.value = AudioSystem.getClip().apply {
                open(stream)
                addLineListener { if (it.type == LineEvent.Type.STOP && playing == file) stop() }
                start()
            }
            playing = file
        } catch (_: Exception) {
            stop()
        }
    }

    DisposableEffect(Unit) { onDispose { stop() } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp), LocalGlassColors.current)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = CallActionRed)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Call Recording", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (settings.recordCalls) "Every call is recorded and saved on this computer"
                    else "Off. Turn on to record calls to this computer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.recordCalls,
                onCheckedChange = { onSettingsChanged(settings.copy(recordCalls = it)) },
                modifier = Modifier.testTag("setting_record_calls")
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Saved in ${CallRecordings.directory().absolutePath}. Recording a call may need the other person's consent.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { openInExplorer(CallRecordings.directory()) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Text("Open folder", modifier = Modifier.padding(start = 6.dp))
            }
        }

        if (recordings.isNotEmpty()) {
            Text(
                "Recordings (${recordings.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
            (if (showAll) recordings else recordings.take(5)).forEach { file ->
                RecordingRow(
                    file = file,
                    isPlaying = playing == file,
                    onPlay = { if (playing == file) stop() else play(file) },
                    onShow = { openInExplorer(file) },
                    onDelete = { toDelete = file }
                )
            }
            if (recordings.size > 5) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Show less" else "Show all ${recordings.size}")
                }
            }
        }
    }

    toDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            text = { Text("Delete this recording?") },
            confirmButton = {
                TextButton(onClick = {
                    if (playing == file) stop()
                    file.delete()
                    toDelete = null
                    refresh++
                }) { Text("Delete", color = CallActionRed) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

/** Opens a folder, or shows a file selected in its folder, in Windows Explorer. */
private fun openInExplorer(file: File) {
    try {
        if (file.isDirectory) {
            ProcessBuilder("explorer.exe", file.absolutePath).start()
        } else {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
        }
    } catch (_: Exception) {
    }
}

@Composable
private fun RecordingRow(
    file: File,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShow: () -> Unit,
    onDelete: () -> Unit
) {
    // Name: <date>_<time>_<number>_<IN|OUT>.wav
    val parts = file.nameWithoutExtension.split('_')
    val number = parts.getOrNull(2).orEmpty()
    val direction = if (parts.getOrNull(3) == "IN") "Incoming" else "Outgoing"
    val sizeKb = file.length() / 1024
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPlay) {
            Icon(
                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(number.ifEmpty { file.name }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$direction · ${formatDateTime(file.lastModified(), "MMM d, HH:mm")} · " +
                    if (sizeKb >= 1024) "${sizeKb / 1024} MB" else "$sizeKb KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onShow) {
            Icon(Icons.Default.FolderOpen, contentDescription = "Show in folder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CallActionRed)
        }
    }
}
