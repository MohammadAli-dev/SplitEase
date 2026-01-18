package com.splitease.data.repository

import com.splitease.data.local.AppDatabase
import com.splitease.data.local.entities.Settlement
import com.splitease.data.device.DeviceRoleManager
import com.splitease.data.device.WritePermissionDeniedException
import com.splitease.data.ledger.LedgerOperationFactory
import com.splitease.data.sync.LedgerSyncScheduler
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
     * Create a global settlement between two users.
     *
     * This records a settlement that is not tied to any group and treats the payer (`fromUserId`) as the creator of the settlement. Currency must be provided as an ISO 4217 code and will not be defaulted.
     *
     * @param fromUserId The user who paid and will be recorded as the creator.
     * @param toUserId The user who received payment.
     * @param amount The settlement amount (will be stored with two decimal places).
     * @param currency The currency code (ISO 4217) for the settlement.
     * @throws ReadOnlyViolationException if device is in read-only mode.
     */
    suspend fun createSettlement(
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal,
        currency: String
    )

    /**
 * Observe settlements involving the two specified users, regardless of which one is payer or payee.
 *
 * @return Lists of settlements between the two users (either direction), updated when the underlying data changes.
 */
    fun observeSettlementsBetween(userA: String, userB: String): Flow<List<Settlement>>

    /**
     * Executes a settlement record and persists its associated sync and ledger operations.
     *
     * Performs domain validation, constructs the Settlement with the provided currency and creator, creates the corresponding sync and ledger operations, and persists them together.
     *
     * @param groupId The group identifier for the settlement; use an empty string to indicate a global (non-group) settlement.
     * @param fromUserId The payer's user ID.
     * @param toUserId The payee's user ID.
     * @param amount The settlement amount; must be greater than zero.
     * @param currency The currency code for the settlement; must be explicitly provided to preserve ledger integrity.
     * @param creatorUserId The user ID recorded as the creator and last modifier of the settlement.
     *
     * @throws IllegalArgumentException if `fromUserId` equals `toUserId` with message "Settlement cannot be self-directed".
     * @throws IllegalArgumentException if `amount` is not positive with message "Settlement amount must be positive".
     * @throws ReadOnlyViolationException if device is in read-only mode.
     */
    suspend fun executeSettlement(
        groupId: String,
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal,
        currency: String,
        creatorUserId: String
    )
}

@Singleton
class SettlementRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncWriteService: SyncWriteService,
    private val ledgerOperationFactory: LedgerOperationFactory,
    private val ledgerSyncScheduler: LedgerSyncScheduler,
    private val deviceRoleManager: DeviceRoleManager
) : SettlementRepository {

    /**
     * Creates a global settlement (no group) from one user to another, treating the payer as the creator.
     *
     * @param fromUserId The payer's user ID (also used as the creator ID for the settlement).
     * @param toUserId The payee's user ID.
     * @param amount The settlement amount (will be stored with two decimal places).
     * @param currency The currency code for the settlement (must be provided; no defaulting).
     *
     * @throws IllegalArgumentException if `fromUserId` equals `toUserId`.
     * @throws IllegalArgumentException if `amount` is not greater than zero.
     * @throws ReadOnlyViolationException if device is in read-only mode.
     */
    override suspend fun createSettlement(
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal,
        currency: String
    ) {
        // Payer is the creator implicitly for now (in absence of Auth Context here)
        // Global settlements use empty string for groupId
        executeSettlement(
            groupId = "",
            fromUserId = fromUserId,
            toUserId = toUserId,
            amount = amount,
            currency = currency,
            creatorUserId = fromUserId
        )
    }

    override fun observeSettlementsBetween(userA: String, userB: String): Flow<List<Settlement>> {
        return appDatabase.settlementDao().observeSettlementsBetween(userA, userB)
    }

    /**
     * Creates and persists a settlement between two users, records a corresponding sync operation and a ledger operation.
     *
     * The created settlement is persisted with the amount rounded to two decimal places (HALF_UP), the date set to now,
     * and both createdByUserId and lastModifiedByUserId set to `creatorUserId`.
     *
     * @param groupId Identifier of the group the settlement belongs to; use an empty string for a global (non-group) settlement.
     * @param fromUserId The payer's user ID.
     * @param toUserId The payee's user ID.
     * @param amount The settlement amount; will be stored scaled to two decimal places.
     * @param currency The ISO currency code for the settlement.
     * @param creatorUserId User ID recorded as the creator of the settlement (typically the payer when no auth context is available).
     * @throws IllegalArgumentException If `fromUserId` equals `toUserId` or if `amount` is not greater than zero.
     * @throws ReadOnlyViolationException if device is in read-only mode.
     */
    override suspend fun executeSettlement(
        groupId: String,
        fromUserId: String,
        toUserId: String,
        amount: BigDecimal,
        currency: String,
        creatorUserId: String
    ) = withContext(Dispatchers.IO) {
        // Write permission guard
        if (!deviceRoleManager.canWrite()) {
            throw WritePermissionDeniedException(deviceRoleManager.getDeviceRole())
        }

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
            currency = currency,
            date = Date(),
            createdByUserId = creatorUserId,
            lastModifiedByUserId = creatorUserId
        )

        val syncOp = syncWriteService.createSettlementCreateSyncOp(settlement)
        val ledgerOp = ledgerOperationFactory.createSettlementCreateOp(settlement, creatorUserId)

        appDatabase.insertSettlementWithLedger(settlement, syncOp, ledgerOp)
        ledgerSyncScheduler.schedulePush()
    }
}