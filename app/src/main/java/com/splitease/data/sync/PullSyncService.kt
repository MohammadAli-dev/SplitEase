package com.splitease.data.sync

import android.util.Log
import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.TokenManager
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.Settlement
import com.splitease.data.remote.RemoteExpense
import com.splitease.data.remote.RemoteExpenseSplit
import com.splitease.data.remote.RemoteGroup
import com.splitease.data.remote.RemoteSettlement
import com.splitease.data.remote.SplitEaseApi
import retrofit2.Response
import com.splitease.data.identity.IdentityConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles pull-based sync: fetching remote data and reconciling it into local database.
 *
 * Key Architectural Decisions:
 * - Stable UUIDs: Local ID == Remote ID (no mapping tables)
 * - Conflict Resolution: Timestamp-based, unsynced-aware (NOT naive LWW)
 * - Server Authority: updated_at timestamps are server-generated
 * - Global Cursor: Single lastSyncedAt cursor (max of remote timestamps)
 */
interface PullSyncService {
    /**
     * Perform a full pull sync operation.
     * 1. Push pending operations first (if any)
     * 2. Fetch remote updates since last sync
     * 3. Reconcile with local data using conflict rules
     * 4. Update sync cursor
     *
     * @return PullSyncResult indicating success/failure
     */
    suspend fun performPullSync(): PullSyncResult
}

sealed class PullSyncResult {
    data class Success(
        val expensesInserted: Int,
        val expensesUpdated: Int,
        val expensesDeleted: Int,
        val groupsInserted: Int,
        val groupsUpdated: Int,
        val settlementsInserted: Int,
        val settlementsUpdated: Int
    ) : PullSyncResult()

    data class Error(val message: String) : PullSyncResult()
}

