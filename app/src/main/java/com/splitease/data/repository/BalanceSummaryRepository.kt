package com.splitease.data.repository

import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.domain.BalanceCalculator
import kotlinx.coroutines.flow.Flow
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
    private val userContext: UserContext
) {

    fun getDashboardSummary(): Flow<DashboardSummary> {
        return combine(
            expenseDao.getAllExpenses(),
            expenseDao.getAllSplits(),
            settlementDao.getAllSettlements(),
            userContext.userId
        ) { expenses, splits, settlements, currentUserId ->
            if (currentUserId.isEmpty()) return@combine DashboardSummary()

            var totalOwed = BigDecimal.ZERO
            var totalOwing = BigDecimal.ZERO
            
            // Track balance per friend (personId -> net balance with current user)
            val friendBalanceMap = mutableMapOf<String, BigDecimal>()

            // Build lookup: expenseId -> payer userId
            val expensePayerMap = expenses.associate { it.id to it.payerId }
            
            // Process each split to compute per-friend balances
            splits.forEach { split ->
                val expensePayerId = expensePayerMap[split.expenseId] ?: return@forEach
                val friendId: String
                val balanceChange: BigDecimal
                
                if (split.userId == currentUserId) {
                    // Current user owes this split amount to the payer
                    if (expensePayerId != currentUserId) {
                        friendId = expensePayerId
                        balanceChange = -split.amount // negative = I owe them
                    } else {
                        return@forEach // Self-payment, no balance change
                    }
                } else if (expensePayerId == currentUserId) {
                    // Someone else owes me this amount
                    friendId = split.userId
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
                
                if (settlement.fromUserId == currentUserId) {
                    // I paid them (reduces what I owe / increases what they owe me)
                    friendId = settlement.toUserId
                    balanceChange = settlement.amount // positive for me
                } else if (settlement.toUserId == currentUserId) {
                    // They paid me (reduces what they owe / I owe them more)
                    friendId = settlement.fromUserId
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
                .map { (friendId, balance) ->
                    FriendBalance(
                        friendId = friendId,
                        friendName = friendId.take(8), // Placeholder - will be resolved in ViewModel
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
}
