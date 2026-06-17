# BookChatAndroid UX Improvements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use sub-agents (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 7 UX shortcomings in the BookChat Android app: password leak, queue loss, no sort/filter, no search retry, no batch download, no clear recents, stuck downloads.

**Architecture:** All changes are within the existing single-module Android app. Each feature is independent and modifies 1-3 files. The app uses Hilt DI, Jetpack Compose UI, DataStore Preferences for persistence, and direct IRC + DCC TCP for book transfers.

**Implementation order:** Tasks 4, 5, and 6 all modify `ResultsList` and `SearchBar` in `SearchScreen.kt` — they must be implemented **in order** (4 → 5 → 6) to avoid merge conflicts. Tasks 1, 2, 3, 7 are independent and can be done in any order.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, DataStore Preferences, OkHttp (Drive only), Gradle 8.13, minSdk 26.

---

## Files Map

| Action | File | Responsibility |
|---|---|---|
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/irc/IrcClient.kt` | Mask password in `_sentLines` emission |
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt` | Add sort/filter state, selection state, lastQuery cache; mask password in diagnostics lines |
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt` | Sort/filter chips, batch select UI, retry button, pull-to-refresh, clear recents item |
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/service/DownloadRepository.kt` | Inject `DownloadQueueStore`; pass timeout to `DccDownloader` |
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/service/DccDownloader.kt` | Accept `timeoutSeconds` param; set `soTimeout` on socket |
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/data/settings/AppSettings.kt` | Add `downloadTimeoutSeconds` field |
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/data/settings/SettingsRepository.kt` | Add `downloadTimeoutSeconds` key; add `clearRecentSearches()` |
| 🔧 Modify | `app/src/main/kotlin/com/bookchat/ui/settings/SettingsScreen.kt` | Add timeout field to Settings UI |
| ✨ Create | `app/src/main/kotlin/com/bookchat/service/DownloadQueueStore.kt` | JSON serialization of queue via DataStore |

---

### Task 1: Mask Password in Diagnostics

**Files:**
- Modify: `app/src/main/kotlin/com/bookchat/irc/IrcClient.kt`
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt`

The IDENTIFY password line is sent via direct `pw.println()` (not `sendObserved()`), so it never reaches `_sentLines`. But the line must still be masked if an IRC server echoes it back via `inboundLines`. Two changes:

1. In `IrcClient.connect()`, change the IDENTIFY line to emit a masked version to `_sentLines` for consistency.
2. In `SearchViewModel.init()`, filter `IDENTIFY <password>` in both inbound and outbound diagnostics lines.

- [ ] **Step 1.1: Mask in `IrcClient.connect()`**

In the connect method, around line ~112, change:

```kotlin
if (settings.ircPassword.isNotBlank()) {
    pw.println("PRIVMSG NickServ :IDENTIFY ${settings.ircPassword}")
}
```

to:

```kotlin
if (settings.ircPassword.isNotBlank()) {
    val cmd = "PRIVMSG NickServ :IDENTIFY ${settings.ircPassword}"
    pw.println(cmd)
    _sentLines.tryEmit("PRIVMSG NickServ :IDENTIFY ••••")
}
```

This sends the real password to IRC but logs a masked version to diagnostics.

- [ ] **Step 1.2: Add safety filter in `SearchViewModel.init()`**

In the `init` block, in both the `inboundLines.collect` and `sentLines.collect` lambdas, apply a regex filter before calling `addDiagnosticsEntry`:

```kotlin
// At the top of SearchViewModel, add a companion/val:
private val passwordPattern = Regex("""(IDENTIFY)\s+\S+""", RegexOption.IGNORE_CASE)

// In the inboundLines collector, change:
if (relevant) addDiagnosticsEntry(...)
// to:
if (relevant) addDiagnosticsEntry(DiagnosticsEntry(
    DiagnosticsDirection.In,
    line.replace(passwordPattern, "$1 ••••"),
))

// In the sentLines collector, change:
addDiagnosticsEntry(DiagnosticsEntry(DiagnosticsDirection.Out, line))
// to:
addDiagnosticsEntry(DiagnosticsEntry(
    DiagnosticsDirection.Out,
    line.replace(passwordPattern, "$1 ••••"),
))
```

