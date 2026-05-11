package com.bookchat.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookchat.data.search.SearchRepository
import com.bookchat.data.search.SearchResult
import com.bookchat.data.search.SearchUiState
import com.bookchat.data.settings.SettingsRepository
import com.bookchat.irc.IrcConnectionState
import com.bookchat.irc.IrcRepository
import com.bookchat.service.DownloadItem
import com.bookchat.service.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    @Volatile private var currentNick = ""

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
                if (relevant) addDiagnosticsEntry(DiagnosticsEntry(DiagnosticsDirection.In, line))
            }
        }
        viewModelScope.launch {
            ircRepository.sentLines.collect { line ->
                addDiagnosticsEntry(DiagnosticsEntry(DiagnosticsDirection.Out, line))
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

    private fun addDiagnosticsEntry(entry: DiagnosticsEntry) {
        _diagnosticsEntries.value = (_diagnosticsEntries.value + entry).takeLast(MAX_DIAGNOSTICS_ENTRIES)
    }

    fun onSearchTextChange(text: String) { _searchText.value = text }

    fun onSearch() {
        val query = _searchText.value.trim()
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { searchRepository.search(query) }
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
