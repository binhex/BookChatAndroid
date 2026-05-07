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

    fun onSearchTextChange(text: String) { _searchText.value = text }

    fun onSearch() {
        val query = _searchText.value.trim()
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { searchRepository.search(query) }
    }

    fun onDownload(result: SearchResult) {
        downloadRepository.enqueue(
            DownloadItem(
                botName = result.downloadCommand, // already "!botName fileHash"
                fileHash = result.fileHash,
                expectedFileName = result.fileName,
                displayTitle = result.title.ifBlank { result.fileName },
                format = result.format,
            )
        )
    }
}
