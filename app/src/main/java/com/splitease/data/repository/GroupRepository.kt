package com.splitease.data.repository

import android.util.Log
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.sync.SyncWriteService
import com.splitease.domain.BalanceCalculator
import com.splitease.domain.GroupExitValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of attempting to leave a group.
 */
sealed interface LeaveGroupResult {
    data object Success : LeaveGroupResult
    data object BlockedByBalance : LeaveGroupResult
    data object BlockedAsLastMember : LeaveGroupResult
    data object AlreadyRemoved : LeaveGroupResult
    data class Error(val message: String) : LeaveGroupResult
}

/**
 * Result of attempting to remove another member from a group.
 */
sealed interface RemoveMemberResult {
    data object Success : RemoveMemberResult
    data object BlockedByBalance : RemoveMemberResult
    data object BlockedAsLastMember : RemoveMemberResult
    data object TargetAlreadyRemoved : RemoveMemberResult
    data class Error(val message: String) : RemoveMemberResult
}

/**
 * Repository for group operations.
 */
interface GroupRepository {
    /**
     * Create a new group, persist its members, and record a CREATE_GROUP sync intent.
     *
     * This operation is atomic and offline-safe: the group, its members, and the sync operation are persisted together for later backend synchronization.
     *
     * @param name The group's display name.
     * @param type The group type identifier (e.g., "TRIP", "HOME", "COUPLE", "OTHER").
     * @param memberIds The user IDs to add as initial members.
     * @param hasTripDates Whether the group includes trip start/end dates.
     * @param tripStartDate Trip start timestamp in epoch milliseconds, or `null` if not applicable.
     * @param tripEndDate Trip end timestamp in epoch milliseconds, or `null` if not applicable.
     * @param creatorUserId The user ID of the group creator.
     */
    suspend fun createGroup(
        name: String,
        type: String,
        memberIds: List<String>,
        hasTripDates: Boolean = false,
        tripStartDate: Long? = null,
        tripEndDate: Long? = null,
        creatorUserId: String
    )

    /**
     * Remove a user from the specified group (self-removal / "Leave Group").
     *
     * This operation is offline-safe, atomic (removal and sync intent persisted together), idempotent,
     * blocked if the user has a non-zero net balance, and blocked if the user is the last remaining member.
     *
     * @param groupId The ID of the group to leave.
     * @param userId The ID of the user to remove from the group.
     * @return `LeaveGroupResult.Success` on successful removal;
     *         `LeaveGroupResult.AlreadyRemoved` if the user is not a member;
     *         `LeaveGroupResult.BlockedByBalance` if the user's net balance is not zero;
     *         `LeaveGroupResult.BlockedAsLastMember` if the user is the group's last member;
     *         or `LeaveGroupResult.Error(message)` if an error occurs. 
     */
    suspend fun leaveGroup(groupId: String, userId: String): LeaveGroupResult

    /**
     * Remove another member from the specified group (peer removal).
     *
     * This operation follows the same invariants as [leaveGroup]: balance must be zero,
     * group must have more than one member. The actor/target distinction is preserved for
     * logging and future auditability.
     *
     * @param groupId The ID of the group.
     * @param actorUserId The user performing the removal (for logging/auditing).
     * @param targetUserId The user being removed from the group.
     * @return `RemoveMemberResult.Success` on successful removal;
     *         `RemoveMemberResult.TargetAlreadyRemoved` if the target is not a member;
     *         `RemoveMemberResult.BlockedByBalance` if the target's net balance is not zero;
     *         `RemoveMemberResult.BlockedAsLastMember` if the target is the group's last member;
     *         or `RemoveMemberResult.Error(message)` if an error occurs.
     */
    suspend fun removeMember(groupId: String, actorUserId: String, targetUserId: String): RemoveMemberResult
}

