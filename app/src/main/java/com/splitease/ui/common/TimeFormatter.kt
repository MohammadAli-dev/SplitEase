package com.splitease.ui.common

import android.text.format.DateUtils
import java.util.concurrent.TimeUnit

/** Centralized time formatting utilities for the app. */
object TimeFormatter {

    /**
     * Formats a timestamp as relative time (e.g., "2h ago", "Yesterday").
     *
     * Handles edge cases:
     * - 0L or negative values return "Just now"
     * - Future timestamps return "Just now"
     * - Very old dates show absolute date
     *
     * @param timestamp The timestamp in milliseconds since epoch
     * @return A human-readable relative time string
     */
    fun formatRelativeTime(timestamp: Long): String {
        if (timestamp <= 0L) {
            return "Just now"
        }

        val now = System.currentTimeMillis()

        // Handle future timestamps gracefully
        if (timestamp > now) {
            return "Just now"
        }

        val diff = now - timestamp

        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                "$minutes min ago"
            }
            diff < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(diff)
                "$hours hr ago"
            }
            diff < TimeUnit.DAYS.toMillis(2) -> "Yesterday"
            diff < TimeUnit.DAYS.toMillis(7) -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                "$days days ago"
            }
            else -> {
                // For older dates, use system's relative time formatting
                DateUtils.getRelativeTimeSpanString(
                                timestamp,
                                now,
                                DateUtils.DAY_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE
                        )
                        .toString()
            }
        }
    }
}
