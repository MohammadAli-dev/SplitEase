package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.Settlement
import com.splitease.data.sync.SyncWriteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface SettlementRepository {
    /**
     * Creates a new settlement (global, not group-specific).
     * @param fromUserId The user who paid.
     * @param toUserId The user who received.
     * @param amount The amount settled.
     */
    suspend fun createSettlement(
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal
    )

    /**
     * Observes all settlements between two users (in either direction).
     */
    fun observeSettlementsBetween(userA: String, userB: String): Flow<List<Settlement>>

    /**
     * Low-level execution of a settlement. Kept for flexibility if group-specific settlements are needed later.
     */
    suspend fun executeSettlement(
        groupId: String,
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal,
        creatorUserId: String
    )
}

@Singleton
class SettlementRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService
) : SettlementRepository {

    override suspend fun createSettlement(
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal
    ) {
        // Payer is the creator implicitly for now (in absence of Auth Context here)
        // Global settlements use empty string for groupId
        executeSettlement(
            groupId = "",
            fromUserId = fromUserId,
            toUserId = toUserId,
            amount = amount,
            creatorUserId = fromUserId
        )
    }

    override fun observeSettlementsBetween(userA: String, userB: String): Flow<List<Settlement>> {
        return appDatabase.settlementDao().observeSettlementsBetween(userA, userB)
    }

    override suspend fun executeSettlement(
        groupId: String,
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal,
        creatorUserId: String
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
            amount = amount.setScale(2, RoundingMode.HALF_UP),
            date = Date(),
            createdByUserId = creatorUserId,
            lastModifiedByUserId = creatorUserId
        )

        val syncOp = syncWriteService.createSettlementCreateSyncOp(settlement)

        appDatabase.insertSettlementWithSync(settlement, syncOp)
    }
}
