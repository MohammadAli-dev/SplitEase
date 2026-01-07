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
     * Create a global settlement record representing a payment from one user to another.
     *
     * The created settlement will record `fromUserId` as the creator and will be persisted
     * with amount normalized to two decimal places.
     *
     * @param fromUserId Identifier of the payer.
     * @param toUserId Identifier of the payee.
     * @param amount The settlement amount; must be greater than zero.
     * @throws IllegalArgumentException if `fromUserId` equals `toUserId` ("Settlement cannot be self-directed")
     *         or if `amount` is not positive ("Settlement amount must be positive").
     */
    suspend fun createSettlement(
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal
    )

    /**
 * Emit updates of settlements that involve the two specified users, regardless of direction.
 *
 * @param userA The id of the first user.
 * @param userB The id of the second user.
 * @return A Flow that emits lists of settlements where either `userA` paid `userB` or `userB` paid `userA`.
 */
    fun observeSettlementsBetween(userA: String, userB: String): Flow<List<Settlement>>

    /**
     * Creates, persists, and enqueues synchronization for a settlement between two users.
     *
     * This performs the low-level creation of a Settlement record (optionally scoped to a group)
     * and persists it to the database while creating a corresponding sync operation.
     *
     * @param groupId Identifier of the group the settlement belongs to; use an empty string for global (non-group) settlements.
     * @param fromUserId User ID of the payer.
     * @param toUserId User ID of the payee.
     * @param amount The settlement amount; normalized to two decimal places using RoundingMode.HALF_UP.
     * @param creatorUserId User ID recorded as the creator/last modifier of the settlement.
     * @throws IllegalArgumentException if `fromUserId` equals `toUserId` or if `amount` is less than or equal to zero.
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

    /**
     * Creates a global (non-group) settlement from one user to another, recording the payer as the creator.
     *
     * The created settlement is global (no group) and will have its amount normalized to two decimal places using HALF_UP rounding.
     *
     * @param fromUserId ID of the payer (also recorded as the creator).
     * @param toUserId ID of the payee.
     * @param amount The settlement amount; it will be rounded to two decimal places.
     */
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

    /**
     * Observes settlements involving the two specified users (in either direction).
     *
     * @param userA Identifier of the first user; order with `userB` does not matter.
     * @param userB Identifier of the second user; order with `userA` does not matter.
     * @return A Flow that emits the current list of Settlement records involving both users whenever the underlying data changes.
     */
    override fun observeSettlementsBetween(userA: String, userB: String): Flow<List<Settlement>> {
        return appDatabase.settlementDao().observeSettlementsBetween(userA, userB)
    }

    /**
     * Creates and persists a settlement between two users, recording a sync operation for replication.
     *
     * The settlement amount is normalized to two decimal places using HALF_UP rounding, a new
     * Settlement record is created with a generated id and current date, and both the settlement
     * and its corresponding sync operation are inserted into the database.
     *
     * @param groupId Identifier of the group the settlement belongs to; an empty string denotes a global (non-group) settlement.
     * @param fromUserId User id of the payer.
     * @param toUserId User id of the recipient.
     * @param amount Monetary amount to settle; it will be rounded to two decimal places.
     * @param creatorUserId User id recorded as the creator and last modifier of the settlement.
     * @throws IllegalArgumentException if `fromUserId` equals `toUserId` ("Settlement cannot be self-directed") or if `amount` is not greater than zero ("Settlement amount must be positive").
     */
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