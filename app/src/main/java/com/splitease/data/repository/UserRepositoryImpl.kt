package com.splitease.data.repository

import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.User
import com.splitease.data.local.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val appDatabase: AppDatabase,
    private val ledgerOperationFactory: com.splitease.data.ledger.LedgerOperationFactory,
    private val ledgerSyncScheduler: com.splitease.data.sync.LedgerSyncScheduler,
    private val deviceRoleManager: com.splitease.data.device.DeviceRoleManager,
    private val ledgerWriteGate: com.splitease.data.ledger.LedgerWriteGate,
    private val userContext: com.splitease.data.identity.UserContext
) : UserRepository {

    /**
     * Observes all users in the data source.
     *
     * Emits updates whenever the stored list of users changes.
     *
     * @return A Flow that emits the current list of User entities; emits a new list whenever the underlying data changes.
     */
    override fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers()
    }

    /**
     * Provides a stream of the user with the given id.
     *
     * @param userId The id of the user to observe.
     * @return A `Flow` that emits the matching `User`, or `null` if no user exists with that id.
     */
    override fun getUser(userId: String): Flow<User?> {
        return userDao.getUser(userId)
    }

    /**
     * Creates a new "phantom" user, persists it to the ledger, and returns the generated user ID.
     *
     * Now emits a `USER.CREATE` ledger operation to ensure the phantom user's name is synced
     * to other devices and preserved across reinstalls.
     *
     * @param name The user's display name.
     * @param email The user's email; trimmed/nullified.
     * @param phone The user's phone; trimmed/nullified.
     * @return The newly generated user ID.
     */
    override suspend fun createPhantomUser(name: String, email: String?, phone: String?): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Generate UUID internally
            val newUserId = UUID.randomUUID().toString()
            
            // Create the user entity
            val newUser = User(
                id = newUserId,
                name = name,
                email = email?.trim()?.ifBlank { null },
                phone = phone?.trim()?.ifBlank { null },
                profileUrl = null
            )
            
            // Ledger Sync Logic
            ledgerWriteGate.withWriteLock {
                if (!deviceRoleManager.canWrite()) {
                     throw com.splitease.data.device.WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
                }
                
                // Get Author ID (Me)
                val authorId = userContext.userId.firstOrNull() ?: "unknown_author"
                
                // Create Ledger Op
                val ledgerOp = ledgerOperationFactory.createUserCreateOp(newUser, authorId)
                
                // Persist Atomically
                appDatabase.insertUserWithLedger(newUser, ledgerOp)
                
                // Schedule Sync
                ledgerSyncScheduler.schedulePush()
            }
            
            newUserId
        }
    }
}