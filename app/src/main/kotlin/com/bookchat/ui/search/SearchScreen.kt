package com.bookchat.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookchat.data.search.DownloadState
import com.bookchat.data.search.SearchResult
import com.bookchat.data.search.SearchUiState
import com.bookchat.irc.IrcConnectionState
import com.bookchat.ui.common.CenteredContent

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()

    SearchScreenWithStrip {
        Column(modifier = Modifier.fillMaxSize()) {
            ConnectingBanner(connectionState)
            SearchBar(
                text = searchText,
                enabled = connectionState is IrcConnectionState.Connected,
                recentSearches = recentSearches,
                onTextChange = viewModel::onSearchTextChange,
                onSearch = viewModel::onSearch,
            )
            Box(modifier = Modifier.weight(1f)) {
                when {
                    searchState is SearchUiState.Searching -> SearchingIndicator()
                    results.isNotEmpty() -> ResultsList(results, onDownload = viewModel::onDownload)
                    searchState is SearchUiState.NoResults -> NoResultsState()
                    else -> EmptyState()
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    text: String,
    enabled: Boolean,
    recentSearches: List<String>,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val showRecents = isFocused && recentSearches.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Search by title or author…") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboardController?.hide()
                onSearch()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
        )
        DropdownMenu(
            expanded = showRecents,
            onDismissRequest = { isFocused = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            recentSearches.forEach { query ->
                DropdownMenuItem(
                    text = { Text(query) },
                    onClick = {
                        keyboardController?.hide()
                        onTextChange(query)
                        isFocused = false
                        onSearch()
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultsList(results: List<SearchResult>, onDownload: (SearchResult) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(results) { _, result ->
            BookResultCard(result = result, onDownload = onDownload)
        }
    }
}

@Composable
fun BookResultCard(result: SearchResult, onDownload: (SearchResult) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.title.ifBlank { result.fileName },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.author.isNotBlank()) {
                        Text(
                            text = result.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (result.format.isNotBlank()) {
                    FormatPill(format = result.format)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        if (result.fileSize.isNotBlank()) append(result.fileSize)
                        if (result.fileSize.isNotBlank() && result.botName.isNotBlank()) append(" · ")
                        if (result.botName.isNotBlank()) append(result.botName)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DownloadButton(state = result.downloadState, onClick = { onDownload(result) })
            }

            if (result.downloadState is DownloadState.Downloading) {
                LinearProgressIndicator(
                    progress = { (result.downloadState as DownloadState.Downloading).progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadButton(state: DownloadState, onClick: () -> Unit) {
    when (state) {
        is DownloadState.Idle -> Button(onClick = onClick) { Text("Download") }
        is DownloadState.Requesting -> Button(onClick = {}, enabled = false) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Requesting…", modifier = Modifier.padding(start = 6.dp))
        }
        is DownloadState.Queued -> Button(onClick = {}, enabled = false) { Text("Queued") }
        is DownloadState.Downloading -> Button(onClick = {}, enabled = false) { Text("Downloading") }
        is DownloadState.Done -> SuggestionChip(
            onClick = {},
            enabled = false,
            label = { Text("✓ Saved") },
        )
        is DownloadState.Failed -> Button(onClick = onClick) { Text("Retry") }
    }
}

@Composable
private fun FormatPill(format: String) {
    val upper = format.uppercase()
    val color = when (upper) {
        "EPUB" -> Color(0xFF2E7D32)
        "MOBI" -> Color(0xFF1565C0)
        "AZW3" -> Color(0xFF6A1B9A)
        "PDF" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = upper,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun IrcStatusDot(state: IrcConnectionState, modifier: Modifier = Modifier) {
    val color = when (state) {
        is IrcConnectionState.Disconnected -> Color.Gray
        is IrcConnectionState.Connecting -> Color(0xFFFFA000)
        is IrcConnectionState.Connected -> Color(0xFF4CAF50)
    }
    Canvas(modifier = modifier.size(10.dp), contentDescription = state.label) {
        drawCircle(color = color)
    }
}

@Composable
private fun ConnectingBanner(state: IrcConnectionState) {
    AnimatedVisibility(visible = state !is IrcConnectionState.Connected) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                IrcStatusDot(state)
                Text(
                    text = if (state is IrcConnectionState.Connecting) "Connecting to IRC…" else "Disconnected from IRC",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchingIndicator() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Waiting for results…", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun NoResultsState() {
    CenteredContent(icon = "🔍", title = "No results found")
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    CenteredContent(
        icon = "📚",
        title = "Find your next read",
        subtitle = "Search by title or author and\nBookChat will fetch it for you",
        modifier = modifier,
    )
}
