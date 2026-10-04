package com.example.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "A new version is available" prompt; shows download progress after "Update". */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onUpdate: (AppUpdate) -> Unit,
    onLater: () -> Unit
) {
    val update = when (state) {
        is UpdateState.Available -> state.update
        is UpdateState.Downloading -> state.update
        is UpdateState.Failed -> state.update
        UpdateState.None -> return
    }
    val downloading = state is UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (!downloading) onLater() },
        title = { Text("Update available") },
        text = {
            Column {
                Text("Version ${update.versionName} is ready to install.")
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(update.notes, style = MaterialTheme.typography.bodySmall)
                }
                when (state) {
                    is UpdateState.Downloading -> {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Download failed (${state.message}). Check the internet and try again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUpdate(update) }, enabled = !downloading) {
                Text(if (state is UpdateState.Failed) "Try again" else "Update")
            }
        },
        dismissButton = {
            if (!downloading) TextButton(onClick = onLater) { Text("Later") }
        }
    )
}
