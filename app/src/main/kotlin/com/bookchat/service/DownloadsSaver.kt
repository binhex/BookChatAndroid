package com.bookchat.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadsSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun save(file: File, watchFolderUri: String): Result<String> {
        return if (watchFolderUri.isNotBlank()) {
            saveToWatchFolder(file, Uri.parse(watchFolderUri))
        } else {
            saveToDownloads(file)
        }
    }

    private fun saveToWatchFolder(file: File, folderUri: Uri): Result<String> = runCatching {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw Exception("Cannot access watch folder — please re-select it in Settings")

        val mimeType = mimeTypeFor(file)
        val destFile = folder.createFile(mimeType, file.nameWithoutExtension)
            ?: throw Exception("Could not create file in watch folder")

        context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw Exception("Could not write to watch folder")

        file.delete()
        "Saved to watch folder: ${file.name}"
    }

    private fun saveToDownloads(file: File): Result<String> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(file))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BookChat")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create file in Downloads")

        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw Exception("Could not open output stream")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        file.delete()
        "Saved to Downloads/BookChat/${file.name}"
    }

    private fun mimeTypeFor(file: File) = when (file.extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
