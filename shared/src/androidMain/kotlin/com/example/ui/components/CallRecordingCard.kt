package com.example.ui.components

import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.data.model.AppSettings
import com.example.data.repository.CallRecordings
import com.example.ui.theme.CallActionRed
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass
import java.io.File

/** Settings card: turn call recording on/off and play, share or delete the recordings. */
@Composable
actual fun CallRecordingCard(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    // Bumped to re-read the folder (on resume, after a delete)
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val recordings = remember(refresh) { CallRecordings.list(context) }
    var showAll by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<File?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    fun stop() {
        player.value?.release()
        player.value = null
        playing = null
    }

    fun play(file: File) {
        stop()
        try {
            player.value = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
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
                    if (settings.recordCalls) "Every call is recorded and saved on this phone"
                    else "Off. Turn on to record calls to this phone",
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
        Text(
            "Saved in Android/data/${context.packageName}/files/Recordings. Recording a call may need the other person's consent.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
                    onShare = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val share = Intent(Intent.ACTION_SEND)
                            .setType("audio/wav")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(share, "Share recording"))
                    },
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

@Composable
private fun RecordingRow(
    file: File,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    // Name: <date>_<time>_<number>_<IN|OUT>.wav
    val parts = file.nameWithoutExtension.split('_')
    val number = parts.getOrNull(2).orEmpty()
    val direction = if (parts.getOrNull(3) == "IN") "Incoming" else "Outgoing"
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
                "$direction · " + DateUtils.formatDateTime(
                    context, file.lastModified(),
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
                ) + " · " + Formatter.formatShortFileSize(context, file.length()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CallActionRed)
        }
    }
}
