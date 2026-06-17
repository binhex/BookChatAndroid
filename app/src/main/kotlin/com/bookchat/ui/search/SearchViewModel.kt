package com.bookchat.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookchat.data.search.SearchRepository
import com.bookchat.data.search.SearchResult
import com.bookchat.data.search.SearchUiState
import com.bookchat.data.settings.SettingsRepository
import com.bookchat.irc.IrcConnectionState
import com.bookchat.irc.IrcRepository
import com.bookchat.service.DiagnosticsLogger
import com.bookchat.service.DownloadItem
import com.bookchat.service.DownloadRepository
import com.bookchat.service.DriveUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val MAX_DIAGNOSTICS_ENTRIES = 75

private val numericReplyRegex = Regex("""\s\d{3}\s""")

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val ircRepository: IrcRepository,
    private val searchRepository: SearchRepository,
    private val settingsRepository: SettingsRepository,
    private val downloadRepository: DownloadRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val driveUploader: DriveUploader,
) : ViewModel() {

    val connectionState: StateFlow<IrcConnectionState> = ircRepository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IrcConnectionState.Disconnected)

    val results: StateFlow<List<SearchResult>> = searchRepository.results
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val searchState: StateFlow<SearchUiState> = searchRepository.searchState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle)

    val recentSearches: StateFlow<List<String>> = settingsRepository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _diagnosticsExpanded = MutableStateFlow(false)
    val diagnosticsExpanded: StateFlow<Boolean> = _diagnosticsExpanded.asStateFlow()

    private val _diagnosticsEntries = MutableStateFlow<List<DiagnosticsEntry>>(emptyList())
    val diagnosticsEntries: StateFlow<List<DiagnosticsEntry>> = _diagnosticsEntries.asStateFlow()

    private val _exportStatus = MutableStateFlow("")
    val exportStatus: StateFlow<String> = _exportStatus.asStateFlow()

    @Volatile private var currentNick = ""

    private val passwordPattern = Regex("""(IDENTIFY)\s+\S+""", RegexOption.IGNORE_CASE)

    enum class SortMode(val label: String) {
        RELIABILITY("Reliability"),
        SIZE("Size"),
        FORMAT("Format"),
    }
    enum class LanguageFilter { ALL, ENGLISH }

    private val _sortMode = MutableStateFlow(SortMode.RELIABILITY)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _languageFilter = MutableStateFlow(LanguageFilter.ALL)
    val languageFilter: StateFlow<LanguageFilter> = _languageFilter.asStateFlow()

    private var _lastQuery = ""

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedHashes = MutableStateFlow<Set<String>>(emptySet())
    val selectedHashes: StateFlow<Set<String>> = _selectedHashes.asStateFlow()

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

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { currentNick = it.ircNickname }
        }
        viewModelScope.launch {
            ircRepository.inboundLines.collect { line ->
                val nick = currentNick
                val relevant = (nick.isNotBlank() && line.contains(nick, ignoreCase = true))
                    || line.startsWith("ERROR", ignoreCase = true)
                    || numericReplyRegex.containsMatchIn(line)
                if (relevant) addDiagnosticsEntry(DiagnosticsEntry(
                    DiagnosticsDirection.In,
                    line.replace(passwordPattern, "$1 \u2022\u2022\u2022\u2022"),
                ))
            }
        }
        viewModelScope.launch {
            ircRepository.sentLines.collect { line ->
                addDiagnosticsEntry(DiagnosticsEntry(
                    DiagnosticsDirection.Out,
                    line.replace(passwordPattern, "$1 \u2022\u2022\u2022\u2022"),
                ))
            }
        }
        viewModelScope.launch {
            ircRepository.connectionState.collect { state ->
                val text = when (state) {
                    is IrcConnectionState.Connected    -> "Connected"
                    is IrcConnectionState.Disconnected -> "Disconnected"
                    is IrcConnectionState.Connecting   ->
                        if (state.lastError != null)
                            "Connecting (attempt ${state.attempt}: ${state.lastError})"
                        else "Connecting…"
                }
                addDiagnosticsEntry(DiagnosticsEntry(DiagnosticsDirection.System, text))
            }
        }
    }

    fun toggleDiagnostics() { _diagnosticsExpanded.value = !_diagnosticsExpanded.value }

    fun exportLog() {
        viewModelScope.launch(Dispatchers.IO) {
            _exportStatus.value = "Uploading…"
            val settings = settingsRepository.settings.first()
            if (settings.driveAccountName.isBlank() || settings.driveFolderId.isBlank()) {
                _exportStatus.value = "Drive not configured — set account and folder in Settings"
                return@launch
            }
            val result = driveUploader.upload(
                file = diagnosticsLogger.currentFile,
                accountName = settings.driveAccountName,
                folderId = settings.driveFolderId,
                deleteAfterUpload = false,
            )
            _exportStatus.value = if (result.isSuccess) {
                "Saved to Drive ✓"
            } else {
                "Upload failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private fun addDiagnosticsEntry(entry: DiagnosticsEntry) {
        _diagnosticsEntries.value = (_diagnosticsEntries.value + entry).takeLast(MAX_DIAGNOSTICS_ENTRIES)
        val marker = when (entry.direction) {
            DiagnosticsDirection.In     -> "←"
            DiagnosticsDirection.Out    -> "→"
            DiagnosticsDirection.System -> "◉"
        }
        diagnosticsLogger.append(marker, entry.time, entry.text)
    }

    fun onSearchTextChange(text: String) { _searchText.value = text }

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
        onSearch()
    }

    fun setSortMode(mode: SortMode) { _sortMode.value = mode }
    fun setLanguageFilter(filter: LanguageFilter) { _languageFilter.value = filter }

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
        try {
            val selected = _selectedHashes.value
            val allResults = results.value
            selected.forEach { hash ->
                allResults.find { it.fileHash == hash }?.let { onDownload(it) }
            }
        } finally {
            _selectionMode.value = false
            _selectedHashes.value = emptySet()
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            settingsRepository.clearRecentSearches()
        }
    }

    fun onDownload(result: SearchResult) {
        downloadRepository.enqueue(
            DownloadItem(
                downloadCommand = result.downloadCommand,
                fileHash = result.fileHash,
                expectedFileName = result.fileName,
                displayTitle = result.title.ifBlank { result.fileName },
                format = result.format,
            )
        )
    }
}
