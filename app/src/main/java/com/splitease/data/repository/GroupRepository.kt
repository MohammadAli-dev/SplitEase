package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.sync.SyncWriteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
}

@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService
) : GroupRepository {

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
}
