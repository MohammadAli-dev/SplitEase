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

    /**
     * Creates a CREATE ledger operation representing the given expense and its splits.
     *
     * @param expense The expense entity to include in the operation payload.
     * @param splits The expense splits associated with the expense; used to build the canonical snapshot payload.
     * @param authorUserId Local user id of the author who performed the operation.
     * @return A LedgerOperation for entity type "EXPENSE" with operation type "CREATE". The operation contains a canonical, versioned JSON snapshot of the expense and splits as its payload, a generated operationId, deviceId from the installation provider, `logicalClock` set to 0, and `createdAt` set to the current timestamp.
     */
    fun createExpenseCreateOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    /**
     * Create a LedgerOperation that records an update to an existing expense.
     *
     * The returned operation contains a canonical, versioned JSON payload of the expense (including the provided splits),
     * uses `ENTITY_EXPENSE` and operation type `OP_UPDATE`, sets `logicalClock` to 0 (assigned at commit), sets `deviceId`
     * from the installation provider, and timestamps `createdAt` with the current time.
     *
     * @param expense The expense entity to capture in the operation payload.
     * @param splits The expense splits to include in the payload.
     * @param authorUserId The local user id of the author creating this operation.
     * @return A LedgerOperation for updating the specified expense; its `payload` is the JSON ExpenseSnapshot, `entityId`
     *         is `expense.id`, `operationType` is `"UPDATE"`, and `authorLocalUserId` is `authorUserId`.
     */
    fun createExpenseUpdateOp(
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
    fun createExpenseDeleteOp(
        expense: Expense,
        splits: List<ExpenseSplit>,
        authorUserId: String
    ): LedgerOperation

    /**
     * Create a ledger operation representing the creation of a group.
     *
     * @param group The group entity to capture in the operation payload.
     * @param members The group's members; the payload uses a canonical snapshot where members are deterministically ordered by `userId`.
     * @param authorUserId The local user id of the operation author.
     * @return A `LedgerOperation` with operation type "CREATE" targeting the group's id. The operation's payload is a canonical, versioned JSON snapshot of the group (including the deterministically ordered members), `deviceId` is obtained from the installation provider, `logicalClock` is set to 0, and `createdAt` is the current system time.
     */
    fun createGroupCreateOp(
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
    fun createMemberRemoveOp(
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

    /**
 * Create a new unique identifier for a ledger operation.
 *
 * @return A UUID string to use as the operation identifier.
 */
private fun generateOperationId(): String = UUID.randomUUID().toString()
    /**
 * Retrieve the current installation's device identifier.
 *
 * @return The device identifier string.
 */
private fun deviceId(): String = installationIdProvider.getDeviceId()
    /**
 * Gets the current wall-clock time in milliseconds since the Unix epoch.
 *
 * @return Current time in milliseconds since January 1, 1970 UTC.
 */
private fun now(): Long = System.currentTimeMillis()

    /**
         * Produces a canonical plain-string representation of this BigDecimal with exactly two decimal places.
         *
         * The value is rounded to two decimal places using HALF_UP and formatted without scientific notation.
         *
         * @receiver The BigDecimal to canonicalize.
         * @return A string with exactly two decimal places, rounded HALF_UP, and no exponential notation.
         */
        private fun BigDecimal.toCanonicalString(): String =
        this.setScale(2, RoundingMode.HALF_UP).toPlainString()

    /**
     * Create a ledger operation that records the creation of an expense as a canonical, versioned JSON snapshot.
     *
     * @param expense The expense entity to serialize into the snapshot.
     * @param splits The expense splits to include in the snapshot; split amounts are canonicalized to two decimals.
     * @param authorUserId The local user id of the operation author.
     * @return A `LedgerOperation` with `operationType` set to `CREATE`, `entityType` `EXPENSE`, `entityId` equal to the expense id, a JSON `payload` containing the `ExpenseSnapshot`, `logicalClock` set to `0L` (placeholder), and `createdAt` set to the current time.
     */
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

    /**
     * Create a ledger operation representing an update to an expense.
     *
     * The resulting operation's payload contains a canonical ExpenseSnapshot serialized to JSON.
     *
     * @param expense The expense entity to snapshot for the update.
     * @param splits The expense splits to include in the snapshot.
     * @param authorUserId The local user id of the author creating the operation.
     * @return A LedgerOperation for the expense update whose payload is the serialized ExpenseSnapshot.
     */
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

    /**
     * Create a ledger operation representing deletion of an expense that carries a full, canonical snapshot.
     *
     * The produced operation's payload is a JSON-serialized ExpenseSnapshot that includes the expense data and its splits.
     * The snapshot's `deletedAt` is set to the expense's `deletedAt` if present, otherwise to the current time.
     *
     * @param expense The expense to delete; its full snapshot will be embedded in the operation payload.
     * @param splits The expense's splits to include in the snapshot; amounts are converted to canonical string form.
     * @param authorUserId Local user id of the actor creating the delete operation.
     * @return A LedgerOperation with operationType `DELETE`, entityType `EXPENSE`, and a payload containing the ExpenseSnapshot JSON.
     */
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

    /**
     * Create a LedgerOperation representing the creation of a group.
     *
     * The operation's payload is a JSON-serialized GroupSnapshot containing the group's fields
     * and the provided members (members are included in the snapshot sorted by `userId`).
     *
     * @param group The source Group whose snapshot will be embedded in the operation payload.
     * @param members The group's members to include in the snapshot; they will be sorted by `userId`.
     * @param authorUserId Local user id of the author of this operation.
     * @return A LedgerOperation for creating the given group with a JSON payload of the canonical GroupSnapshot.
     */
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

    /**
     * Creates a LedgerOperation that records removal of a user from a group.
     *
     * @param groupId The identifier of the group the member is removed from.
     * @param userId The identifier of the user being removed.
     * @param authorUserId The local user id of the operation author.
     * @return A LedgerOperation representing the member removal. The operation's payload is a JSON-serialized
     * MemberSnapshot with `removedAt` set to the current time. The operation uses the composite entityId
     * "<groupId>:<userId>", entityType `MEMBER`, operationType `DELETE`, logicalClock `0`, and has `createdAt`
     * set to the current time.
     */
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

    /**
     * Creates a ledger operation representing the creation of a settlement.
     *
     * Builds a canonical SettlementSnapshot (amount scaled to two decimals as a plain string) serialized to JSON and returns a LedgerOperation carrying that payload.
     *
     * @param settlement The settlement to snapshot into the operation payload.
     * @param authorUserId The local user id of the operation author.
     * @return A LedgerOperation for creating the settlement whose payload is the JSON-serialized SettlementSnapshot; `logicalClock` is set to 0 and `createdAt` is the current time.
     */
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