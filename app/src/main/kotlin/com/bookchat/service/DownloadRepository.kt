package com.bookchat.service

import android.content.Context
import android.content.Intent
import com.bookchat.data.search.DownloadState
import com.bookchat.data.search.SearchRepository
import com.bookchat.data.settings.SettingsRepository
import com.bookchat.irc.IrcRepository
import com.bookchat.ui.common.UserEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ircRepository: IrcRepository,
    private val downloadsSaver: DownloadsSaver,
    private val settingsRepository: SettingsRepository,
    private val searchRepository: SearchRepository,
    private val userEventBus: UserEventBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

    private val _activeDownload = MutableStateFlow<DownloadItem?>(null)
    val activeDownload: StateFlow<DownloadItem?> = _activeDownload.asStateFlow()

    private val _completed = MutableStateFlow<List<DownloadItem>>(emptyList())
    val completed: StateFlow<List<DownloadItem>> = _completed.asStateFlow()

    private var downloadJob: Job? = null

    // Cached settings — updated continuously, avoids runBlocking in enqueue()
    @Volatile private var cachedSettings = com.bookchat.data.settings.AppSettings()

    init {
        scope.launch { settingsRepository.settings.collect { cachedSettings = it } }
        scope.launch {
            ircRepository.dccOffers.collect { offer ->
                val active = _activeDownload.value
                val isSearchZip = offer.senderNick.contains("Search", ignoreCase = true) &&
                        offer.fileName.endsWith(".zip", ignoreCase = true)
                if (active?.state == DownloadItemState.RequestSent && !isSearchZip) {
                    startDccTransfer(active, offer)
                }
            }
        }
    }

    fun enqueue(item: DownloadItem) {
        _queue.value = _queue.value + item
        startServiceIfNeeded()
        if (_activeDownload.value == null) processNext(cachedSettings.ircChannel)
    }

    fun cancelActive() {
        downloadJob?.cancel()
        val active = _activeDownload.value ?: return
        moveToCompleted(active.copy(state = DownloadItemState.Cancelled))
        searchRepository.updateResultState(active.expectedFileName, DownloadState.Idle)
        _activeDownload.value = null
        processNext()
    }

    fun removeFromQueue(id: UUID) {
        _queue.value = _queue.value.filter { it.id != id }
    }

    fun retry(item: DownloadItem) {
        _completed.value = _completed.value.filter { it.id != item.id }
        enqueue(item.copy(id = UUID.randomUUID(), state = DownloadItemState.Queued))
    }

    private fun processNext(channel: String = cachedSettings.ircChannel) {
        val next = _queue.value.firstOrNull() ?: run {
            stopService()
            return
        }
        _queue.value = _queue.value.drop(1)
        _activeDownload.value = next.copy(state = DownloadItemState.RequestSent)
        ircRepository.sendRaw("PRIVMSG $channel :${next.botName}")
        searchRepository.updateResultState(next.expectedFileName, DownloadState.Requesting)
    }

    private fun startDccTransfer(item: DownloadItem, offer: com.bookchat.irc.DccOffer) {
        updateActive(item.copy(state = DownloadItemState.DccOffered))
        searchRepository.updateResultState(item.expectedFileName, DownloadState.Downloading(0f))

        downloadJob = scope.launch {
            val tempFile = File(context.cacheDir, "downloads/${offer.fileName}")
            tempFile.parentFile?.mkdirs()

            val speedWindow = ArrayDeque<Pair<Long, Long>>()
            var lastUpdate = System.currentTimeMillis()

            val result = DccDownloader.download(
                ippDotted = offer.ippDotted,
                port = offer.port,
                fileSize = offer.fileSize,
                destFile = tempFile,
                onProgress = { bytesReceived ->
                    val now = System.currentTimeMillis()
                    speedWindow.addLast(now to bytesReceived)
                    while (speedWindow.size > 1 && now - speedWindow.first().first > 3000) {
                        speedWindow.removeFirst()
                    }
                    if (now - lastUpdate >= 1000) {
                        val deltaBytes = bytesReceived - (speedWindow.firstOrNull()?.second ?: 0L)
                        val deltaMs = (now - (speedWindow.firstOrNull()?.first ?: now)).coerceAtLeast(1L)
                        val speedBps = deltaBytes * 1000 / deltaMs
                        val eta = if (speedBps > 0) (offer.fileSize - bytesReceived) / speedBps else 0L
                        updateActive(item.copy(state = DownloadItemState.Downloading(
                            bytesReceived = bytesReceived,
                            totalBytes = offer.fileSize,
                            speedBps = speedBps,
                            etaSeconds = eta,
                        )))
                        searchRepository.updateResultState(
                            item.expectedFileName,
                            DownloadState.Downloading(bytesReceived.toFloat() / offer.fileSize),
                        )
                        lastUpdate = now
                    }
                },
            )

            result.fold(
                onSuccess = { file ->
                    val saveResult = downloadsSaver.save(file, cachedSettings.watchFolderUri)
                    saveResult.fold(
                        onSuccess = {
                            moveToCompleted(item.copy(state = DownloadItemState.Done))
                            searchRepository.updateResultState(item.expectedFileName, DownloadState.Done)
                        },
                        onFailure = { e ->
                            val msg = e.message ?: "Save failed"
                            moveToCompleted(item.copy(state = DownloadItemState.Failed(msg)))
                            searchRepository.updateResultState(item.expectedFileName, DownloadState.Failed(msg))
                        },
                    )
                },
                onFailure = { e ->
                    if (e !is CancellationException) {
                        val msg = e.message ?: "Download failed"
                        moveToCompleted(item.copy(state = DownloadItemState.Failed(msg)))
                        searchRepository.updateResultState(item.expectedFileName, DownloadState.Failed(msg))
                    }
                },
            )

            _activeDownload.value = null
            processNext()
        }
    }

    private fun updateActive(item: DownloadItem) { _activeDownload.value = item }

    private fun moveToCompleted(item: DownloadItem) {
        _activeDownload.value = null
        _completed.value = (listOf(item) + _completed.value).take(10)
    }

    private fun startServiceIfNeeded() {
        context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    private fun stopService() {
        context.stopService(Intent(context, DownloadService::class.java))
    }
}
