package com.splitease.data.sync

import android.util.Log
import com.splitease.data.auth.AuthConfig
import com.splitease.data.auth.TokenManager
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.Settlement
import com.splitease.data.local.entities.SyncEntityType
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
        val groupsDeleted: Int,
        val settlementsInserted: Int,
        val settlementsUpdated: Int,
        val settlementsDeleted: Int,
        val newCursor: String? = null // New cursor value (if changed)
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
    private val transactionRunner: TransactionRunner
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

            // ════════════════════════════════════════════════════════════════
            // PHASE 1: FETCH ALL REMOTE DATA (outside transaction)
            // ════════════════════════════════════════════════════════════════
            // All network operations occur BEFORE the transaction starts.
            // This ensures the transaction contains ONLY local DB operations.
            
            val groups = fetchAllPagesOfGroups(authHeader, apiKey, lastSyncedAt)
            val expenses = fetchAllPagesOfExpenses(authHeader, apiKey, lastSyncedAt)
            val expenseIds = expenses.map { it.id }
            val remoteSplits = if (expenseIds.isNotEmpty()) {
                fetchAllPagesOfSplits(authHeader, apiKey, expenseIds)
            } else {
                emptyList()
            }
            val settlements = fetchAllPagesOfSettlements(authHeader, apiKey, lastSyncedAt)
            
            val splitsByExpenseId = remoteSplits.groupBy { it.expense_id }
            
            Log.d(TAG, "Fetched: ${groups.size} groups, ${expenses.size} expenses, ${remoteSplits.size} splits, ${settlements.size} settlements")

            // ════════════════════════════════════════════════════════════════
            // PHASE 2: ATOMIC RECONCILIATION (all-or-nothing)
            // ════════════════════════════════════════════════════════════════
            // Any exception inside this block rolls back ALL changes.
            // Partial application is STRICTLY FORBIDDEN.
            
            val result = transactionRunner.run {
                var expensesInserted = 0
                var expensesUpdated = 0
                var expensesDeleted = 0
                var groupsInserted = 0
                var groupsUpdated = 0
                var groupsDeleted = 0
                var settlementsInserted = 0
                var settlementsUpdated = 0
                var settlementsDeleted = 0
                var maxRemoteTimestamp = lastSyncedAt

                // Reconcile Groups first (expenses depend on groups)
                for (remoteGroup in groups) {
                    val action = reconcileGroup(remoteGroup)
                    when (action) {
                        ReconcileAction.INSERT -> groupsInserted++
                        ReconcileAction.UPDATE -> groupsUpdated++
                        ReconcileAction.DELETE -> groupsDeleted++
                        ReconcileAction.SKIP -> {}
                    }
                    if (remoteGroup.updated_at > maxRemoteTimestamp) {
                        maxRemoteTimestamp = remoteGroup.updated_at
                    }
                }

                // Reconcile Expenses
                for (remoteExpense in expenses) {
                    val splits = splitsByExpenseId[remoteExpense.id] ?: emptyList()
                    val action = reconcileExpense(remoteExpense, splits)
                    when (action) {
                        ReconcileAction.INSERT -> expensesInserted++
                        ReconcileAction.UPDATE -> expensesUpdated++
                        ReconcileAction.DELETE -> expensesDeleted++
                        ReconcileAction.SKIP -> {}
                    }
                    if (remoteExpense.updated_at > maxRemoteTimestamp) {
                        maxRemoteTimestamp = remoteExpense.updated_at
                    }
                }

                // Reconcile Settlements
                for (remoteSettlement in settlements) {
                    val action = reconcileSettlement(remoteSettlement)
                    when (action) {
                        ReconcileAction.INSERT -> settlementsInserted++
                        ReconcileAction.UPDATE -> settlementsUpdated++
                        ReconcileAction.DELETE -> settlementsDeleted++
                        ReconcileAction.SKIP -> {}
                    }
                    if (remoteSettlement.updated_at > maxRemoteTimestamp) {
                        maxRemoteTimestamp = remoteSettlement.updated_at
                    }
                }

                // Return stats from transaction (cursor update happens OUTSIDE)
                PullSyncResult.Success(
                    expensesInserted = expensesInserted,
                    expensesUpdated = expensesUpdated,
                    expensesDeleted = expensesDeleted,
                    groupsInserted = groupsInserted,
                    groupsUpdated = groupsUpdated,
                    groupsDeleted = groupsDeleted,
                    settlementsInserted = settlementsInserted,
                    settlementsUpdated = settlementsUpdated,
                    settlementsDeleted = settlementsDeleted,
                    newCursor = if (maxRemoteTimestamp > lastSyncedAt) maxRemoteTimestamp else null
                )
            }

            // ════════════════════════════════════════════════════════════════
            // PHASE 3: CURSOR ADVANCE (only after Room transaction commits)
            // ════════════════════════════════════════════════════════════════
            // DataStore is NOT transactional with Room, so cursor update must
            // occur AFTER Room commits. This ensures cursor is never advanced
            // for data that was rolled back.
            //
            // IMPORTANT: Cursor update failures should NOT mask successful data
            // commits. If DataStore fails here, we log a warning and return the
            // successful result. The next sync will re-fetch the same data
            // (idempotent reconciliation handles this gracefully).
            if (result is PullSyncResult.Success && result.newCursor != null) {
                try {
                    syncMetadataStore.setLastSyncedAt(result.newCursor)
                    Log.d(TAG, "Cursor advanced: $lastSyncedAt -> ${result.newCursor}")
                } catch (e: Exception) {
                    // Non-fatal: Data is committed, cursor will advance on next sync
                    Log.w(TAG, "Failed to advance cursor (non-fatal): ${e.message}", e)
                }
            }

            Log.d(TAG, "Pull sync complete: $result")
            result
        } catch (e: Exception) {
            // ✅ Transaction rolled back automatically on any exception
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

    /**
     * Fetches expense splits for a list of expense IDs.
     * 
     * Uses batching to avoid URL length limits (8KB). Each batch contains
     * up to 50 expense IDs (~1.8KB URL), well under the limit.
     * 
     * If any batch fails, the entire operation throws to maintain data integrity.
     * Missing splits would cause incorrect balance calculations.
     */
    private suspend fun fetchAllPagesOfSplits(
        authHeader: String,
        apiKey: String,
        expenseIds: List<String>
    ): List<RemoteExpenseSplit> {
        if (expenseIds.isEmpty()) {
            return emptyList()
        }
        
        // Batch size of 50 keeps URL under ~2KB (36 chars/UUID * 50 = 1800 chars)
        val batchSize = 50
        val allSplits = mutableListOf<RemoteExpenseSplit>()
        
        expenseIds.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            Log.d(TAG, "Fetching splits batch ${batchIndex + 1}/${(expenseIds.size + batchSize - 1) / batchSize} (${batch.size} expense IDs)")
            
            val filter = "in.(${batch.joinToString(",")})"
            
            val batchSplits = fetchAllPages("ExpenseSplit[batch=$batchIndex]") { range ->
                api.getExpenseSplits(
                    authHeader = authHeader,
                    apiKey = apiKey,
                    expenseIdFilter = filter,
                    rangeHeader = range
                )
            }
            
            allSplits.addAll(batchSplits)
        }
        
        Log.d(TAG, "Fetched ${allSplits.size} total splits across ${(expenseIds.size + batchSize - 1) / batchSize} batches")
        return allSplits
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
        if (remoteUpdatedAt == null) {
            Log.e(TAG, "Reconcile[EXPENSE:${remote.id}]: SKIP (invalid updated_at timestamp)")
            return ReconcileAction.SKIP
        }

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

        // Map remote expense safely
        val expense = mapRemoteToLocalExpense(remote, remoteUpdatedAt)
        if (expense == null) {
            Log.e(TAG, "Reconcile[EXPENSE:${remote.id}]: SKIP (mapper returned null - malformed data)")
            return ReconcileAction.SKIP
        }
        // Map splits safely, dropping malformed ones
        val splits = remoteSplits.mapNotNull { mapRemoteToLocalSplit(it) }

        if (local == null) {
            // INSERT: Remote exists, local missing
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
        if (remoteUpdatedAt == null) {
            Log.e(TAG, "Reconcile[GROUP:${remote.id}]: SKIP (invalid updated_at timestamp)")
            return ReconcileAction.SKIP
        }

        if (remote.deleted_at != null) {
            if (local != null) {
                groupDao.deleteGroup(remote.id)
                Log.d(TAG, "Reconcile[GROUP:${remote.id}]: DELETE")
                return ReconcileAction.DELETE
            }
            return ReconcileAction.SKIP
        }

        if (local == null) {
            val group = mapRemoteToLocalGroup(remote, remoteUpdatedAt) ?: run {
                Log.e(TAG, "Reconcile[GROUP:${remote.id}]: SKIP (mapper returned null)")
                return ReconcileAction.SKIP
            }
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
                val group = mapRemoteToLocalGroup(remote, remoteUpdatedAt) ?: run {
                    Log.e(TAG, "Reconcile[GROUP:${remote.id}]: SKIP (mapper returned null)")
                    return ReconcileAction.SKIP
                }
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
        if (remoteUpdatedAt == null) {
            Log.e(TAG, "Reconcile[SETTLEMENT:${remote.id}]: SKIP (invalid updated_at timestamp)")
            return ReconcileAction.SKIP
        }

        if (remote.deleted_at != null) {
            if (local != null) {
                settlementDao.deleteSettlement(remote.id)
                Log.d(TAG, "Reconcile[SETTLEMENT:${remote.id}]: DELETE")
                return ReconcileAction.DELETE
            }
            return ReconcileAction.SKIP
        }

        if (local == null) {
            val settlement = mapRemoteToLocalSettlement(remote, remoteUpdatedAt) ?: run {
                Log.e(TAG, "Reconcile[SETTLEMENT:${remote.id}]: SKIP (mapper returned null)")
                return ReconcileAction.SKIP
            }
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
                val settlement = mapRemoteToLocalSettlement(remote, remoteUpdatedAt) ?: run {
                    Log.e(TAG, "Reconcile[SETTLEMENT:${remote.id}]: SKIP (mapper returned null)")
                    return ReconcileAction.SKIP
                }
                settlementDao.insertSettlement(settlement)
                Log.d(TAG, "Reconcile[SETTLEMENT:${remote.id}]: UPDATE")
                return ReconcileAction.UPDATE
            }
            else -> return ReconcileAction.SKIP
        }
    }

    // --- Dirty Check Helpers ---

    private suspend fun hasPendingSyncOp(expenseId: String): Boolean {
        return syncDao.hasPendingOperationForEntity(expenseId, SyncEntityType.EXPENSE)
    }

    private suspend fun hasPendingSyncOpForGroup(groupId: String): Boolean {
        return syncDao.hasPendingOperationForEntity(groupId, SyncEntityType.GROUP)
    }

    private suspend fun hasPendingSyncOpForSettlement(settlementId: String): Boolean {
        return syncDao.hasPendingOperationForEntity(settlementId, SyncEntityType.SETTLEMENT)
    }

    // --- Mapping Helpers ---

    /**
     * Safely parses a numeric string into BigDecimal.
     * Returns null if the string is malformed and logs the error with context.
     */
    private fun parseAmount(amountStr: String, context: String): BigDecimal? {
        return try {
            BigDecimal(amountStr)
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Invalid amount '$amountStr' in $context: ${e.message}", e)
            null
        }
    }

    private fun mapRemoteToLocalExpense(remote: RemoteExpense, updatedAtMillis: Long): Expense? {
        val amount = parseAmount(remote.amount, "Expense:${remote.id}") ?: return null
        val date = parseIso8601ToDate(remote.date)
        if (date == null) {
            Log.e(TAG, "Skipping expense ${remote.id}: invalid date '${remote.date}'")
            return null
        }
        return Expense(
            id = remote.id,
            groupId = remote.group_id,
            title = remote.title,
            amount = amount,
            currency = remote.currency,
            date = date,
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

    private fun mapRemoteToLocalSplit(remote: RemoteExpenseSplit): ExpenseSplit? {
        val amount = parseAmount(remote.amount, "ExpenseSplit:${remote.expense_id}, user:${remote.user_id}") ?: return null
        return ExpenseSplit(
            expenseId = remote.expense_id,
            userId = remote.user_id,
            amount = amount
        )
    }

    private fun mapRemoteToLocalGroup(remote: RemoteGroup, updatedAtMillis: Long): Group? {
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

    private fun mapRemoteToLocalSettlement(remote: RemoteSettlement, updatedAtMillis: Long): Settlement? {
        val amount = parseAmount(remote.amount, "Settlement:${remote.id}") ?: return null
        val date = parseIso8601ToDate(remote.date)
        if (date == null) {
            Log.e(TAG, "Skipping settlement ${remote.id}: invalid date '${remote.date}'")
            return null
        }
        return Settlement(
            id = remote.id,
            groupId = remote.group_id,
            fromUserId = remote.from_user_id,
            toUserId = remote.to_user_id,
            amount = amount,
            date = date,
            createdByUserId = remote.created_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            lastModifiedByUserId = remote.last_modified_by_user_id ?: IdentityConstants.LEGACY_USER_ID,
            updatedAt = updatedAtMillis,
            deletedAt = remote.deleted_at?.let { parseIso8601ToEpochMillis(it) }
        )
    }

    // --- Time Parsing Helpers ---

    /**
     * Safely parses ISO-8601 timestamp to epoch milliseconds.
     * Returns null if the string is malformed and logs the error.
     */
    private fun parseIso8601ToEpochMillis(iso8601: String): Long? {
        return try {
            Instant.parse(iso8601).toEpochMilli()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ISO-8601 timestamp: '$iso8601'", e)
            null
        }
    }

    /**
     * Safely parses ISO-8601 string to Date.
     * Returns null if the string is malformed and logs the error.
     */
    private fun parseIso8601ToDate(iso8601: String): Date? {
        return try {
            Date.from(Instant.parse(iso8601))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ISO-8601 date: '$iso8601'", e)
            null
        }
    }
}
