package com.bookchat.data.search

import com.bookchat.data.settings.SettingsRepository
import com.bookchat.data.stats.BotStatsRepository
import com.bookchat.irc.IrcRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Searching : SearchUiState()
    data class ResultsReady(val count: Int) : SearchUiState()
    data object NoResults : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

@Singleton
class SearchRepository @Inject constructor(
    private val ircRepository: IrcRepository,
    private val botStatsRepository: BotStatsRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    init {
        scope.launch {
            ircRepository.dccOffers.collect { offer ->
                // Search results zip comes from a nick containing "Search"
                if (offer.senderNick.contains("Search", ignoreCase = true) &&
                    offer.fileName.endsWith(".zip", ignoreCase = true)
                ) {
                    receiveSearchZip(offer)
                }
            }
        }
    }

    suspend fun search(query: String) {
        _results.value = emptyList()
        _searchState.value = SearchUiState.Searching
        settingsRepository.addRecentSearch(query)
        val channel = settingsRepository.settings.first().ircChannel
        ircRepository.sendRaw("PRIVMSG $channel :@search $query")
    }

    fun updateResultState(fileName: String, state: DownloadState) {
        _results.value = _results.value.map {
            if (it.fileName == fileName) it.copy(downloadState = state) else it
        }
    }

    private suspend fun receiveSearchZip(offer: com.bookchat.irc.DccOffer) {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                val socket = Socket()
                socket.connect(InetSocketAddress(offer.ippDotted, offer.port), 30_000)
                socket.use {
                    val input = it.getInputStream()
                    val output = it.getOutputStream()
                    val buf = ByteArray(8192)
                    val baos = ByteArrayOutputStream()
                    var totalReceived = 0L
                    var lastAcked = 0L

                    var read = input.read(buf)
                    while (read >= 0) {
                        baos.write(buf, 0, read)
                        totalReceived += read

                        if (totalReceived - lastAcked >= 4096) {
                            val ack = java.nio.ByteBuffer.allocate(4)
                                .putInt(totalReceived.toInt()).array()
                            output.write(ack)
                            output.flush()
                            lastAcked = totalReceived
                        }

                        if (totalReceived >= offer.fileSize) break
                        read = input.read(buf)
                    }

                    // Final ACK
                    val ack = java.nio.ByteBuffer.allocate(4).putInt(totalReceived.toInt()).array()
                    output.write(ack)
                    output.flush()

                    baos.toByteArray()
                }
            }.getOrNull()
        } ?: run {
            _searchState.value = SearchUiState.Error("Failed to receive search results")
            return
        }

        val parsed = withContext(Dispatchers.Default) {
            SearchResultParser.parseZip(bytes)
        }

        if (parsed.isEmpty()) {
            _searchState.value = SearchUiState.NoResults
        } else {
            val stats = botStatsRepository.allStats.first()
            val ranked = ResultRanker.rank(parsed, stats)
            _results.value = ranked.map { result ->
                val s = stats[result.botName]
                if (s != null && s.successes + s.failures > 0)
                    result.copy(reliabilityScore = s.reliabilityScore)
                else result
            }
            _searchState.value = SearchUiState.ResultsReady(parsed.size)
        }
    }
}
