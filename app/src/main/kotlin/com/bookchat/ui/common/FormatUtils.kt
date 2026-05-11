package com.bookchat.ui.common

fun formatSpeed(bps: Long): String = when {
    bps >= 1_000_000 -> "${bps / 1_000_000} MB/s"
    bps >= 1_000     -> "${bps / 1_000} KB/s"
    else             -> "$bps B/s"
}
