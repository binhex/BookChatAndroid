package com.bookchat.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentFile: File

    init {
        val dir = File(context.filesDir, "irc_logs")
        dir.mkdirs()
        // Keep last 4 sessions; the new one makes 5
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(4)
            ?.forEach { it.delete() }

        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        currentFile = File(dir, "BookChat_IRC_Log_$ts.txt")
        val header = "=== BookChat IRC Log — " +
            "${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} ===\n"
        scope.launch { currentFile.writeText(header) }
    }

    /** [marker] is one of ←, →, ◉ — kept as a plain string to avoid a UI-layer dependency. */
    fun append(marker: String, time: String, text: String) {
        scope.launch { currentFile.appendText("$time $marker $text\n") }
    }
}
