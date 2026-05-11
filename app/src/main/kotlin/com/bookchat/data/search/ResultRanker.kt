package com.bookchat.data.search

import com.bookchat.data.stats.BotStats

object ResultRanker {

    fun rank(results: List<SearchResult>, botStats: Map<String, BotStats>): List<SearchResult> =
        results.sortedByDescending { score(it, botStats[it.botName]) }

    private fun score(result: SearchResult, stats: BotStats?): Double {
        val lang = languageScore(result.language) * 4.0
        val fmt = formatScore(result.format) * 2.0
        val reliability = (stats?.reliabilityScore ?: 0.5)
        val speed = speedScore(stats)
        return lang + fmt + reliability + speed
    }

    private fun languageScore(language: String): Double = when {
        language.isBlank() -> 1.0  // no tag = assume English
        language.lowercase() in setOf("eng", "en", "english") -> 1.0
        else -> 0.0
    }

    private fun formatScore(format: String): Double = when (format.uppercase()) {
        "EPUB"       -> 1.0
        "MOBI"       -> 0.8
        "AZW3"       -> 0.6
        "RAR", "ZIP" -> 0.4
        "HTML"       -> 0.2
        "PDF"        -> 0.0
        else         -> 0.1
    }

    // Normalised: 0 KB/s = 0, 500 KB/s = 0.5, ≥1 MB/s = 1.0 (max 0.5 contribution)
    private fun speedScore(stats: BotStats?): Double {
        val bps = stats?.avgSpeedBps ?: return 0.0
        return (bps.toDouble() / 1_000_000.0).coerceAtMost(1.0) * 0.5
    }
}