- [ ] **Step 1.3: Commit**

```bash
git add app/src/main/kotlin/com/bookchat/irc/IrcClient.kt \
       app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt
git commit -m "fix: mask IRC password in diagnostics logs"
```

---

### Task 2: Download Queue Persistence

**Files:**
- Create: `app/src/main/kotlin/com/bookchat/service/DownloadQueueStore.kt`
- Modify: `app/src/main/kotlin/com/bookchat/service/DownloadRepository.kt`
- Test: (manual — build APK, enqueue downloads, kill app, reopen, verify queue restored)

- [ ] **Step 2.1: Create `DownloadQueueStore.kt`**

New file at `app/src/main/kotlin/com/bookchat/service/DownloadQueueStore.kt`:

```kotlin
package com.bookchat.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val key = stringPreferencesKey("download_queue")
    private val maxItems = 20

    suspend fun load(): List<DownloadItem> {
        val json = dataStore.data.map { it[key] }.first() ?: return emptyList()
        return parseJson(json)
    }

    suspend fun save(items: List<DownloadItem>) {
        dataStore.edit { prefs ->
            if (items.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = toJson(items.take(maxItems))
            }
        }
    }

    private fun toJson(items: List<DownloadItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id.toString())
                put("downloadCommand", item.downloadCommand)
                put("fileHash", item.fileHash)
                put("expectedFileName", item.expectedFileName)
                put("displayTitle", item.displayTitle)
                put("format", item.format)
            })
        }
        return arr.toString()
    }

    private fun parseJson(json: String): List<DownloadItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            runCatching {
                DownloadItem(
                    id = UUID.fromString(obj.optString("id", UUID.randomUUID().toString())),
                    downloadCommand = obj.getString("downloadCommand"),
                    fileHash = obj.getString("fileHash"),
                    expectedFileName = obj.getString("expectedFileName"),
                    displayTitle = obj.getString("displayTitle"),
                    format = obj.getString("format"),
                    state = DownloadItemState.Queued,
                )
            }.getOrNull()
        }
    }
}
```

- [ ] **Step 2.2: Modify `DownloadRepository.kt` — inject store**

Add `private val queueStore: DownloadQueueStore` to the constructor. In the `init` block, load the persisted queue and enqueue any items. Add `callSave()` after every queue mutation.

Constructor change:

```kotlin
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ircRepository: IrcRepository,
    private val downloadsSaver: DownloadsSaver,
    private val driveUploader: DriveUploader,
    private val settingsRepository: SettingsRepository,
    private val searchRepository: SearchRepository,
    private val botStatsRepository: BotStatsRepository,
    private val userEventBus: UserEventBus,
    private val queueStore: DownloadQueueStore,  // ADD THIS
)
```

In the `init` block, add after existing init logic:

```kotlin
// Restore persisted queue
scope.launch {
    val saved = queueStore.load()
    if (saved.isNotEmpty()) {
        _queue.value = saved
        if (_activeDownload.value == null) processNext()
    }
}
```

Add a `callSave` helper:

```kotlin
private fun callSave() {
    scope.launch { queueStore.save(_queue.value) }
}
```

Call `callSave()` after every mutation of `_queue.value`:
- In `enqueue()` after `_queue.value = _queue.value + item`
- In `processNext()` after `_queue.value = _queue.value.drop(1)`
- In `removeFromQueue()` after `_queue.value = _queue.value.filter { ... }`

Also call it in `moveToCompleted()` to ensure the completed items are cleared from the persisted queue.

- [ ] **Step 2.3: Commit**

```bash
git add app/src/main/kotlin/com/bookchat/service/DownloadQueueStore.kt \
       app/src/main/kotlin/com/bookchat/service/DownloadRepository.kt
git commit -m "feat: persist download queue across app restarts"
```

---

### Task 3: Sort & Filter Search Results

**Files:**
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt`
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt`

- [ ] **Step 3.1: Add sort/filter state to `SearchViewModel.kt`**

Add to `SearchViewModel`:

