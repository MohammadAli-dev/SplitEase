package com.splitease.data.repository

import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.domain.BalanceCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

data class DashboardSummary(
    val totalOwed: BigDecimal = BigDecimal.ZERO,
    val totalOwing: BigDecimal = BigDecimal.ZERO,
    val friendBalances: List<FriendBalance> = emptyList()
)

/**
 * Balance with a specific friend across all groups.
 * Positive = they owe you; Negative = you owe them.
 */
data class FriendBalance(
    val friendId: String,
    val friendName: String,
    val balance: BigDecimal // positive = owed to you, negative = you owe them
)

@Singleton
class BalanceSummaryRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val settlementDao: SettlementDao,
    private val userContext: UserContext,
    private val identityResolver: com.splitease.data.identity.IdentityResolver
) {

    fun getDashboardSummary(): Flow<DashboardSummary> {
        return combine(
            expenseDao.getAllExpenses(),
            expenseDao.getAllSplits(),
            settlementDao.getAllSettlements(),
            userContext.userId
        ) { expenses, splits, settlements, currentUserId ->
            if (currentUserId.isEmpty()) return@combine DashboardSummary()

            // Collect all unique IDs for batch resolution
            val allIds = mutableSetOf<String>()
            allIds.add(currentUserId)
            expenses.forEach { allIds.add(it.payerId) } // Payer
            splits.forEach { allIds.add(it.userId) } // Split participants
            settlements.forEach { 
                allIds.add(it.fromUserId)
                allIds.add(it.toUserId)
            }
            
            // Batch resolve all involved identities keyed by INPUT ID
            val resolvedMap = identityResolver.resolveBatchByUserId(allIds.toList())
            
            // Helper to resolve any ID (User or Person) to canonical Person ID using the resolved map
            fun toCanonicalId(id: String): String {
                return resolvedMap[id]?.stableId ?: id
            }

            var totalOwed = BigDecimal.ZERO
            var totalOwing = BigDecimal.ZERO
            
            // Track balance per friend (canonicalPersonId -> net balance with current user)
            val friendBalanceMap = mutableMapOf<String, BigDecimal>()
            
            // Resolve current user's canonical ID
            val currentUserCanonicalId = toCanonicalId(currentUserId)

            // Build lookup: expenseId -> payer canonical ID
            val expensePayerMap = expenses.associate { it.id to toCanonicalId(it.payerId) }
            
            // Process each split to compute per-friend balances
            splits.forEach { split ->
                val expensePayerId = expensePayerMap[split.expenseId] ?: return@forEach
                val friendId: String
                val balanceChange: BigDecimal
                
                val splitPersonId = toCanonicalId(split.userId)
                
                if (splitPersonId == currentUserCanonicalId) {
                    // Current user owes this split amount to the payer
                    if (expensePayerId != currentUserCanonicalId) {
                        friendId = expensePayerId
                        balanceChange = -split.amount // negative = I owe them
                    } else {
                        return@forEach // Self-payment, no balance change
                    }
                } else if (expensePayerId == currentUserCanonicalId) {
                    // Someone else owes me this amount
                    friendId = splitPersonId
                    balanceChange = split.amount // positive = they owe me
                } else {
                    return@forEach // Transaction between two other people
                }
                
                friendBalanceMap[friendId] = (friendBalanceMap[friendId] ?: BigDecimal.ZERO) + balanceChange
            }
            
            // Process settlements
            settlements.forEach { settlement ->
                val friendId: String
                val balanceChange: BigDecimal
                
                val fromCanonical = toCanonicalId(settlement.fromUserId)
                val toCanonical = toCanonicalId(settlement.toUserId)
                
                if (fromCanonical == currentUserCanonicalId) {
                    // I paid them (reduces what I owe / increases what they owe me)
                    friendId = toCanonical
                    balanceChange = settlement.amount // positive for me
                } else if (toCanonical == currentUserCanonicalId) {
                    // They paid me (reduces what they owe / I owe them more)
                    friendId = fromCanonical
                    balanceChange = -settlement.amount // negative for me
                } else {
                    return@forEach
                }
                
                friendBalanceMap[friendId] = (friendBalanceMap[friendId] ?: BigDecimal.ZERO) + balanceChange
            }
            
            // Build friend balances list (filter out zero balances)
            // Note: Using compareTo instead of != for BigDecimal to handle scale differences
            val friendBalances = friendBalanceMap
                .filter { it.value.compareTo(BigDecimal.ZERO) != 0 }
                .map { (friendCanonicalId, balance) ->
                    // Reverse logical lookup: We need a Display Name for this Canonical ID.
                    // We can find any entry in resolvedMap that maps to this stableId.
                    // Or more robustly, since resolvedMap keys are input IDs, we might have multiple inputs mapping to one stableId.
                    // We pick the best display name.
                    val participant = resolvedMap.values.find { it.stableId == friendCanonicalId }
                    FriendBalance(
                        friendId = friendCanonicalId,
                        friendName = participant?.displayName ?: "Unknown",
                        balance = balance
                    )
                }
                .sortedByDescending { it.balance.abs() }
            
            // Compute totals
            friendBalances.forEach { fb ->
                if (fb.balance > BigDecimal.ZERO) {
                    totalOwed += fb.balance
                } else {
                    totalOwing += fb.balance.abs()
                }
            }
            
            DashboardSummary(totalOwed, totalOwing, friendBalances)
        }
    }

    /**
     * Single source of truth for a specific friend's balance.
     * Reuses dashboard logic to ensure consistency.
     */
    fun getBalanceWithFriend(friendId: String): Flow<BigDecimal> {
        return getDashboardSummary().map { summary ->
            summary.friendBalances.find { it.friendId == friendId }?.balance ?: BigDecimal.ZERO
        }
    }
}
