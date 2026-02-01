package com.splitease.data.identity

import android.util.Log
import com.splitease.data.local.AppDatabase
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.data.local.entities.SyncStatus
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.domain.PersonalGroupConstants
import com.splitease.domain.GroupType
import com.splitease.data.sync.SyncWriteService
import java.util.Date

@Singleton
class IdentityBootstrapper @Inject constructor(
    private val userContext: UserContext,
    private val userDao: UserDao,
    private val groupDao: GroupDao,
    private val db: AppDatabase,
    private val ledgerOperationFactory: LedgerOperationFactory,
    private val syncWriteService: SyncWriteService
) {
    /**
     * Ensures the local user identity and virtual containers are registered in the database.
     * Must be called during authentication flow (startup recovery, login, signup).
     *
     * Semantics:
     * - Idempotent: checks specific existence before inserting.
     * - Fail-Safe: Propagates DB exceptions for caller to handle.
     * - Ledger-Aligned: Emits USER.CREATE ledger operation for the local user.
     */
    suspend fun ensureLocalUserRegistered(): Boolean {
        val userId = userContext.userId.firstOrNull()
        if (userId == null) {
            Log.w("IdentityBootstrapper", "Cannot bootstrap identity: No local user ID found")
            return false
        }
        
        try {
            // Check if user already exists to maintain idempotency
            val existingUser = userDao.getUserById(userId)
            
            if (existingUser == null) {
                // Construct the User entity
                val user = User(
                    id = userId,
                    name = IdentityConstants.LOCAL_USER_DISPLAY_NAME,
                    email = null,
                    profileUrl = null
                )

                // Create the Ledger Operation (USER.CREATE)
                val ledgerOp = ledgerOperationFactory.createUserCreateOp(
                    user = user,
                    authorUserId = userId
                )

                // Create the Sync Operation
                val syncOp = SyncOperation(
                    operationType = LedgerOperationFactory.OP_CREATE,
                    entityType = SyncEntityType.USER,
                    entityId = userId,
                    payload = ledgerOp.payload,
                    status = SyncStatus.PENDING,
                    timestamp = Date().time
                )

                // Atomic Commit: User + Sync + Ledger
                db.insertUserWithLedger(user, syncOp, ledgerOp)
                Log.d("IdentityBootstrapper", "Bootstrapped local user identity with ledger: $userId")
            } else {
                 Log.d("IdentityBootstrapper", "Local user identity already exists: $userId")
            }

            // Verify/Bootstrap Personal Group Container
            // We use a check-then-insert pattern here to ensure the core container
            // is present and durable in the ledger.
            val personalGroupExists = groupDao.getGroupById(PersonalGroupConstants.PERSONAL_GROUP_ID) != null
            if (!personalGroupExists) {
                val group = Group(
                    id = PersonalGroupConstants.PERSONAL_GROUP_ID,
                    name = PersonalGroupConstants.PERSONAL_GROUP_NAME,
                    type = GroupType.OTHER,
                    createdBy = userId,
                    createdByUserId = userId,
                    lastModifiedByUserId = userId
                )

                // Every group must have at least one member (the creator) to satisfy UI invariants
                val members = listOf(
                    GroupMember(
                        groupId = group.id,
                        userId = userId,
                        joinedAt = Date()
                    )
                )

                // Create sync and ledger facts
                val syncOp = syncWriteService.createGroupCreateSyncOp(group, members)
                val ledgerOp = ledgerOperationFactory.createGroupCreateOp(group, members, userId)

                // Atomic Commit: Group + Members + Sync + Ledger
                db.insertGroupWithMembersAndLedger(group, members, syncOp, ledgerOp)
                Log.d("IdentityBootstrapper", "Bootstrapped personal group container with ledger: ${group.id}")
            } else {
                 Log.d("IdentityBootstrapper", "Personal group container already exists")
            }

            return true
        } catch (e: Exception) {
            Log.e("IdentityBootstrapper", "Failed to bootstrap identity for $userId", e)
            throw e 
        }
    }
}
