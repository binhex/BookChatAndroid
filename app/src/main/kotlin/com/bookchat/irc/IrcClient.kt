package com.bookchat.irc

import com.bookchat.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min
import kotlin.math.pow

class IrcClient {

    private val _connectionState = MutableStateFlow<IrcConnectionState>(IrcConnectionState.Disconnected)
    val connectionState: StateFlow<IrcConnectionState> = _connectionState.asStateFlow()

    private val _inboundLines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val inboundLines = _inboundLines.asSharedFlow()

    @Volatile private var writer: PrintWriter? = null
    @Volatile private var socket: Socket? = null

    fun sendRaw(line: String) {
        writer?.println(line)
    }

    // Runs forever — caller cancels the coroutine to stop.
    suspend fun connectLoop(settings: AppSettings) {
        var attempt = 0
        while (true) {
            _connectionState.value = IrcConnectionState.Connecting(attempt)
            try {
                connect(settings)
                // Clean disconnect (server closed connection) — retry immediately
                attempt = 0
            } catch (e: CancellationException) {
                _connectionState.value = IrcConnectionState.Disconnected
                throw e
            } catch (e: Exception) {
                // Failed connection — brief backoff, capped at 15s so the user
                // doesn't wait long after the screen wakes up
                val backoffMs = min(2_000L * 2.0.pow(attempt).toLong(), 15_000L)
                attempt++
                _connectionState.value = IrcConnectionState.Connecting(attempt)
                delay(backoffMs)
            }
        }
    }

    private suspend fun connect(settings: AppSettings) = withContext(Dispatchers.IO) {
        val sock = Socket()
        socket = sock
        try {
            sock.connect(InetSocketAddress(settings.ircServer, settings.ircPort), 30_000)
            // Keep the OS-level TCP connection alive so the server doesn't time out
            // when the phone screen is off and the network is briefly quiet.
            sock.keepAlive = true
            sock.soTimeout = 0 // blocking reads; PING/PONG handles server-side keepalive

            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val pw = PrintWriter(sock.getOutputStream(), true)
            writer = pw

            pw.println("NICK ${settings.ircNickname}")
            pw.println("USER bookchat 0 * :BookChat Android")

            var registered = false

            while (!sock.isClosed) {
                val raw = reader.readLine() ?: break
                val line = raw.trimEnd()

                if (line.startsWith("PING")) {
                    pw.println("PONG ${line.substring(5)}")
                    continue
                }

                if (!registered && line.contains(" 001 ")) {
                    registered = true
                    if (settings.ircPassword.isNotBlank()) {
                        pw.println("PRIVMSG NickServ :IDENTIFY ${settings.ircPassword}")
                    }
                    pw.println("JOIN ${settings.ircChannel}")
                    _connectionState.value = IrcConnectionState.Connected
                }

                _inboundLines.emit(line)
            }
        } finally {
            writer = null
            socket = null
            _connectionState.value = IrcConnectionState.Disconnected
            runCatching { sock.close() }
        }
    }

    fun disconnect() {
        runCatching { socket?.close() }
    }
}
