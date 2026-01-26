package com.splitease.data.sync

import com.google.gson.Gson
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.local.entities.SyncEntityType
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

    /**
 * Create a SyncOperation representing creation of the given settlement.
 *
 * @param settlement The settlement entity whose id, groupId, fromUserId, toUserId and amount are used to build the payload.
 * @return A SyncOperation with operationType "CREATE", entityType "SETTLEMENT", entityId set to settlement.id, and a JSON payload containing the settlement's id, groupId, fromUserId, toUserId and amount (version 1).
 */
    fun createSettlementCreateSyncOp(settlement: com.splitease.data.local.entities.Settlement): SyncOperation

    /**
 * Creates a SyncOperation that represents an intent to remove a member from a group.
 *
 * This operation is intent-based and does not perform a state replacement; the caller must persist the returned SyncOperation within a transaction.
 *
 * @return A SyncOperation representing the intent to remove the specified `userId` from the specified `groupId`.
 */
    fun createGroupMemberRemoveSyncOp(groupId: String, userId: String): SyncOperation

    /**
     * Creates a SyncOperation that represents an intent to add a member to a group.
     */
    fun createGroupMemberAddSyncOp(groupId: String, userId: String): SyncOperation
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
            entityType = SyncEntityType.EXPENSE,
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
            entityType = SyncEntityType.EXPENSE,
            entityId = expense.id,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }

    override fun createDeleteExpenseSyncOp(expenseId: String): SyncOperation {
        return SyncOperation(
            operationType = SyncOperationType.DELETE.name,
            entityType = SyncEntityType.EXPENSE,
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
            entityType = SyncEntityType.GROUP,
            entityId = group.id,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Creates a SyncOperation that represents creating the given settlement.
     *
     * @param settlement The settlement to be represented in the sync operation.
     * @return A SyncOperation configured as a `CREATE` for the settlement, with a JSON payload containing the settlement's id, groupId, fromUserId, toUserId, and amount.
     */
    override fun createSettlementCreateSyncOp(settlement: com.splitease.data.local.entities.Settlement): SyncOperation {
        val payload = SettlementCreatePayload(
            version = 1,
            id = settlement.id,
            groupId = settlement.groupId,
            fromUserId = settlement.fromUserId,
            toUserId = settlement.toUserId,
            amount = settlement.amount
        )
        return SyncOperation(
            operationType = SyncOperationType.CREATE.name,
            entityType = SyncEntityType.SETTLEMENT,
            entityId = settlement.id,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Creates a sync operation that records an intent to remove a member from a group.
     *
     * @param groupId The identifier of the group from which the member will be removed.
     * @param userId The identifier of the member to remove.
     * @return The SyncOperation representing a `REMOVE_MEMBER` intent for the group; its payload contains the `groupId` and `userId`.
     */
    override fun createGroupMemberRemoveSyncOp(groupId: String, userId: String): SyncOperation {
        val payload = GroupMemberRemovePayload(
            version = 1,
            groupId = groupId,
            userId = userId
        )
        return SyncOperation(
            operationType = SyncOperationType.REMOVE_MEMBER.name,
            entityType = SyncEntityType.GROUP,
            entityId = groupId, // Entity is the group, intent is to remove this member
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }

    override fun createGroupMemberAddSyncOp(groupId: String, userId: String): SyncOperation {
        val payload = GroupMemberAddPayload(
            version = 1,
            groupId = groupId,
            userId = userId
        )
        return SyncOperation(
            operationType = SyncOperationType.ADD_MEMBER.name,
            entityType = SyncEntityType.GROUP,
            entityId = groupId,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis()
        )
    }
}

/**
 * Versioned payload for expense creation sync.
 */
data class ExpenseCreatePayload(
    val version: Int = 1,
    val expense: Expense,
    val splits: List<ExpenseSplit>
)

/**
 * Versioned payload for group creation sync.
 */
data class GroupCreatePayload(
    val version: Int,
    val group: Group,
    val members: List<GroupMember>
)

/**
 * Versioned payload for settlement creation sync.
 * Includes groupId and ID for backend reconciliation.
 */
data class SettlementCreatePayload(
    val version: Int,
    val id: String,
    val groupId: String,
    val fromUserId: String,
    val toUserId: String,
    val amount: java.math.BigDecimal
)

/**
 * Versioned payload for group member removal sync.
 * This is an explicit intent, not a state replacement.
 */
data class GroupMemberRemovePayload(
    val version: Int,
    val groupId: String,
    val userId: String
)

/**
 * Versioned payload for group member addition sync.
 */
data class GroupMemberAddPayload(
    val version: Int,
    val groupId: String,
    val userId: String
)