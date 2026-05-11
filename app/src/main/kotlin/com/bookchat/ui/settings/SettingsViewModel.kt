package com.bookchat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.bookchat.data.settings.AppSettings
import com.bookchat.data.settings.SettingsRepository
import com.bookchat.service.DriveUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveUploader: DriveUploader,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings(),
        )

    fun update(settings: AppSettings) {
        viewModelScope.launch { settingsRepository.save(settings) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.save(transform(settings.value))
        }
    }

    fun parseDriveFolderId(input: String): String = driveUploader.parseFolderId(input)

    // Non-null when Drive needs user authorization — launch this intent to show consent screen
    private val _driveAuthIntent = MutableStateFlow<Intent?>(null)
    val driveAuthIntent: StateFlow<Intent?> = _driveAuthIntent.asStateFlow()

    fun testDriveAuth(onResult: (String) -> Unit) {
        val accountName = settings.value.driveAccountName
        val folderId = settings.value.driveFolderId
        if (accountName.isBlank() || folderId.isBlank()) {
            onResult("Set account and folder ID first")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = driveUploader.testAuth(accountName)
            result.fold(
                onSuccess = { onResult("Drive authorized ✓") },
                onFailure = { e ->
                    val intent = driveUploader.lastAuthIntent
                    if (intent != null) {
                        _driveAuthIntent.value = intent
                        onResult("Authorization required — tap Authorize below")
                    } else {
                        onResult("Auth failed: ${e.message}")
                    }
                }
            )
        }
    }

    fun clearDriveAuthIntent() { _driveAuthIntent.value = null }
}
