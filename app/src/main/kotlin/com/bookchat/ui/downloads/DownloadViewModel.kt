package com.bookchat.ui.downloads

import androidx.lifecycle.ViewModel
import com.bookchat.service.DownloadItem
import com.bookchat.service.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    val activeDownload: StateFlow<DownloadItem?> = downloadRepository.activeDownload
    val queue: StateFlow<List<DownloadItem>> = downloadRepository.queue
    val completed: StateFlow<List<DownloadItem>> = downloadRepository.completed

    fun onCancel() = downloadRepository.cancelActive()
    fun onRemoveFromQueue(id: UUID) = downloadRepository.removeFromQueue(id)
    fun onRetry(item: DownloadItem) = downloadRepository.retry(item)
}
