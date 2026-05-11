package com.bookchat.irc

import com.bookchat.data.settings.AppSettings
import com.bookchat.data.settings.SettingsRepository
import com.bookchat.ui.common.UserEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IrcRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val userEventBus: UserEventBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = IrcClient()

    val connectionState: StateFlow<IrcConnectionState> = client.connectionState
    val inboundLines: SharedFlow<String> = client.inboundLines

    // Kept for legacy collectors; UserEventBus is now the canonical channel
    private val _reconnectEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnectEvent: SharedFlow<Unit> = _reconnectEvent

    private val _dccOffers = kotlinx.coroutines.flow.MutableSharedFlow<DccOffer>(extraBufferCapacity = 8)
    val dccOffers: SharedFlow<DccOffer> = _dccOffers

    private var connectJob: Job? = null
    private var everConnected = false

    init {
        scope.launch {
            settingsRepository.settings
                .distinctUntilChangedBy {
                    listOf(it.ircServer, it.ircPort, it.ircChannel, it.ircNickname, it.ircUseSsl)
                }
                .collect { settings -> reconnect(settings) }
        }
        // Parse DCC SEND offers from inbound IRC lines
        scope.launch {
            client.inboundLines.collect { line ->
                if (line.contains("DCC SEND", ignoreCase = true)) {
                    val nickMatch = Regex("""^:([^!]+)!""").find(line)
                    val senderNick = nickMatch?.groupValues?.get(1) ?: ""
                    DccParser.parse(line, senderNick)?.let { _dccOffers.emit(it) }
                }
            }
        }
    }

    fun sendRaw(line: String) {
        scope.launch { client.sendRaw(line) }
    }

    private suspend fun reconnect(settings: AppSettings) {
        val wasRunning = connectJob?.isActive == true
        connectJob?.cancel()
        connectJob?.join()
        // Only show snackbar when the user changed a setting while already connected,
        // not on the initial launch.
        if (wasRunning && everConnected) {
            _reconnectEvent.tryEmit(Unit)
            userEventBus.snackbar("Reconnecting…")
        }
        everConnected = true
        connectJob = scope.launch { client.connectLoop(settings) }
    }
}
