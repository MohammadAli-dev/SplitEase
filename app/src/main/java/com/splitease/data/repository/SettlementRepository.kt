package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.Settlement
import com.splitease.data.sync.SyncWriteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface SettlementRepository {
    /**
     * Executes a settlement between two users.
     *
     * - **Atomic**: Persists settlement and sync intent in one transaction.
     * - **Offline-safe**: Works without network.
     * - **Idempotent**: Sync payload includes unique ID for backend deduping.
     *
     * @throws IllegalArgumentException if fromUserId == toUserId
     */
    suspend fun executeSettlement(
        groupId: String,
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal
    )
}

@Singleton
class SettlementRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService
) : SettlementRepository {

    override suspend fun executeSettlement(
        groupId: String,
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal
    ) = withContext(Dispatchers.IO) {
        // Domain Guard: No self-settlement
        require(fromUserId != toUserId) {
            "Settlement cannot be self-directed"
        }
        
        // Domain Guard: Amount must be positive
        require(amount.signum() > 0) {
            "Settlement amount must be positive"
        }

        val settlement = Settlement(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            fromUserId = fromUserId,
            toUserId = toUserId,
            amount = amount,
            date = Date()
        )

        val syncOp = syncWriteService.createSettlementCreateSyncOp(settlement)

        appDatabase.insertSettlementWithSync(settlement, syncOp)
    }
}
