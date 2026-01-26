package com.splitease.data.ledger

import com.google.gson.Gson
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.device.InstallationIdProvider
import com.splitease.data.device.WritePermissionDeniedException
import com.splitease.data.ledger.model.ExpenseSnapshot
import com.splitease.data.ledger.model.ExpenseSplitSnapshot
import com.splitease.data.ledger.model.GroupMemberSnapshot
import com.splitease.data.ledger.model.GroupSnapshot
import com.splitease.data.ledger.model.MemberSnapshot
import com.splitease.data.ledger.model.SettlementSnapshot
import com.splitease.data.ledger.model.UserSnapshot
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.LedgerOperation
import com.splitease.data.local.entities.Settlement
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_EXPENSE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_GROUP
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_MEMBER
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_SETTLEMENT
import com.splitease.data.ledger.LedgerOperationFactory.Companion.ENTITY_USER
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_CREATE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_DELETE
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_UPDATE
import com.splitease.data.resolution.ConflictResolutionPayload
import com.splitease.data.resolution.ResolutionType
import com.splitease.data.conflict.LedgerOpRef
import com.splitease.data.ledger.LedgerOperationFactory.Companion.OP_RESOLVE_CONFLICT
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

    /**
     * Creates a CREATE ledger operation representing the given expense and its splits.
     *
     * @param expense The expense entity to include in the operation payload.
     * @param splits The expense splits associated with the expense; used to build the canonical snapshot payload.
     * @param authorUserId Local user id of the author who performed the operation.
     * @return A LedgerOperation for entity type "EXPENSE" with operation type "CREATE". The operation contains a canonical, versioned JSON snapshot of the expense and splits as its payload, a generated operationId, deviceId from the installation provider, `logicalClock` set to 0, and `createdAt` set to the current timestamp.
     */
    suspend fun createExpenseCreateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    /**
     * Builds a LedgerOperation representing an update to the given expense with a canonical JSON snapshot that includes the provided splits.
     *
     * @param expense The expense to include in the operation payload.
     * @param splits Expense splits to include in the snapshot.
     * @param authorUserId Local user id of the operation author.
     * @return A LedgerOperation whose payload is the JSON ExpenseSnapshot for the expense (including splits), with `entityId` set to the expense id, `operationType` set to "UPDATE", `authorLocalUserId` set to `authorUserId`, `deviceId` from the installation provider, `logicalClock` set to 0, and `createdAt` set to the current time.
     */
    suspend fun createExpenseUpdateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    /**
     * Create a ledger operation representing deletion of the given expense.
     *
     * The resulting operation carries a full `ExpenseSnapshot` payload (for replay/reversibility).
     * If `expense.deletedAt` is null, the snapshot's `deletedAt` is set to the current time.
     *
     * @param expense The expense to be deleted; its fields are captured into the snapshot.
     * @param splits The expense splits to include in the snapshot.
     * @param authorUserId Local user id of the author of this operation.
     * @return A `LedgerOperation` whose payload is the JSON-serialized `ExpenseSnapshot` for the deleted expense.
     */
    suspend fun createExpenseDeleteOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    /**
     * Creates a CREATE operation for the given group with a canonical JSON snapshot.
     *
     * The snapshot includes the group's fields and members deterministically ordered by `userId`.
     *
     * @param group The group to snapshot.
     * @param members The group's members; will be ordered by `userId` in the payload.
     * @param authorUserId The local user id of the operation author.
     * @return A LedgerOperation representing a "CREATE" operation for the group's id whose payload is a canonical, versioned JSON GroupSnapshot.
     */
    suspend fun createGroupCreateOp(
        group: Group,
        members: List<GroupMember>,
        authorUserId: String
    ): LedgerOperation

    /**
     * Create a LedgerOperation that records removal of a user from a group.
     *
     * The returned operation targets the `MEMBER` entity with operation type `DELETE`.
     * The operation's `entityId` is the composite key "groupId:userId". The payload is a `MemberSnapshot`
     * where `joinedAt` is `null` and `removedAt` is set to the current timestamp. The operation's
     * `logicalClock` is left at 0 and `createdAt` is the current time; `authorLocalUserId` is set to
     * the provided `authorUserId` and `deviceId` reflects the current device.
     *
     * @param groupId The id of the group the member is being removed from.
     * @param userId The id of the user being removed.
     * @param authorUserId The local user id performing the removal.
     * @return A `LedgerOperation` representing the member removal. 
     */
    suspend fun createMemberRemoveOp(
        groupId: String,
        userId: String,
        authorUserId: String
    ): LedgerOperation

    /**
     * Create a LedgerOperation that records adding a user to a group.
     * 
     * @param groupId The id of the group the member is being added to.
     * @param userId The id of the user being added.
     * @param authorUserId The local user id performing the addition.
     * @return A LedgerOperation for entity type "MEMBER" with operation type "CREATE".
     */
    suspend fun createMemberAddOp(
        groupId: String,
        userId: String,
        authorUserId: String
    ): LedgerOperation

    /**
     * Creates a ledger operation representing the creation of a settlement.
     *
     * Builds a canonical, versioned SettlementSnapshot from the provided settlement (amount canonicalized to two decimals),
     * serializes it to JSON as the operation payload, and returns a LedgerOperation with entityType "SETTLEMENT" and operationType "CREATE".
     *
     * @param settlement The domain Settlement to snapshot into the operation payload.
     * @param authorUserId Local user id of the author of this operation.
     * @return A LedgerOperation whose entityId is the settlement's id, whose payload is the JSON SettlementSnapshot, whose authorLocalUserId is `authorUserId`, whose deviceId is obtained from the installation provider, whose logicalClock is 0, and whose createdAt is the current system time in milliseconds.
     */
    suspend fun createSettlementCreateOp(
        settlement: Settlement,
        authorUserId: String
    ): LedgerOperation

    /**
     * Creates a RESOLVE_CONFLICT operation payload.
     *
     * @param conflictId The ID of the conflict being resolved.
     * @param resolutionType The type of resolution (e.g. KEEP_OPERATION).
     * @param chosenOpRef The operation reference chosen by the user.
     * @param authorUserId The local user ID performing the resolution.
     * @return A LedgerOperation containing the serialized [ConflictResolutionPayload].
     */
    suspend fun createResolutionOp(
        conflictId: String,
        resolutionType: ResolutionType,
        chosenOpRef: LedgerOpRef,
        authorUserId: String
    ): LedgerOperation

    /**
     * Creates a ledger operation for creating a User (Phantom User).
     */
    suspend fun createUserCreateOp(
        user: com.splitease.data.local.entities.User,
        authorUserId: String
    ): LedgerOperation

    companion object {
        const val ENTITY_EXPENSE = "EXPENSE"
        const val ENTITY_GROUP = "GROUP"
        const val ENTITY_SETTLEMENT = "SETTLEMENT"
        const val ENTITY_MEMBER = "MEMBER"
        const val ENTITY_USER = "USER"


        const val OP_CREATE = "CREATE"
        const val OP_UPDATE = "UPDATE"
        const val OP_DELETE = "DELETE"
        /** Sprint 21: Explicit conflict resolution operation type. */
        const val OP_RESOLVE_CONFLICT = "RESOLVE_CONFLICT"
    }


}