```kotlin
enum class SortMode { RELIABILITY, SIZE, FORMAT }
enum class LanguageFilter { ALL, ENGLISH }

private val _sortMode = MutableStateFlow(SortMode.RELIABILITY)
val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

private val _languageFilter = MutableStateFlow(LanguageFilter.ALL)
val languageFilter: StateFlow<LanguageFilter> = _languageFilter.asStateFlow()

fun setSortMode(mode: SortMode) { _sortMode.value = mode }
fun setLanguageFilter(filter: LanguageFilter) { _languageFilter.value = filter }
```

Add a `sortedAndFiltered` derived flow. Import `import kotlinx.coroutines.flow.combine` and add:

```kotlin
val sortedAndFiltered: StateFlow<List<SearchResult>> =
    combine(results, _sortMode, _languageFilter) { list, sort, lang ->
        var filtered = list
        if (lang == LanguageFilter.ENGLISH) {
            filtered = filtered.filter {
                it.language.isBlank() || it.language.lowercase() in setOf("eng", "en", "english")
            }
        }
        when (sort) {
            SortMode.RELIABILITY -> filtered.sortedByDescending { it.reliabilityScore ?: -1.0 }
            SortMode.SIZE -> filtered.sortedByDescending { it.fileSizeBytes }
            SortMode.FORMAT -> filtered.sortedBy {
                when (it.format.uppercase()) {
                    "EPUB" -> 0; "MOBI" -> 1; "AZW3" -> 2; "PDF" -> 3; else -> 4
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

- [ ] **Step 3.2: Add sort/filter chips to `SearchScreen.kt`**

Above `ResultsList` in the `SearchScreen` composable, add a `SortFilterBar`:

```kotlin
@Composable
private fun SortFilterBar(
    sortMode: SortMode,
    languageFilter: LanguageFilter,
    onSortChange: (SortMode) -> Unit,
    onLanguageChange: (LanguageFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Sort dropdown
        var sortExpanded by remember { mutableStateOf(false) }
        FilterChip(
            selected = false,
            onClick = { sortExpanded = true },
            label = { Text("Sort: ${sortMode.label}") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = { onSortChange(mode); sortExpanded = false },
                )
            }
        }
        // Language filter dropdown
        var langExpanded by remember { mutableStateOf(false) }
        FilterChip(
            selected = languageFilter != LanguageFilter.ALL,
            onClick = { langExpanded = true },
            label = { Text(if (languageFilter == LanguageFilter.ALL) "All languages" else "English only") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
            LanguageFilter.ALL to "All languages",
            LanguageFilter.ENGLISH to "English only",
        ).forEach { (filter, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onLanguageChange(filter); langExpanded = false },
                )
            }
        }
    }
}
```

Add a `label` property to `SortMode`:

```kotlin
private val SortMode.label: String get() = when (this) {
    SortMode.RELIABILITY -> "Reliability"
    SortMode.SIZE -> "Size"
    SortMode.FORMAT -> "Format"
}
```

Place `SortFilterBar` in the main `SearchScreen` layout between `SearchBar`/`DiagnosticsPanel` and the results `Box`.

Change `ResultsList` to consume `sortedAndFiltered` instead of `results` directly:

```kotlin
val sortedResults by viewModel.sortedAndFiltered.collectAsStateWithLifecycle()
// ...
if (sortedResults.isNotEmpty()) -> ResultsList(sortedResults, onDownload = viewModel::onDownload)
```

Add the required imports to `SearchScreen.kt`:

```kotlin
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
```

- [ ] **Step 3.3: Commit**

```bash
git add app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt \
       app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt
git commit -m "feat: add sort (reliability/size/format) and language filter to search results"
```

---

### Task 4: Search Retry & Pull-to-Refresh

**Files:**
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt`
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt`

- [ ] **Step 4.1: Store last query in ViewModel**

In `SearchViewModel`:

```kotlin
private var _lastQuery = ""

fun onSearch() {
    val query = _searchText.value.trim()
    if (query.isBlank()) return
    _lastQuery = query
    viewModelScope.launch(Dispatchers.IO) { searchRepository.search(query) }
}

