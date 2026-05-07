package com.bookchat.data.search

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Requesting : DownloadState()
    data object Queued : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data object Done : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}