@Singleton
class PullSyncServiceImpl @Inject constructor(
    private val api: SplitEaseApi,
    private val syncMetadataStore: SyncMetadataStore,
    private val tokenManager: TokenManager,
    private val expenseDao: ExpenseDao,
    private val groupDao: GroupDao,
    private val settlementDao: SettlementDao,
    private val syncDao: SyncDao,
    private val db: AppDatabase
) : PullSyncService {

    companion object {
        private const val TAG = "PullSyncService"
        // PAGE_SIZE matches PostgREST/Supabase default max-rows.
        // If server returns exactly PAGE_SIZE, we fetch next page until response size < PAGE_SIZE.
        private const val PAGE_SIZE = 1000
        private val ISO_8601_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override suspend fun performPullSync(): PullSyncResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting pull sync...")

            // Get auth headers
            val accessToken = tokenManager.getAccessToken()
            if (accessToken.isNullOrBlank()) {
                Log.e(TAG, "Pull sync failed: No access token")
                return@withContext PullSyncResult.Error("Not authenticated")
            }
            val authHeader = "Bearer $accessToken"
            val apiKey = AuthConfig.supabasePublicKey

            // Get last sync cursor
            val lastSyncedAt = syncMetadataStore.getLastSyncedAt() ?: "1970-01-01T00:00:00.000Z"
            Log.d(TAG, "Pull sync: cursor=$lastSyncedAt")

            // Track stats
            var expensesInserted = 0
            var expensesUpdated = 0
            var expensesDeleted = 0
            var groupsInserted = 0
            var groupsUpdated = 0
            var settlementsInserted = 0
            var settlementsUpdated = 0
            var maxRemoteTimestamp = lastSyncedAt

            // 1. Fetch and reconcile Groups first (expenses depend on groups)
            val groups = fetchAllPagesOfGroups(authHeader, apiKey, lastSyncedAt)
            for (remoteGroup in groups) {
                val result = reconcileGroup(remoteGroup)
                when (result) {
                    ReconcileAction.INSERT -> groupsInserted++
                    ReconcileAction.UPDATE -> groupsUpdated++
                    ReconcileAction.DELETE -> {} // tracked separately
                    ReconcileAction.SKIP -> {}
                }
                if (remoteGroup.updated_at > maxRemoteTimestamp) {
                    maxRemoteTimestamp = remoteGroup.updated_at
                }
            }

            // 2. Fetch and reconcile Expenses
            val expenses = fetchAllPagesOfExpenses(authHeader, apiKey, lastSyncedAt)
            val expenseIds = expenses.map { it.id }
            val remoteSplits = if (expenseIds.isNotEmpty()) {
                fetchAllPagesOfSplits(authHeader, apiKey, expenseIds)
            } else {
                emptyList()
            }
            val splitsByExpenseId = remoteSplits.groupBy { it.expense_id }

            for (remoteExpense in expenses) {
                val splits = splitsByExpenseId[remoteExpense.id] ?: emptyList()
                val result = reconcileExpense(remoteExpense, splits)
                when (result) {
                    ReconcileAction.INSERT -> expensesInserted++
                    ReconcileAction.UPDATE -> expensesUpdated++
                    ReconcileAction.DELETE -> expensesDeleted++
                    ReconcileAction.SKIP -> {}
                }
                if (remoteExpense.updated_at > maxRemoteTimestamp) {
                    maxRemoteTimestamp = remoteExpense.updated_at
                }
            }

            // 3. Fetch and reconcile Settlements
            val settlements = fetchAllPagesOfSettlements(authHeader, apiKey, lastSyncedAt)
            for (remoteSettlement in settlements) {
                val result = reconcileSettlement(remoteSettlement)
                when (result) {
                    ReconcileAction.INSERT -> settlementsInserted++
                    ReconcileAction.UPDATE -> settlementsUpdated++
                    ReconcileAction.DELETE -> {} // tracked separately
                    ReconcileAction.SKIP -> {}
                }
                if (remoteSettlement.updated_at > maxRemoteTimestamp) {
                    maxRemoteTimestamp = remoteSettlement.updated_at
                }
            }

            // 4. Update cursor to max(remote.updated_at)
            if (maxRemoteTimestamp > lastSyncedAt) {
                syncMetadataStore.setLastSyncedAt(maxRemoteTimestamp)
                Log.d(TAG, "Cursor advanced: $lastSyncedAt -> $maxRemoteTimestamp")
            }

            val result = PullSyncResult.Success(
                expensesInserted = expensesInserted,
                expensesUpdated = expensesUpdated,
                expensesDeleted = expensesDeleted,
                groupsInserted = groupsInserted,
                groupsUpdated = groupsUpdated,
                settlementsInserted = settlementsInserted,
                settlementsUpdated = settlementsUpdated
            )
            Log.d(TAG, "Pull sync complete: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Pull sync failed: ${e.message}", e)
            PullSyncResult.Error("Pull sync failed: ${e.message}")
        }
    }

    // --- Pagination Helpers ---

    private suspend fun <T> fetchAllPages(
        entityName: String,
        fetchPage: suspend (rangeHeader: String) -> Response<List<T>>
    ): List<T> {
        val allItems = mutableListOf<T>()
        var offset = 0
        while (true) {
            val rangeHeader = "$offset-${offset + PAGE_SIZE - 1}"
            val response = fetchPage(rangeHeader)
            
            if (!response.isSuccessful) {
                val errorMsg = "$entityName fetch failed at offset $offset: HTTP ${response.code()}"
                Log.e(TAG, errorMsg)
                throw java.io.IOException(errorMsg)
            }
            
            val page = response.body() ?: emptyList<T>()
            allItems.addAll(page)
            
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return allItems
    }

    private suspend fun fetchAllPagesOfExpenses(
        authHeader: String,
        apiKey: String,
        lastSyncedAt: String
    ): List<RemoteExpense> = fetchAllPages("Expense") { range ->
        api.getExpenseUpdates(
            authHeader = authHeader,
            apiKey = apiKey,
            updatedAtFilter = "gt.$lastSyncedAt",
            rangeHeader = range
        )
    }

    private suspend fun fetchAllPagesOfGroups(
        authHeader: String,
        apiKey: String,
        lastSyncedAt: String
    ): List<RemoteGroup> = fetchAllPages("Group") { range ->
        api.getGroupUpdates(
            authHeader = authHeader,
            apiKey = apiKey,
            updatedAtFilter = "gt.$lastSyncedAt",
            rangeHeader = range
        )
    }

    private suspend fun fetchAllPagesOfSettlements(
        authHeader: String,
        apiKey: String,
        lastSyncedAt: String
    ): List<RemoteSettlement> = fetchAllPages("Settlement") { range ->
        api.getSettlementUpdates(
            authHeader = authHeader,
            apiKey = apiKey,
            updatedAtFilter = "gt.$lastSyncedAt",
            rangeHeader = range
        )
    }

    private suspend fun fetchAllPagesOfSplits(
        authHeader: String,
        apiKey: String,
        expenseIds: List<String>
    ): List<RemoteExpenseSplit> {
        // TODO(Sprint 14): Implement batching if expenseIds > 150 (Stay under 8KB URL limit)
        require(expenseIds.size <= 200) {
            "Too many expense IDs for single fetch: ${expenseIds.size}. " +
            "Batching will be implemented in Sprint 14. See issue #40"
        }
        
        val filter = "in.(${expenseIds.joinToString(",")})"
        
        return fetchAllPages("ExpenseSplit") { range ->
            api.getExpenseSplits(
                authHeader = authHeader,
                apiKey = apiKey,
                expenseIdFilter = filter,
                rangeHeader = range
            )
        }
    }

    // --- Reconciliation Logic ---

    private enum class ReconcileAction { INSERT, UPDATE, DELETE, SKIP }

    /**
     * Reconcile a remote expense with local data.
     * Conflict Resolution: Timestamp-based, unsynced-aware.
     */
    private suspend fun reconcileExpense(
        remote: RemoteExpense,
        remoteSplits: List<RemoteExpenseSplit>
    ): ReconcileAction {
        val local = expenseDao.getExpenseById(remote.id)
        val remoteUpdatedAt = parseIso8601ToEpochMillis(remote.updated_at)

        // Soft delete check
        if (remote.deleted_at != null) {
            if (local != null) {
                expenseDao.deleteExpenseWithSplits(remote.id)
                Log.d(TAG, "Reconcile[EXPENSE:${remote.id}]: DELETE (remote soft-deleted)")
                return ReconcileAction.DELETE
            }
            Log.d(TAG, "Reconcile[EXPENSE:${remote.id}]: SKIP (remote deleted, local missing)")
            return ReconcileAction.SKIP
        }

        if (local == null) {
            // INSERT: Remote exists, local missing
            val expense = mapRemoteToLocalExpense(remote, remoteUpdatedAt)
            val splits = remoteSplits.map { mapRemoteToLocalSplit(it) }
            expenseDao.insertExpenseWithSplits(expense, splits)
            Log.d(TAG, "Reconcile[EXPENSE:${remote.id}]: INSERT")
            return ReconcileAction.INSERT
        }

        // Both exist: Check conflict resolution
        val localUpdatedAt = local.updatedAt
        val isLocalDirty = hasPendingSyncOp(remote.id)

        when {
            remoteUpdatedAt == localUpdatedAt -> {
                Log.d(TAG, "Reconcile[EXPENSE:${remote.id}]: SKIP (timestamps equal)")
                return ReconcileAction.SKIP
            }
            isLocalDirty -> {
                // Local is dirty (has pending sync op) -> Preserve local, let push handle it
                Log.d(TAG, "Reconcile[EXPENSE:${remote.id}]: SKIP (local dirty, L=$localUpdatedAt, R=$remoteUpdatedAt)")
                return ReconcileAction.SKIP
            }
            remoteUpdatedAt > localUpdatedAt -> {
                // Remote is newer -> Overwrite local
                val expense = mapRemoteToLocalExpense(remote, remoteUpdatedAt)
                val splits = remoteSplits.map { mapRemoteToLocalSplit(it) }
                expenseDao.updateExpenseWithSplits(remote.id, expense, splits)
                Log.d(TAG, "Reconcile[EXPENSE:${remote.id}]: UPDATE (R=$remoteUpdatedAt > L=$localUpdatedAt)")
                return ReconcileAction.UPDATE
            }
            else -> {
                // Local is newer but not dirty (shouldn't happen often, but safe to skip)
                Log.d(TAG, "Reconcile[EXPENSE:${remote.id}]: SKIP (L=$localUpdatedAt > R=$remoteUpdatedAt)")
                return ReconcileAction.SKIP
            }
        }
    }

    private suspend fun reconcileGroup(remote: RemoteGroup): ReconcileAction {
        val local = groupDao.getGroupById(remote.id)
        val remoteUpdatedAt = parseIso8601ToEpochMillis(remote.updated_at)

        if (remote.deleted_at != null) {
            if (local != null) {
                groupDao.deleteGroup(remote.id)
                Log.d(TAG, "Reconcile[GROUP:${remote.id}]: DELETE")
                return ReconcileAction.DELETE
            }
            return ReconcileAction.SKIP
        }

        if (local == null) {
            val group = mapRemoteToLocalGroup(remote, remoteUpdatedAt)
            groupDao.insertGroup(group)
            Log.d(TAG, "Reconcile[GROUP:${remote.id}]: INSERT")
            return ReconcileAction.INSERT
        }

        val localUpdatedAt = local.updatedAt
        val isLocalDirty = hasPendingSyncOpForGroup(remote.id)

        when {
            remoteUpdatedAt == localUpdatedAt -> return ReconcileAction.SKIP
            isLocalDirty -> {
                Log.d(TAG, "Reconcile[GROUP:${remote.id}]: SKIP (dirty)")
                return ReconcileAction.SKIP
            }
            remoteUpdatedAt > localUpdatedAt -> {
                val group = mapRemoteToLocalGroup(remote, remoteUpdatedAt)
                groupDao.insertGroup(group)
                Log.d(TAG, "Reconcile[GROUP:${remote.id}]: UPDATE")
                return ReconcileAction.UPDATE
            }
            else -> return ReconcileAction.SKIP
        }
    }

    private suspend fun reconcileSettlement(remote: RemoteSettlement): ReconcileAction {
        val local = settlementDao.getSettlementById(remote.id)
        val remoteUpdatedAt = parseIso8601ToEpochMillis(remote.updated_at)

        if (remote.deleted_at != null) {
            if (local != null) {
                settlementDao.deleteSettlement(remote.id)
                Log.d(TAG, "Reconcile[SETTLEMENT:${remote.id}]: DELETE")
                return ReconcileAction.DELETE
            }
            return ReconcileAction.SKIP
        }

        if (local == null) {
            val settlement = mapRemoteToLocalSettlement(remote, remoteUpdatedAt)
            settlementDao.insertSettlement(settlement)
            Log.d(TAG, "Reconcile[SETTLEMENT:${remote.id}]: INSERT")
            return ReconcileAction.INSERT
        }

        val localUpdatedAt = local.updatedAt
        val isLocalDirty = hasPendingSyncOpForSettlement(remote.id)

        when {
            remoteUpdatedAt == localUpdatedAt -> return ReconcileAction.SKIP
            isLocalDirty -> {
                Log.d(TAG, "Reconcile[SETTLEMENT:${remote.id}]: SKIP (dirty)")
                return ReconcileAction.SKIP
            }
            remoteUpdatedAt > localUpdatedAt -> {
                val settlement = mapRemoteToLocalSettlement(remote, remoteUpdatedAt)
                settlementDao.insertSettlement(settlement)
                Log.d(TAG, "Reconcile[SETTLEMENT:${remote.id}]: UPDATE")
                return ReconcileAction.UPDATE
            }
            else -> return ReconcileAction.SKIP
        }
    }

    // --- Dirty Check Helpers ---

    private suspend fun hasPendingSyncOp(expenseId: String): Boolean {
        return syncDao.hasPendingOperationForEntity(expenseId)
    }

    private suspend fun hasPendingSyncOpForGroup(groupId: String): Boolean {
        return syncDao.hasPendingOperationForEntity(groupId)
    }

    private suspend fun hasPendingSyncOpForSettlement(settlementId: String): Boolean {
        return syncDao.hasPendingOperationForEntity(settlementId)
    }

    // --- Mapping Helpers ---

    private fun mapRemoteToLocalExpense(remote: RemoteExpense, updatedAtMillis: Long): Expense {
        return Expense(
            id = remote.id,
            groupId = remote.group_id,
            title = remote.title,
            amount = BigDecimal(remote.amount),
            currency = remote.currency,
            date = parseIso8601ToDate(remote.date),
            payerId = remote.payer_id,
            createdBy = remote.created_by,
            syncStatus = "SYNCED",
            expenseDate = remote.expense_date ?: System.currentTimeMillis(),
            createdByUserId = remote.created_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            lastModifiedByUserId = remote.last_modified_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            updatedAt = updatedAtMillis,
            deletedAt = remote.deleted_at?.let { parseIso8601ToEpochMillis(it) }
        )
    }

    private fun mapRemoteToLocalSplit(remote: RemoteExpenseSplit): ExpenseSplit {
        return ExpenseSplit(
            expenseId = remote.expense_id,
            userId = remote.user_id,
            amount = BigDecimal(remote.amount)
        )
    }

    private fun mapRemoteToLocalGroup(remote: RemoteGroup, updatedAtMillis: Long): Group {
        return Group(
            id = remote.id,
            name = remote.name,
            type = remote.type,
            coverUrl = remote.cover_url,
            createdBy = remote.created_by,
            hasTripDates = remote.has_trip_dates ?: false,
            tripStartDate = remote.trip_start_date,
            tripEndDate = remote.trip_end_date,
            createdByUserId = remote.created_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            lastModifiedByUserId = remote.last_modified_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            updatedAt = updatedAtMillis,
            deletedAt = remote.deleted_at?.let { parseIso8601ToEpochMillis(it) }
        )
    }

    private fun mapRemoteToLocalSettlement(remote: RemoteSettlement, updatedAtMillis: Long): Settlement {
        return Settlement(
            id = remote.id,
            groupId = remote.group_id,
            fromUserId = remote.from_user_id,
            toUserId = remote.to_user_id,
            amount = BigDecimal(remote.amount),
            date = parseIso8601ToDate(remote.date),
            createdByUserId = remote.created_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            lastModifiedByUserId = remote.last_modified_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            updatedAt = updatedAtMillis,
            deletedAt = remote.deleted_at?.let { parseIso8601ToEpochMillis(it) }
        )
    }

    // --- Time Parsing Helpers ---

    private fun parseIso8601ToEpochMillis(iso8601: String): Long {
        return try {
            Instant.parse(iso8601).toEpochMilli()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ISO-8601 timestamp: '$iso8601' (${e.javaClass.simpleName}: ${e.message}). Using epoch (0) as fallback.", e)
            0L
        }
    }

    private fun parseIso8601ToDate(iso8601: String): Date {
        return try {
            Date.from(Instant.parse(iso8601))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ISO-8601 date: '$iso8601' (${e.javaClass.simpleName}: ${e.message}). Using current time as fallback.", e)
            Date()
        }
    }
}