@Singleton
class LedgerOperationFactoryImpl @Inject constructor(
    private val gson: Gson,
    private val installationIdProvider: InstallationIdProvider,
    private val deviceRoleManager: DeviceRoleManager
) : LedgerOperationFactory {

    /**
     * Create a new unique identifier for a ledger operation.
     */
    private fun generateOperationId(): String = UUID.randomUUID().toString()
    
    /**
     * Retrieve the current installation's device identifier.
     */
    private fun deviceId(): String = installationIdProvider.getDeviceId()
    
    /**
     * Gets the current wall-clock time in milliseconds since the Unix epoch.
     */
    private fun now(): Long = System.currentTimeMillis()

    /**
     * Produces a canonical plain-string representation of this BigDecimal with exactly two decimal places.
     */
    private fun BigDecimal.toCanonicalString(): String =
        this.setScale(2, RoundingMode.HALF_UP).toPlainString()

    /**
     * Guard to enforce write permissions at the factory level.
     * Throws [WritePermissionDeniedException] if the device role does not permit writes.
     */
    private suspend fun ensureNotReadOnly() {
        if (!deviceRoleManager.canWrite()) {
            throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
        }
    }
    override suspend fun createExpenseCreateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
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

    override suspend fun createExpenseUpdateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
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

    override suspend fun createExpenseDeleteOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
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

    override suspend fun createGroupCreateOp(
        group: Group,
        members: List<GroupMember>,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
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

    override suspend fun createMemberRemoveOp(
        groupId: String,
        userId: String,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
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

    override suspend fun createMemberAddOp(
        groupId: String,
        userId: String,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
        val snapshot = MemberSnapshot(
            groupId = groupId,
            userId = userId,
            joinedAt = now(),
            removedAt = null
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = ENTITY_MEMBER,
            entityId = "$groupId:$userId", // Composite key
            operationType = OP_CREATE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }

    override suspend fun createSettlementCreateOp(
        settlement: Settlement,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
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

    override suspend fun createResolutionOp(
        conflictId: String,
        resolutionType: ResolutionType,
        chosenOpRef: LedgerOpRef,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
        val payload = ConflictResolutionPayload(
            conflictId = conflictId,
            resolutionType = resolutionType,
            chosenOpRef = chosenOpRef
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = OP_RESOLVE_CONFLICT,
            entityId = conflictId,
            operationType = OP_RESOLVE_CONFLICT,
            payload = gson.toJson(payload),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }
    override suspend fun createUserCreateOp(
        user: com.splitease.data.local.entities.User,
        authorUserId: String
    ): LedgerOperation {
        ensureNotReadOnly()
        val snapshot = UserSnapshot(
            id = user.id,
            name = user.name,
            email = user.email,
            phone = user.phone,
            profileUrl = user.profileUrl
        )
        return LedgerOperation(
            operationId = generateOperationId(),
            entityType = LedgerOperationFactory.ENTITY_USER,
            entityId = user.id,
            operationType = OP_CREATE,
            payload = gson.toJson(snapshot),
            authorLocalUserId = authorUserId,
            deviceId = deviceId(),
            logicalClock = 0L,
            createdAt = now()
        )
    }
}