fun retryLastSearch() {
    val query = _lastQuery.ifBlank { _searchText.value.trim() }
    if (query.isBlank()) return
    onSearchTextChange(query)
    viewModelScope.launch(Dispatchers.IO) { searchRepository.search(query) }
}
```

- [ ] **Step 4.2: Add retry button to error state and pull-to-refresh**

In `SearchScreen.kt`:

Change the `when` block in the results `Box` to handle retry:

```kotlin
Box(modifier = Modifier.weight(1f)) {
    when (val state = searchState) {
        is SearchUiState.Searching -> SearchingIndicator()
        sortedResults.isNotEmpty() -> ResultsList(
            results = sortedResults,
            onDownload = viewModel::onDownload,
            onRefresh = { viewModel.retryLastSearch() },
        )
        is SearchUiState.NoResults -> NoResultsState(onRetry = viewModel::retryLastSearch)
        is SearchUiState.Error -> ErrorState(
            message = state.message,
            onRetry = viewModel::retryLastSearch,
        )
        else -> EmptyState()
    }
}
```

Add the `onRefresh` parameter to `ResultsList`:

```kotlin
@Composable
private fun ResultsList(
    results: List<SearchResult>,
    onDownload: (SearchResult) -> Unit,
    onRefresh: () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(results) { _, result ->
                BookResultCard(result = result, onDownload = onDownload)
            }
        }
    }
}
```

Add `ErrorState` composable:

```kotlin
@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠️", style = MaterialTheme.typography.displayMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        Button(onClick = onRetry) { Text("Retry") }
    }
}
```

Update `NoResultsState` to accept optional `onRetry`:

```kotlin
@Composable
private fun NoResultsState(onRetry: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CenteredContent(icon = "🔍", title = "No results found")
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
    }
}
```

Add required imports:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.PullToRefreshBox
import androidx.compose.foundation.layout.PaddingValues
```

- [ ] **Step 4.3: Commit**

```bash
git add app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt \
       app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt
git commit -m "feat: add search retry and pull-to-refresh on results"
```

---

### Task 5: Multi-Select Batch Downloads

**Files:**
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt`
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt`

- [ ] **Step 5.1: Add selection state to `SearchViewModel`**

```kotlin
private val _selectionMode = MutableStateFlow(false)
val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

private val _selectedHashes = MutableStateFlow<Set<String>>(emptySet())
val selectedHashes: StateFlow<Set<String>> = _selectedHashes.asStateFlow()

fun toggleSelectionMode() {
    _selectionMode.value = !_selectionMode.value
    if (!_selectionMode.value) _selectedHashes.value = emptySet()
}

fun toggleSelection(hash: String) {
    _selectedHashes.value = if (_selectedHashes.value.contains(hash)) {
        _selectedHashes.value - hash
    } else {
        _selectedHashes.value + hash
    }
}

fun batchDownload() {
    val selected = _selectedHashes.value
    val allResults = results.value
    selected.forEach { hash ->
        allResults.find { it.fileHash == hash }?.let { onDownload(it) }
    }
    _selectionMode.value = false
    _selectedHashes.value = emptySet()
}
```

- [ ] **Step 5.2: Add selection UI to `SearchScreen`**

Add a "Select" toggle button in the search bar. In `SearchBar` (or next to it), add:

```kotlin
// Inside the SearchScreen, near the SearchBar:
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    SearchBar(...) // existing, with Modifier.weight(1f)
    if (results.isNotEmpty()) {
        IconButton(onClick = viewModel::toggleSelectionMode) {
            Icon(
                imageVector = if (selectionMode) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (selectionMode) "Cancel selection" else "Select",
            )
        }
    }
}
```

Modify `BookResultCard` to show a checkbox when in selection mode. Add a `selectionMode` and `isSelected` parameter:

```kotlin
@Composable
fun BookResultCard(
    result: SearchResult,
    onDownload: (SearchResult) -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (selectionMode && onToggleSelection != null) {
            { onToggleSelection() }
        } else null,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection?.invoke() },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    // ... existing title/author content
                }
                // ... existing format pill
            }
            // ... existing rest
        }
    }
}
```

Add the `selectionMode` state propagation in `ResultsList`:

