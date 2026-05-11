package com.bookchat.data.stats

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BotStatsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val key = stringPreferencesKey("bot_stats")

    val allStats: Flow<Map<String, BotStats>> = dataStore.data.map { prefs ->
        prefs[key]?.let { parseJson(it) } ?: emptyMap()
    }

    suspend fun recordSuccess(botName: String, speedBps: Long) {
        update(botName) { it.withSuccess(speedBps) }
    }

    suspend fun recordFailure(botName: String) {
        update(botName) { it.withFailure() }
    }

    private suspend fun update(botName: String, transform: (BotStats) -> BotStats) {
        dataStore.edit { prefs ->
            val map = prefs[key]?.let { parseJson(it) }?.toMutableMap() ?: mutableMapOf()
            map[botName] = transform(map[botName] ?: BotStats())
            prefs[key] = toJson(map)
        }
    }

    private fun parseJson(json: String): Map<String, BotStats> = runCatching {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { botName ->
            val entry = obj.getJSONObject(botName)
            BotStats(
                successes = entry.optInt("s"),
                failures = entry.optInt("f"),
                totalSpeedBps = entry.optLong("t"),
                sampleCount = entry.optInt("n"),
            )
        }
    }.getOrDefault(emptyMap())

    private fun toJson(map: Map<String, BotStats>): String {
        val obj = JSONObject()
        map.forEach { (botName, stats) ->
            obj.put(botName, JSONObject().apply {
                put("s", stats.successes)
                put("f", stats.failures)
                put("t", stats.totalSpeedBps)
                put("n", stats.sampleCount)
            })
        }
        return obj.toString()
    }
}
