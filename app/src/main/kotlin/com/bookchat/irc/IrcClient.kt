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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.min
import kotlin.math.pow

class IrcClient {

    private val _connectionState = MutableStateFlow<IrcConnectionState>(IrcConnectionState.Disconnected)
    val connectionState: StateFlow<IrcConnectionState> = _connectionState.asStateFlow()

    private val _inboundLines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val inboundLines = _inboundLines.asSharedFlow()

    private val _sentLines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val sentLines = _sentLines.asSharedFlow()

    @Volatile private var writer: PrintWriter? = null
    @Volatile private var socket: Socket? = null

    fun sendRaw(line: String) {
        writer?.println(line)
        _sentLines.tryEmit(line)
    }

    // Runs forever — caller cancels the coroutine to stop.
    suspend fun connectLoop(settings: AppSettings) {
        var attempt = 0
        var lastError: String? = null
        while (true) {
            // Preserve last error so the banner stays readable across retries
            _connectionState.value = IrcConnectionState.Connecting(attempt, lastError)
            try {
                connect(settings)
                attempt = 0
                lastError = null
            } catch (e: CancellationException) {
                _connectionState.value = IrcConnectionState.Disconnected
                throw e
            } catch (e: Exception) {
                val errMsg = e.message?.substringAfterLast(": ")?.trim() ?: e.javaClass.simpleName
                lastError = "$errMsg (${settings.ircServer}:${settings.ircPort}${if (settings.ircUseSsl) " SSL" else ""})"
                val backoffMs = min(2_000L * 2.0.pow(attempt).toLong(), 15_000L)
                attempt++
                _connectionState.value = IrcConnectionState.Connecting(attempt, lastError)
                delay(backoffMs)
            }
        }
    }

    private suspend fun connect(settings: AppSettings) = withContext(Dispatchers.IO) {
        val plain = Socket()
        plain.connect(InetSocketAddress(settings.ircServer, settings.ircPort), 30_000)
        val sock: Socket = if (settings.ircUseSsl) {
            val ssl = trustAllSslFactory()
                .createSocket(plain, settings.ircServer, settings.ircPort, true) as SSLSocket
            ssl.soTimeout = 30_000
            ssl.startHandshake()
            ssl
        } else {
            plain
        }
        socket = sock
        try {
            sock.keepAlive = true
            sock.soTimeout = 30_000 // covers first server line; reset to 0 after first data

            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val pw = PrintWriter(sock.getOutputStream(), true)
            writer = pw

            sendObserved(pw, "NICK ${settings.ircNickname}")
            sendObserved(pw, "USER bookchat 0 * :BookChat Android")

            var registered = false
            var firstLine = true

            while (!sock.isClosed) {
                val raw = reader.readLine() ?: break
                if (firstLine) {
                    // 5-minute read timeout — detects silently stale connections faster
                    // than the OS TCP keepalive (default ~2 h on Android).
                    sock.soTimeout = 300_000
                    firstLine = false
                }
                val line = raw.trimEnd()

                if (line.startsWith("PING")) {
                    pw.println("PONG ${line.substring(5)}")
                    continue
                }

                if (!registered && line.contains(" 001 ")) {
                    registered = true
                    if (settings.ircPassword.isNotBlank()) {
                        val cmd = "PRIVMSG NickServ :IDENTIFY ${settings.ircPassword}"
                        pw.println(cmd)
                        _sentLines.tryEmit("PRIVMSG NickServ :IDENTIFY \u2022\u2022\u2022\u2022")
                    }
                    sendObserved(pw, "JOIN ${settings.ircChannel}")
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

    // Sends a line and emits it to sentLines. Use for all non-sensitive outbound lines.
    private fun sendObserved(pw: PrintWriter, line: String) {
        pw.println(line)
        _sentLines.tryEmit(line)
    }

    fun disconnect() {
        runCatching { socket?.close() }
    }

    // IRC SSL certs are frequently self-signed — disable verification.
    // SSL here is used to bypass carrier port blocking, not for certificate trust.
    private fun trustAllSslFactory(): SSLSocketFactory {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }.socketFactory
    }
}
