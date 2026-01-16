package com.splitease.data.repository

import android.util.Log
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
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
 * Repository for group operations.
 */
interface GroupRepository {
    /**
     * Creates a new group with the specified members.
     *
     * This method is:
     * - **Offline-safe**: Works without network connectivity.
     * - **Atomic**: Group, members, and sync operation are persisted in a single transaction.
     * - **Sync-aware**: Records a CREATE_GROUP sync intent for future backend sync.
     *
     * UI should rely on Room Flows (e.g., `GroupDao.getAllGroups()`) for updates,
     * not on the return value of this method.
     *
     * @param name The display name of the group.
     * @param type The group type (e.g., TRIP, HOME, COUPLE, OTHER).
     * @param memberIds List of user IDs to add as members.
     * @param hasTripDates Whether trip dates are enabled.
     * @param tripStartDate Trip start date (epoch millis), null if not applicable.
     * @param tripEndDate Trip end date (epoch millis), null if not applicable.
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
     * Removes the current user from a group.
     *
     * This method is:
     * - **Offline-safe**: Works without network connectivity.
     * - **Atomic**: Member removal and sync operation are persisted in a single transaction.
     * - **Balance-gated**: User CANNOT leave if their net balance != 0.
     * - **Last-member protected**: User CANNOT leave if they are the last member.
     * - **Idempotent**: Calling multiple times for an already-removed user returns [LeaveGroupResult.AlreadyRemoved].
     *
     * @param groupId The ID of the group to leave.
     * @param userId The ID of the user leaving the group.
     * @return A [LeaveGroupResult] indicating success or the reason for blocking.
     */
    suspend fun leaveGroup(groupId: String, userId: String): LeaveGroupResult
}

@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService
) : GroupRepository {

    companion object {
        private const val TAG = "GroupRepository"
    }

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

            appDatabase.insertGroupWithMembersAndSync(group, members, syncOp)
        }

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

            // 3. Check member count guardrail (derived from same snapshot)
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

            // 6. Create sync operation and execute transaction
            val syncOp = syncWriteService.createGroupMemberRemoveSyncOp(groupId, userId)
            appDatabase.leaveGroupWithSync(groupId, userId, syncOp)

            Log.d(TAG, "leaveGroup: Success [groupId=$groupId, userId=$userId, syncOpId=${syncOp.id}]")
            LeaveGroupResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "leaveGroup: Error [groupId=$groupId, userId=$userId, error=${e.message}]", e)
            LeaveGroupResult.Error(e.message ?: "Unknown error")
        }
    }
}

