package com.bookchat.ui.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class UserEvent {
    data class ShowSnackbar(val message: String) : UserEvent()
}

@Singleton
class UserEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<UserEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UserEvent> = _events.asSharedFlow()

    fun emit(event: UserEvent) { _events.tryEmit(event) }
    fun snackbar(message: String) = emit(UserEvent.ShowSnackbar(message))
}
