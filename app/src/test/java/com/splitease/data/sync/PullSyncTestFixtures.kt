package com.splitease.data.sync

import com.splitease.data.remote.RemoteExpense
import com.splitease.data.remote.RemoteExpenseSplit
import com.splitease.data.remote.RemoteGroup
import com.splitease.data.remote.RemoteSettlement
import java.time.Instant

/**
 * Shared test fixtures for PullSyncService tests.
 * Centralizes factory methods to reduce duplication and improve maintainability.
 */
object PullSyncTestFixtures {

    /**
     * Creates a RemoteExpense with sensible defaults.
     *
     * @param id The expense ID
     * @param updatedAt Optional epoch millis for updated_at (defaults to 2024-01-01T11:00:00Z)
     * @param deletedAt Optional ISO-8601 deleted_at timestamp
     */
    fun createRemoteExpense(
        id: String,
        updatedAtMillis: Long? = null,
        deletedAt: String? = null
    ): RemoteExpense {
        val updatedAtStr = updatedAtMillis?.let { 
            Instant.ofEpochMilli(it).toString() 
        } ?: "2024-01-01T11:00:00Z"
        
        return RemoteExpense(
            id = id,
            group_id = "g1",
            title = "Test Expense",
            amount = "10.0",
            currency = "USD",
            date = "2024-01-01T10:00:00Z",
            payer_id = "u1",
            created_by = "TestUser",
            updated_at = updatedAtStr,
            deleted_at = deletedAt,
            created_by_user_id = "u1",
            last_modified_by_user_id = "u1",
            sync_status = "SYNCED",
            expense_date = 1704103200000L // 2024-01-01
        )
    }

    /**
     * Creates a RemoteExpenseSplit with the given parameters.
     */
    fun createRemoteSplit(
        expenseId: String,
        userId: String,
        amount: Double
    ): RemoteExpenseSplit {
        return RemoteExpenseSplit(
            expense_id = expenseId,
            user_id = userId,
            amount = amount.toString()
        )
    }

    /**
     * Creates a RemoteGroup with sensible defaults.
     */
    fun createRemoteGroup(
        id: String,
        name: String = "Test Group",
        updatedAtMillis: Long? = null,
        deletedAt: String? = null
    ): RemoteGroup {
        val updatedAtStr = updatedAtMillis?.let {
            Instant.ofEpochMilli(it).toString()
        } ?: "2024-01-01T11:00:00Z"

        return RemoteGroup(
            id = id,
            name = name,
            type = "general",
            cover_url = null,
            created_by = "TestUser",
            has_trip_dates = false,
            trip_start_date = null,
            trip_end_date = null,
            created_by_user_id = "u1",
            last_modified_by_user_id = "u1",
            updated_at = updatedAtStr,
            deleted_at = deletedAt
        )
    }

    /**
     * Creates a RemoteSettlement with sensible defaults.
     */
    fun createRemoteSettlement(
        id: String,
        fromUserId: String = "u1",
        toUserId: String = "u2",
        amount: Double = 50.0,
        updatedAtMillis: Long? = null,
        deletedAt: String? = null
    ): RemoteSettlement {
        val updatedAtStr = updatedAtMillis?.let {
            Instant.ofEpochMilli(it).toString()
        } ?: "2024-01-01T11:00:00Z"

        return RemoteSettlement(
            id = id,
            group_id = "g1",
            from_user_id = fromUserId,
            to_user_id = toUserId,
            amount = amount.toString(),
            date = "2024-01-01T10:00:00Z",
            created_by_user_id = "u1",
            last_modified_by_user_id = "u1",
            updated_at = updatedAtStr,
            deleted_at = deletedAt
        )
    }
}
