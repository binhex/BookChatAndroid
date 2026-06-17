package com.bookchat.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ircServer = stringPreferencesKey("irc_server")
        val ircPort = intPreferencesKey("irc_port")
        val ircUseSsl = androidx.datastore.preferences.core.booleanPreferencesKey("irc_use_ssl")
        val ircChannel = stringPreferencesKey("irc_channel")
        val ircNickname = stringPreferencesKey("irc_nickname")
        val ircPassword = stringPreferencesKey("irc_password")
        val watchFolderUri = stringPreferencesKey("watch_folder_uri")
        val driveAccountName = stringPreferencesKey("drive_account_name")
        val driveFolderId = stringPreferencesKey("drive_folder_id")
        val recentSearches = stringPreferencesKey("recent_searches")
        val downloadTimeoutSeconds = intPreferencesKey("download_timeout_seconds")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            ircServer = prefs[Keys.ircServer] ?: "irc.irchighway.net",
            ircPort = prefs[Keys.ircPort] ?: 6667,
            ircUseSsl = prefs[Keys.ircUseSsl] ?: false,
            ircChannel = prefs[Keys.ircChannel] ?: "#ebooks",
            ircNickname = prefs[Keys.ircNickname] ?: generateDefaultNick(),
            ircPassword = prefs[Keys.ircPassword] ?: "",
            watchFolderUri = prefs[Keys.watchFolderUri] ?: "",
            driveAccountName = prefs[Keys.driveAccountName] ?: "",
            driveFolderId = prefs[Keys.driveFolderId] ?: "",
            downloadTimeoutSeconds = prefs[Keys.downloadTimeoutSeconds] ?: 60,
        )
    }

    val recentSearches: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[Keys.recentSearches]
            ?.split("|")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun save(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.ircServer] = settings.ircServer
            prefs[Keys.ircPort] = settings.ircPort
            prefs[Keys.ircUseSsl] = settings.ircUseSsl
            prefs[Keys.ircChannel] = settings.ircChannel
            prefs[Keys.ircNickname] = settings.ircNickname
            prefs[Keys.ircPassword] = settings.ircPassword
            prefs[Keys.watchFolderUri] = settings.watchFolderUri
            prefs[Keys.driveAccountName] = settings.driveAccountName
            prefs[Keys.driveFolderId] = settings.driveFolderId
            prefs[Keys.downloadTimeoutSeconds] = settings.downloadTimeoutSeconds
        }
    }

    suspend fun addRecentSearch(query: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.recentSearches]
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val updated = (listOf(query) + current).distinct().take(10)
            prefs[Keys.recentSearches] = updated.joinToString("|")
        }
    }

    suspend fun clearRecentSearches() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.recentSearches)
        }
    }

    private fun generateDefaultNick(): String =
        "BookChat_${Random.nextInt(1000, 9999)}"
}
