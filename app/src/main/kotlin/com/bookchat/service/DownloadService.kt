package com.bookchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bookchat.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloadRepository: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private val launchIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", 0, 0))
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeProgress() {
        scope.launch {
            downloadRepository.activeDownload.collect { item ->
                val notification = when (val state = item?.state) {
                    is DownloadItemState.Downloading -> {
                        val pct = if (state.totalBytes > 0)
                            (state.bytesReceived * 100 / state.totalBytes).toInt() else 0
                        buildNotification(
                            text = "${item.displayTitle} · ${formatSpeed(state.speedBps)}",
                            progress = pct,
                            max = 100,
                        )
                    }
                    is DownloadItemState.RequestSent, is DownloadItemState.DccOffered ->
                        buildNotification("Waiting for ${item.displayTitle}…", 0, 0, indeterminate = true)
                    null -> buildNotification("Download complete", 0, 0)
                    else -> return@collect
                }
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean = false,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("BookChat")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentIntent(launchIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .apply {
            if (max > 0 || indeterminate) setProgress(max, progress, indeterminate)
        }
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "BookChat download progress"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000 -> "${bps / 1_000_000} MB/s"
        bps >= 1_000 -> "${bps / 1_000} KB/s"
        else -> "$bps B/s"
    }

    companion object {
        const val CHANNEL_ID = "download_progress"
        const val NOTIFICATION_ID = 1
    }
}
