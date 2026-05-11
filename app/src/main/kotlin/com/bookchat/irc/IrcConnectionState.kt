package com.bookchat.irc

sealed class IrcConnectionState {
    data object Disconnected : IrcConnectionState()
    data class Connecting(val attempt: Int, val lastError: String? = null) : IrcConnectionState()
    data object Connected : IrcConnectionState()

    val label: String get() = when (this) {
        is Disconnected -> "Disconnected"
        is Connecting -> if (lastError != null) "Connecting… ($lastError)" else "Connecting…"
        is Connected -> "Connected"
    }
}
