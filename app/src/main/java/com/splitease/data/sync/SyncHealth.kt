package com.splitease.data.sync

/**
 * Derived sync health state — computed from SyncDao, not persisted.
 */
data class SyncHealth(
    val pendingCount: Int,
    val failedCount: Int,
    val oldestPendingAgeMillis: Long?
)

/**
 * UI-facing sync state, derived from SyncHealth in ViewModel.
 * Evaluated in strict priority order: FAILED > PAUSED > SYNCING > IDLE.
 */
enum class SyncState {
    FAILED,   // ⚠️ Some changes couldn't be synced
    PAUSED,   // 💤 Sync paused — waiting for network
    SYNCING,  // ⏳ Syncing changes...
    IDLE      // No indicator
}