```kotlin
@Composable
private fun ResultsList(
    results: List<SearchResult>,
    onDownload: (SearchResult) -> Unit,
    onRefresh: () -> Unit,
    selectionMode: Boolean = false,
    selectedHashes: Set<String> = emptySet(),
    onToggleSelection: (String) -> Unit = {},
) {
    PullToRefreshBox(...) {
        LazyColumn(...) {
            itemsIndexed(results) { _, result ->
                BookResultCard(
                    result = result,
                    onDownload = onDownload,
                    selectionMode = selectionMode,
                    isSelected = selectedHashes.contains(result.fileHash),
                    onToggleSelection = { onToggleSelection(result.fileHash) },
                )
            }
        }
    }
}
```

Add a floating action bar at the bottom when `selectionMode && selectedHashes.isNotEmpty()`:

```kotlin
// Below the Box with results, overlay a bottom bar:
AnimatedVisibility(visible = selectionMode && selectedHashes.isNotEmpty()) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${selectedHashes.size} selected",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = viewModel::batchDownload) {
                Text("Download (${selectedHashes.size})")
            }
        }
    }
}
```

Wire everything in `SearchScreen`:

```kotlin
val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
val selectedHashes by viewModel.selectedHashes.collectAsStateWithLifecycle()

// In SearchScreenWithStrip { Column { ... } }
SearchScreenWithStrip {
    Column(modifier = Modifier.fillMaxSize()) {
        ConnectingBanner(connectionState)
        SearchBar(...)
        SortFilterBar(...)
        DiagnosticsPanel(...)
        Box(modifier = Modifier.weight(1f)) {
            when (val state = searchState) {
                // ... states with ResultsList(..., selectionMode = selectionMode, selectedHashes = selectedHashes, onToggleSelection = viewModel::toggleSelection)
            }
        }
        // Bottom batch bar
        AnimatedVisibility(visible = selectionMode && selectedHashes.isNotEmpty()) {
            Surface(...) { ... }
        }
    }
}
```

Add required imports:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
```

- [ ] **Step 5.3: Commit**

```bash
git add app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt \
       app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt
git commit -m "feat: add multi-select batch downloads with checkbox mode"
```

---

### Task 6: Clear Recent Searches

**Files:**
- Modify: `app/src/main/kotlin/com/bookchat/data/settings/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt`

- [ ] **Step 6.1: Add method to `SettingsRepository`**

```kotlin
suspend fun clearRecentSearches() {
    dataStore.edit { prefs ->
        prefs.remove(Keys.recentSearches)
    }
}
```

- [ ] **Step 6.2: Add "Clear history" to recent searches dropdown**

In `SearchScreen.kt`, in the `SearchBar` composable, inside the `DropdownMenu` for recent searches:

```kotlin
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
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("Clear history", color = MaterialTheme.colorScheme.error) },
        onClick = {
            onClearRecents()
            isFocused = false
        },
    )
}
```

Add the `clearRecentSearches()` method to `SearchViewModel`:

```kotlin
fun clearRecentSearches() {
    viewModelScope.launch {
        settingsRepository.clearRecentSearches()
    }
}
```

Wire it in `SearchBar` by passing it as a parameter:

```kotlin
// SearchBar signature:
onClearRecents: () -> Unit = {},
// In the dropdown:
DropdownMenuItem(
    text = { Text("Clear history", color = MaterialTheme.colorScheme.error) },
    onClick = { onClearRecents(); isFocused = false },
)
```

- [ ] **Step 6.3: Commit**

```bash
git add app/src/main/kotlin/com/bookchat/data/settings/SettingsRepository.kt \
       app/src/main/kotlin/com/bookchat/ui/search/SearchViewModel.kt \
       app/src/main/kotlin/com/bookchat/ui/search/SearchScreen.kt
