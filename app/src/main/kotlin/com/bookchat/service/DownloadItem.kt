package com.bookchat.service

import java.util.UUID

data class DownloadItem(
    val id: UUID = UUID.randomUUID(),
    val botName: String,
    val fileHash: String,
    val expectedFileName: String,
    val displayTitle: String,
    val format: String,
    val state: DownloadItemState = DownloadItemState.Queued,
)

sealed class DownloadItemState {
    data object Queued : DownloadItemState()
    data object RequestSent : DownloadItemState()
    data object DccOffered : DownloadItemState()
    data class Downloading(
        val bytesReceived: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val etaSeconds: Long,
    ) : DownloadItemState()
    data object Done : DownloadItemState()
    data class SavedLocally(val driveError: String) : DownloadItemState()
    data class Failed(val reason: String) : DownloadItemState()
    data object Cancelled : DownloadItemState()
}
