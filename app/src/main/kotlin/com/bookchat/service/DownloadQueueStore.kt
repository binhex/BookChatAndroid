package com.bookchat.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val key = stringPreferencesKey("download_queue")
    private val maxItems = 20

    suspend fun load(): List<DownloadItem> {
        val raw = dataStore.data.map { it[key] }.first() ?: return emptyList()
        return runCatching { parseJson(raw) }
            .onFailure { Log.e("DownloadQueueStore", "Failed to parse queue JSON", it) }
            .getOrDefault(emptyList())
    }

    suspend fun save(items: List<DownloadItem>) {
        dataStore.edit { prefs ->
            if (items.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = toJson(items.take(maxItems))
            }
        }
    }

    private fun toJson(items: List<DownloadItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id.toString())
                put("downloadCommand", item.downloadCommand)
                put("fileHash", item.fileHash)
                put("expectedFileName", item.expectedFileName)
                put("displayTitle", item.displayTitle)
                put("format", item.format)
            })
        }
        return arr.toString()
    }

    private fun parseJson(json: String): List<DownloadItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            runCatching {
                if (obj.isNull("id")) return@runCatching null
                val idStr = obj.getString("id")
                DownloadItem(
                    id = UUID.fromString(idStr),
                    downloadCommand = obj.getString("downloadCommand"),
                    fileHash = obj.getString("fileHash"),
                    expectedFileName = obj.getString("expectedFileName"),
                    displayTitle = obj.getString("displayTitle"),
                    format = obj.getString("format"),
                    state = DownloadItemState.Queued,
                )
            }.onFailure { e ->
                Log.w("DownloadQueueStore", "Failed to parse queue item at index $i: ${e.message}")
            }.getOrNull()
        }
    }
}