git commit -m "feat: add clear recent searches"
```

---

### Task 7: Download Stuck-Transfer Timeout

**Files:**
- Modify: `app/src/main/kotlin/com/bookchat/data/settings/AppSettings.kt`
- Modify: `app/src/main/kotlin/com/bookchat/data/settings/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/com/bookchat/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/bookchat/service/DccDownloader.kt`
- Modify: `app/src/main/kotlin/com/bookchat/service/DownloadRepository.kt`

- [ ] **Step 7.1: Add field to `AppSettings`**

```kotlin
data class AppSettings(
    // ... existing fields
    val downloadTimeoutSeconds: Int = 60,
)
```

- [ ] **Step 7.2: Add DataStore key to `SettingsRepository`**

In the `Keys` object:

```kotlin
val downloadTimeoutSeconds = intPreferencesKey("download_timeout_seconds")
```

In the `settings` flow, add:

```kotlin
downloadTimeoutSeconds = prefs[Keys.downloadTimeoutSeconds] ?: 60,
```

In `save()`:

```kotlin
prefs[Keys.downloadTimeoutSeconds] = settings.downloadTimeoutSeconds
```

- [ ] **Step 7.3: Add `timeoutSeconds` param to `DccDownloader`**

Change the `download` function signature:

```kotlin
suspend fun download(
    ippDotted: String,
    port: Int,
    fileSize: Long,
    destFile: File,
    timeoutSeconds: Int = 60,
    onProgress: (bytesReceived: Long) -> Unit,
): Result<File> = withContext(Dispatchers.IO) {
```

After `socket.connect(InetSocketAddress(ippDotted, port), 30_000)`, add:

```kotlin
socket.soTimeout = timeoutSeconds * 1000
```

Also remove the `require(fileSize < Int.MAX_VALUE)` guard since it's overly restrictive (replace with a long-compatible ACK). Actually, the existing ACK uses `putInt(totalReceived.toInt())` which truncates. Let me keep this as is since fixing it is a separate concern — the timeout is the goal here.

Add a note in the code:

```kotlin
// Fail-safe: if no data received for timeoutSeconds, read() throws SocketTimeoutException
socket.soTimeout = timeoutSeconds * 1000
```

- [ ] **Step 7.4: Pass timeout from `DownloadRepository` to `DccDownloader`**

In `DownloadRepository.startDccTransfer()`, find the `DccDownloader.download()` call and add the timeout parameter:

```kotlin
val liveSettings = settingsRepository.settings.first()  // already read later in the method
val timeout = liveSettings.downloadTimeoutSeconds

val result = DccDownloader.download(
    ippDotted = offer.ippDotted,
    port = offer.port,
    fileSize = offer.fileSize,
    destFile = tempFile,
    timeoutSeconds = timeout,
    onProgress = { bytesReceived ->
        // ...
    },
)
```

Read `liveSettings` earlier or use a separate `settingsRepository.settings.first().downloadTimeoutSeconds`. The method already reads `liveSettings` later for Drive config — move it before the download call or read twice.

- [ ] **Step 7.5: Add UI field in Settings screen**

In `SettingsScreen.kt`, add within the `LazyColumn` after the IRC section items:

```kotlin
item { SectionHeader("Downloads") }
item {
    EditableField(
        label = "Download timeout (seconds)",
        value = settings.downloadTimeoutSeconds.toString(),
        keyboardType = KeyboardType.Number,
        validate = { it.toIntOrNull()?.let { v -> v in 1..3600 } ?: false },
        errorMessage = "Must be 1–3600",
        onSave = { viewModel.update { it.copy(downloadTimeoutSeconds = this.toInt()) } },
    )
}
```

- [ ] **Step 7.6: Commit**

```bash
git add app/src/main/kotlin/com/bookchat/data/settings/AppSettings.kt \
       app/src/main/kotlin/com/bookchat/data/settings/SettingsRepository.kt \
       app/src/main/kotlin/com/bookchat/ui/settings/SettingsScreen.kt \
       app/src/main/kotlin/com/bookchat/service/DccDownloader.kt \
       app/src/main/kotlin/com/bookchat/service/DownloadRepository.kt
git commit -m "feat: add configurable download timeout (default 60s) to prevent stuck transfers"
```

---

## Spec Coverage Check

| Spec section | Task |
|---|---|
| 1. Password masking | Task 1 |
| 2. Queue persistence | Task 2 |
| 3. Sort & filter | Task 3 |
| 4. Retry & pull-to-refresh | Task 4 |
| 5. Batch downloads | Task 5 |
| 6. Clear recents | Task 6 |
| 7. Download timeout | Task 7 |

All spec requirements covered.
