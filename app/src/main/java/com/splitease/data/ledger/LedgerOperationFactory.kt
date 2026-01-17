package com.splitease.data.ledger

import com.google.gson.Gson
import com.splitease.data.device.InstallationIdProvider
import com.splitease.data.ledger.model.ExpenseSnapshot
import com.splitease.data.ledger.model.ExpenseSplitSnapshot
import com.splitease.data.ledger.model.GroupMemberSnapshot
import com.splitease.data.ledger.model.GroupSnapshot
import com.splitease.data.ledger.model.MemberSnapshot
import com.splitease.data.ledger.model.SettlementSnapshot
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.local.entities.Settlement
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating [LedgerOperation] instances deterministically.
 *
 * **Responsibility**: Converts entities + action into a LedgerOperation DTO.
 * **Note**: Does NOT assign [LedgerOperation.logicalClock]. That is handled
 * atomically by the database transaction helper.
 *
 * All payloads are canonical, versioned snapshots.
 */
interface LedgerOperationFactory {

    fun createExpenseCreateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    fun createExpenseUpdateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    fun createExpenseDeleteOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    fun createGroupCreateOp(
        group: Group,
        members: List<GroupMember>,
        authorUserId: String
    ): LedgerOperation

    fun createMemberRemoveOp(
        groupId: String,
        userId: String,
        authorUserId: String
    ): LedgerOperation

    fun createSettlementCreateOp(
        settlement: Settlement,
        authorUserId: String
    ): LedgerOperation
}

@Singleton
class LedgerOperationFactoryImpl @Inject constructor(
    private val gson: Gson,
    private val installationIdProvider: InstallationIdProvider
) : LedgerOperationFactory {

    private fun generateOperationId(): String = UUID.randomUUID().toString()
    private fun deviceId(): String = installationIdProvider.getDeviceId()
    private fun now(): Long = System.currentTimeMillis()

    private fun BigDecimal.toCanonicalString(): String =
        this.setScale(2, RoundingMode.HALF_UP).toPlainString()

    override fun createExpenseCreateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation {
        val snapshot = ExpenseSnapshot(
            id = expense.id,
            groupId = expense.groupId,
            title = expense.title,
            amount = expense.amount.toCanonicalString(),
            currency = expense.currency,
            payerId = expense.payerId,
            createdBy = expense.createdBy,
            syncStatus = expense.syncStatus,
            date = expense.date.time,
            expenseDate = expense.expenseDate,
            createdByUserId = expense.createdByUserId,
            lastModifiedByUserId = expense.lastModifiedByUserId,
            updatedAt = expense.updatedAt,
            deletedAt = expense.deletedAt,
            splits = splits.map { ExpenseSplitSnapshot(it.expenseId, it.userId, it.amount.toCanonicalString()) }
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = ENTITY_EXPENSE,
            entityId = expense.id,
            operationType = OP_CREATE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L, // Placeholder; assigned atomically at commit
            createdAt = now()
        )
    }

    override fun createExpenseUpdateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation {
        val snapshot = ExpenseSnapshot(
            id = expense.id,
            groupId = expense.groupId,
            title = expense.title,
            amount = expense.amount.toCanonicalString(),
            currency = expense.currency,
            payerId = expense.payerId,
            createdBy = expense.createdBy,
            syncStatus = expense.syncStatus,
            date = expense.date.time,
            expenseDate = expense.expenseDate,
            createdByUserId = expense.createdByUserId,
            lastModifiedByUserId = expense.lastModifiedByUserId,
            updatedAt = expense.updatedAt,
            deletedAt = expense.deletedAt,
            splits = splits.map { ExpenseSplitSnapshot(it.expenseId, it.userId, it.amount.toCanonicalString()) }
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = ENTITY_EXPENSE,
            entityId = expense.id,
            operationType = OP_UPDATE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }

    override fun createExpenseDeleteOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation {
        // DELETE still carries full snapshot for replay reversibility
        val snapshot = ExpenseSnapshot(
            id = expense.id,
            groupId = expense.groupId,
            title = expense.title,
            amount = expense.amount.toCanonicalString(),
            currency = expense.currency,
            payerId = expense.payerId,
            createdBy = expense.createdBy,
            syncStatus = expense.syncStatus,
            date = expense.date.time,
            expenseDate = expense.expenseDate,
            createdByUserId = expense.createdByUserId,
            lastModifiedByUserId = expense.lastModifiedByUserId,
            updatedAt = expense.updatedAt,
            deletedAt = expense.deletedAt ?: now(),
            splits = splits.map { ExpenseSplitSnapshot(it.expenseId, it.userId, it.amount.toCanonicalString()) }
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = ENTITY_EXPENSE,
            entityId = expense.id,
            operationType = OP_DELETE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }

    override fun createGroupCreateOp(
        group: Group,
        members: List<GroupMember>,
        authorUserId: String
    ): LedgerOperation {
        val snapshot = GroupSnapshot(
            id = group.id,
            name = group.name,
            type = group.type,
            coverUrl = group.coverUrl,
            createdBy = group.createdBy,
            hasTripDates = group.hasTripDates,
            tripStartDate = group.tripStartDate,
            tripEndDate = group.tripEndDate,
            createdByUserId = group.createdByUserId,
            lastModifiedByUserId = group.lastModifiedByUserId,
            updatedAt = group.updatedAt,
            deletedAt = group.deletedAt,
            members = members.sortedBy { it.userId }.map {
                GroupMemberSnapshot(it.groupId, it.userId, it.joinedAt.time)
            }
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = ENTITY_GROUP,
            entityId = group.id,
            operationType = OP_CREATE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }

    override fun createMemberRemoveOp(
        groupId: String,
        userId: String,
        authorUserId: String
    ): LedgerOperation {
        val snapshot = MemberSnapshot(
            groupId = groupId,
            userId = userId,
            joinedAt = null,
            removedAt = now()
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = ENTITY_MEMBER,
            entityId = "$groupId:$userId", // Composite key
            operationType = OP_DELETE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }

    override fun createSettlementCreateOp(
        settlement: Settlement,
        authorUserId: String
    ): LedgerOperation {
        val snapshot = SettlementSnapshot(
            id = settlement.id,
            groupId = settlement.groupId,
            fromUserId = settlement.fromUserId,
            toUserId = settlement.toUserId,
            amount = settlement.amount.toCanonicalString(),
            currency = settlement.currency,
            date = settlement.date.time,
            createdByUserId = settlement.createdByUserId,
            lastModifiedByUserId = settlement.lastModifiedByUserId,
            updatedAt = settlement.updatedAt,
            deletedAt = settlement.deletedAt
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = ENTITY_SETTLEMENT,
            entityId = settlement.id,
            operationType = OP_CREATE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }

    companion object {
        const val ENTITY_EXPENSE = "EXPENSE"
        const val ENTITY_GROUP = "GROUP"
        const val ENTITY_SETTLEMENT = "SETTLEMENT"
        const val ENTITY_MEMBER = "MEMBER"

        const val OP_CREATE = "CREATE"
        const val OP_UPDATE = "UPDATE"
        const val OP_DELETE = "DELETE"
    }
}
