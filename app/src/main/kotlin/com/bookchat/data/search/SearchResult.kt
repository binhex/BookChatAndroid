package com.bookchat.data.search

data class SearchResult(
    val botName: String,
    val fileHash: String,
    val author: String,
    val title: String,
    val series: String,
    val language: String,
    val format: String,
    val fileSize: String,
    val tags: String,
    val rawLine: String,
    val fileName: String,
    val downloadState: DownloadState = DownloadState.Idle,
) {
    val downloadCommand: String get() = "!$botName $fileHash"

    val fileSizeBytes: Long get() {
        val match = Regex(
            """(\d+\.?\d*)\s*(KB|MB|GB|KiB|MiB|GiB)""",
            RegexOption.IGNORE_CASE,
        ).find(fileSize) ?: return 0L
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        return when (match.groupValues[2].uppercase()) {
            "KB", "KIB" -> (value * 1024).toLong()
            "MB", "MIB" -> (value * 1024 * 1024).toLong()
            "GB", "GIB" -> (value * 1024 * 1024 * 1024).toLong()
            else -> 0L
        }
    }
}
