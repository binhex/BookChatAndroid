package com.bookchat.irc

sealed class IrcConnectionState {
    data object Disconnected : IrcConnectionState()
    data class Connecting(val attempt: Int) : IrcConnectionState()
    data object Connected : IrcConnectionState()

    val label: String get() = when (this) {
        is Disconnected -> "Disconnected"
        is Connecting -> "Connecting…"
        is Connected -> "Connected"
    }
}