@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService,
    private val ledgerOperationFactory: LedgerOperationFactory
) : GroupRepository {

    companion object {
        private const val TAG = "GroupRepository"
    }

    /**
         * Creates a new group with the given metadata and members, persists it in the local database, and records a sync intent for backend synchronization.
         *
         * @param name The group's display name.
         * @param type A string identifying the group's type or category.
         * @param memberIds List of user IDs to be added as group members.
         * @param hasTripDates Whether the group includes trip start/end dates.
         * @param tripStartDate Trip start timestamp in milliseconds since the Unix epoch, or `null` if not set.
         * @param tripEndDate Trip end timestamp in milliseconds since the Unix epoch, or `null` if not set.
         * @param creatorUserId The user ID of the group's creator; recorded as the creator and last modifier.
         */
        override suspend fun createGroup(
        name: String,
        type: String,
        memberIds: List<String>,
        hasTripDates: Boolean,
        tripStartDate: Long?,
        tripEndDate: Long?,
        creatorUserId: String
    ) = withContext(Dispatchers.IO) {
            val groupId = UUID.randomUUID().toString()
            val now = Date()

            val group = Group(
                id = groupId,
                name = name,
                type = type,
                coverUrl = null,
                createdBy = creatorUserId,
                hasTripDates = hasTripDates,
                tripStartDate = tripStartDate,
                tripEndDate = tripEndDate,
                createdByUserId = creatorUserId,
                lastModifiedByUserId = creatorUserId
            )

            val members = memberIds.sortedBy { it }.map { userId ->
                GroupMember(
                    groupId = groupId,
                    userId = userId,
                    joinedAt = now
                )
            }

            val syncOp = syncWriteService.createGroupCreateSyncOp(group, members)
            val ledgerOp = ledgerOperationFactory.createGroupCreateOp(group, members, creatorUserId)

            appDatabase.insertGroupWithMembersAndLedger(group, members, syncOp, ledgerOp)
        }

    /**
     * Attempts to remove a user from a group, enforcing business rules and recording a sync operation.
     *
     * This operation is idempotent and persists changes via the local database while creating a sync intent
     * for backend reconciliation. It will:
     * - Return AlreadyRemoved if the user is not a member.
     * - Prevent removal when the user is the last member of the group.
     * - Prevent removal when the user's net balance for the group is non-zero.
     * - On success, remove the member and record a group-member-remove sync operation in a single transaction.
     * Any unexpected error is captured and returned as LeaveGroupResult.Error with an explanatory message.
     *
     * @return `LeaveGroupResult.Success` on successful removal;
     * `LeaveGroupResult.AlreadyRemoved` if the user was not a member;
     * `LeaveGroupResult.BlockedAsLastMember` if removing would leave the group empty;
     * `LeaveGroupResult.BlockedByBalance` if the user's balance prevents leaving;
     * `LeaveGroupResult.Error(message)` for unexpected failures with a diagnostic message.
     */
    override suspend fun leaveGroup(groupId: String, userId: String): LeaveGroupResult = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch current members (single source of truth for this operation)
            val currentMembers = appDatabase.groupDao().getGroupMembers(groupId).first()
            val memberCount = currentMembers.size
            val isMember = currentMembers.any { it.userId == userId }

            // 2. Check if user is still a member (idempotency)
            if (!isMember) {
                Log.d(TAG, "leaveGroup: AlreadyRemoved [groupId=$groupId, userId=$userId]")
                return@withContext LeaveGroupResult.AlreadyRemoved
            }

            // 3. Structural invariant: Cannot remove the last member (group would be orphaned)
            // This is checked early to avoid expensive balance computation for impossible operations
            if (memberCount <= 1) {
                Log.w(TAG, "leaveGroup: BlockedAsLastMember [groupId=$groupId, userId=$userId, memberCount=$memberCount]")
                return@withContext LeaveGroupResult.BlockedAsLastMember
            }

            // 4. Calculate balances for validation
            val expenses = appDatabase.expenseDao().getExpensesForGroup(groupId).first()
            val splits = appDatabase.expenseDao().getAllExpenseSplitsForGroup(groupId).first()
            val settlements = appDatabase.settlementDao().getSettlementsForGroup(groupId).first()
            val balances = BalanceCalculator.calculate(expenses, splits, settlements)

            // 5. Run domain validation
            val eligibility = GroupExitValidator.checkLeaveEligibility(
                userId = userId,
                balances = balances,
                memberCount = memberCount
            )

            when (eligibility) {
                is GroupExitValidator.LeaveGroupResult.BlockedByBalance -> {
                    val userBalance = balances[userId]
                    Log.w(TAG, "leaveGroup: BlockedByBalance [groupId=$groupId, userId=$userId, balance=$userBalance]")
                    return@withContext LeaveGroupResult.BlockedByBalance
                }
                is GroupExitValidator.LeaveGroupResult.BlockedAsLastMember -> {
                    Log.w(TAG, "leaveGroup: BlockedAsLastMember [groupId=$groupId, userId=$userId]")
                    return@withContext LeaveGroupResult.BlockedAsLastMember
                }
                is GroupExitValidator.LeaveGroupResult.Allowed -> {
                    // Proceed with removal
                }
            }

            // 6. Create sync and ledger operations and execute transaction
            val syncOp = syncWriteService.createGroupMemberRemoveSyncOp(groupId, userId)
            val ledgerOp = ledgerOperationFactory.createMemberRemoveOp(groupId, userId, userId)
            appDatabase.removeMemberWithLedger(groupId, userId, syncOp, ledgerOp)

            Log.d(TAG, "leaveGroup: Success [groupId=$groupId, userId=$userId, syncOpId=${syncOp.id}]")
            LeaveGroupResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "leaveGroup: Error [groupId=$groupId, userId=$userId, error=${e.message}]", e)
            LeaveGroupResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Attempts to remove another member from a group, enforcing business rules and recording a sync operation.
     *
     * This operation is idempotent and persists changes via the local database while creating a sync intent
     * for backend reconciliation. It mirrors the logic of [leaveGroup] but with explicit actor/target roles.
     *
     * @return `RemoveMemberResult` indicating success or the specific blocking reason.
     */
    override suspend fun removeMember(groupId: String, actorUserId: String, targetUserId: String): RemoveMemberResult = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch current members (single source of truth for this operation)
            val currentMembers = appDatabase.groupDao().getGroupMembers(groupId).first()
            val memberCount = currentMembers.size
            val isMember = currentMembers.any { it.userId == targetUserId }

            // 2. Check if user is still a member (idempotency)
            if (!isMember) {
                Log.d(TAG, "REMOVE_MEMBER groupId=$groupId actor=$actorUserId target=$targetUserId result=TargetAlreadyRemoved")
                return@withContext RemoveMemberResult.TargetAlreadyRemoved
            }

            // 3. Structural invariant: Cannot remove the last member (group would be orphaned)
            if (memberCount <= 1) {
                Log.w(TAG, "REMOVE_MEMBER groupId=$groupId actor=$actorUserId target=$targetUserId result=BlockedAsLastMember memberCount=$memberCount")
                return@withContext RemoveMemberResult.BlockedAsLastMember
            }

            // 4. Calculate balances for validation
            val expenses = appDatabase.expenseDao().getExpensesForGroup(groupId).first()
            val splits = appDatabase.expenseDao().getAllExpenseSplitsForGroup(groupId).first()
            val settlements = appDatabase.settlementDao().getSettlementsForGroup(groupId).first()
            val balances = BalanceCalculator.calculate(expenses, splits, settlements)

            // 5. Run domain validation for TARGET user
            val eligibility = GroupExitValidator.checkLeaveEligibility(
                userId = targetUserId,
                balances = balances,
                memberCount = memberCount
            )

            when (eligibility) {
                is GroupExitValidator.LeaveGroupResult.BlockedByBalance -> {
                    val userBalance = balances[targetUserId]
                    Log.w(TAG, "REMOVE_MEMBER groupId=$groupId actor=$actorUserId target=$targetUserId result=BlockedByBalance balance=$userBalance")
                    return@withContext RemoveMemberResult.BlockedByBalance
                }
                is GroupExitValidator.LeaveGroupResult.BlockedAsLastMember -> {
                    // Should be caught by step 3, but defensive double-check
                    Log.w(TAG, "REMOVE_MEMBER groupId=$groupId actor=$actorUserId target=$targetUserId result=BlockedAsLastMember")
                    return@withContext RemoveMemberResult.BlockedAsLastMember
                }
                is GroupExitValidator.LeaveGroupResult.Allowed -> {
                    // Proceed with removal
                }
            }

            // 6. Create sync and ledger operations and execute transaction
            // Reusing existing sync op type as "leaving" == "being removed" in backend terms
            val syncOp = syncWriteService.createGroupMemberRemoveSyncOp(groupId, targetUserId)
            val ledgerOp = ledgerOperationFactory.createMemberRemoveOp(groupId, targetUserId, actorUserId)
            appDatabase.removeMemberWithLedger(groupId, targetUserId, syncOp, ledgerOp)

            Log.d(TAG, "REMOVE_MEMBER groupId=$groupId actor=$actorUserId target=$targetUserId result=Success syncOpId=${syncOp.id}")
            RemoveMemberResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "REMOVE_MEMBER groupId=$groupId actor=$actorUserId target=$targetUserId result=Error error=${e.message}", e)
            RemoveMemberResult.Error(e.message ?: "Unknown error")
        }
    }
}
