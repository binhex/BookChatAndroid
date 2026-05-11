package com.bookchat.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookchat.service.DownloadItem
import com.bookchat.service.DownloadItemState
import com.bookchat.ui.common.CenteredContent
import com.bookchat.ui.common.SectionHeader
import com.bookchat.ui.common.formatSpeed

@Composable
fun DownloadsScreen(viewModel: DownloadViewModel = hiltViewModel()) {
    val active by viewModel.activeDownload.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val completed by viewModel.completed.collectAsStateWithLifecycle()

    if (active == null && queue.isEmpty() && completed.isEmpty()) {
        DownloadsEmptyState()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        active?.let { item ->
            item {
                SectionHeader("Active")
                ActiveDownloadCard(item = item, onStop = viewModel::onCancel)
            }
        }

        if (queue.isNotEmpty()) {
            item { SectionHeader("Queue (${queue.size})") }
            items(queue, key = { it.id }) { item ->
                QueuedItemCard(item = item, onRemove = { viewModel.onRemoveFromQueue(item.id) })
            }
        }

        if (completed.isNotEmpty()) {
            item { SectionHeader("Recently Completed") }
            items(completed, key = { it.id }) { item ->
                CompletedItemCard(item = item, onRetry = { viewModel.onRetry(item) })
            }
        }
    }
}


@Composable
private fun ActiveDownloadCard(item: DownloadItem, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val state = item.state) {
                is DownloadItemState.Downloading -> {
                    val progress = if (state.totalBytes > 0)
                        state.bytesReceived.toFloat() / state.totalBytes else 0f
                    val pct = (progress * 100).toInt()
                    val speed = formatSpeed(state.speedBps)
                    val eta = formatEta(state.etaSeconds)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("$speed · $eta", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$pct%", style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    )
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(top = 4.dp))
                }
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Stop") }
        }
    }
}

@Composable
private fun QueuedItemCard(item: DownloadItem, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove from queue")
            }
        }
    }
}

@Composable
private fun CompletedItemCard(item: DownloadItem, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val failedState = item.state as? DownloadItemState.Failed
            Column(modifier = Modifier.weight(1f)) {
                val prefix = when (item.state) {
                    is DownloadItemState.Done -> "✓ "
                    is DownloadItemState.SavedLocally -> "⚠ "
                    is DownloadItemState.Cancelled -> "— "
                    else -> "✗ "
                }
                Text(
                    text = "$prefix${item.displayTitle}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (failedState != null) {
                    Text(
                        text = failedState.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val savedLocally = item.state as? DownloadItemState.SavedLocally
                if (savedLocally != null) {
                    Text(
                        text = "Saved to Downloads/BookChat — ${savedLocally.driveError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (failedState != null || item.state is DownloadItemState.SavedLocally) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun DownloadsEmptyState() {
    CenteredContent(icon = "⬇", title = "All clear", subtitle = "No downloads in progress")
}

private fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> ""
    seconds < 60 -> "~${seconds}s"
    else -> "~${seconds / 60}m ${seconds % 60}s"
}
