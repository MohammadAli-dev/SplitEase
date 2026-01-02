package com.splitease.data.sync

import com.google.gson.Gson
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.SyncOperation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for creating SyncOperation objects.
 * Does NOT perform database writes or network calls.
 */
interface SyncWriteService {
    /**
     * Creates a SyncOperation for a new expense.
     * Caller is responsible for persisting within a transaction.
     */
    fun createExpenseSyncOp(expense: Expense, splits: List<ExpenseSplit>): SyncOperation

    fun createUpdateExpenseSyncOp(expense: Expense, splits: List<ExpenseSplit>): SyncOperation

    fun createDeleteExpenseSyncOp(expenseId: String): SyncOperation

    /**
     * Creates a SyncOperation for a new group.
     * Caller is responsible for persisting within a transaction.
     */
    fun createGroupCreateSyncOp(group: Group, members: List<GroupMember>): SyncOperation
}

@Singleton
class SyncWriteServiceImpl @Inject constructor(
    private val gson: Gson
) : SyncWriteService {

    override fun createExpenseSyncOp(expense: Expense, splits: List<ExpenseSplit>): SyncOperation {
        val payload = ExpenseCreatePayload(
            version = 1,
            expense = expense,
            splits = splits
        )
        return SyncOperation(
            operationType = SyncOperationType.CREATE.name,
            entityType = SyncEntityType.EXPENSE.name,
            entityId = expense.id,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }

    override fun createUpdateExpenseSyncOp(expense: Expense, splits: List<ExpenseSplit>): SyncOperation {
        val payload = ExpenseCreatePayload(
            version = 1,
            expense = expense,
            splits = splits
        )
        return SyncOperation(
            operationType = SyncOperationType.UPDATE.name,
            entityType = SyncEntityType.EXPENSE.name,
            entityId = expense.id,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }

    override fun createDeleteExpenseSyncOp(expenseId: String): SyncOperation {
        return SyncOperation(
            operationType = SyncOperationType.DELETE.name,
            entityType = SyncEntityType.EXPENSE.name,
            entityId = expenseId,
            payload = "", // No payload for delete, ID is enough
            timestamp = System.currentTimeMillis()
        )
    }

    override fun createGroupCreateSyncOp(group: Group, members: List<GroupMember>): SyncOperation {
        val payload = GroupCreatePayload(
            version = 1,
            group = group,
            members = members.sortedBy { it.userId } // Deterministic ordering
        )
        return SyncOperation(
            operationType = SyncOperationType.CREATE.name,
            entityType = SyncEntityType.GROUP.name,
            entityId = group.id,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }
}

/**
 * Versioned payload for group creation sync.
 */
data class GroupCreatePayload(
    val version: Int,
    val group: Group,
    val members: List<GroupMember>
)

