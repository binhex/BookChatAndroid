package com.bookchat.data.stats

data class BotStats(
    val successes: Int = 0,
    val failures: Int = 0,
    val totalSpeedBps: Long = 0,
    val sampleCount: Int = 0,
) {
    /** 0.0–1.0. Starts at 0.5 (neutral) for unknown bots. */
    val reliabilityScore: Double get() =
        if (successes + failures == 0) 0.5
        else successes.toDouble() / (successes + failures)

    val avgSpeedBps: Long get() =
        if (sampleCount == 0) 0L else totalSpeedBps / sampleCount

    fun withSuccess(speedBps: Long) = copy(
        successes = successes + 1,
        totalSpeedBps = totalSpeedBps + speedBps,
        sampleCount = sampleCount + 1,
    )

    fun withFailure() = copy(failures = failures + 1)
}
