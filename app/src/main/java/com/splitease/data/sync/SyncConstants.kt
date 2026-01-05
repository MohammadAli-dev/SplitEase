package com.splitease.data.sync

/**
 * Constants for sync behavior thresholds.
 */
object SyncConstants {
    /** Time after which pending operations are considered "paused" (5 minutes) */
    const val PAUSED_THRESHOLD_MS = 5 * 60 * 1000L
    
    /** Debounce period for manual sync button (5 seconds) */
    const val MANUAL_SYNC_DEBOUNCE_MS = 5_000L
}
