package com.splitease.data.repository

import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.Settlement
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.User
import com.splitease.domain.PersonalGroupConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A ledger item shown in Friend Ledger Screen.
 * This is a read-only projection of expenses and settlements. Not persisted.
 */
sealed class FriendLedgerItem {
    abstract val expenseId: String
    abstract val groupId: String
    abstract val title: String
    abstract val amount: BigDecimal
    abstract val timestamp: Long
    abstract val payerName: String
    abstract val paidByCurrentUser: Boolean
    abstract val myShare: BigDecimal
    
    data class GroupExpense(
        override val expenseId: String,
        override val groupId: String,
        val groupName: String,
        override val title: String,
        override val amount: BigDecimal,
        override val timestamp: Long,
        override val payerName: String,
        override val paidByCurrentUser: Boolean,
        override val myShare: BigDecimal
    ) : FriendLedgerItem()
    
    data class DirectExpense(
        override val expenseId: String,
        override val groupId: String,
        override val title: String,
        override val amount: BigDecimal,
        override val timestamp: Long,
        override val payerName: String,
        override val paidByCurrentUser: Boolean,
        override val myShare: BigDecimal
    ) : FriendLedgerItem()

    data class SettlementItem(
        val id: String,
        override val expenseId: String = id, // Use settlement ID as unique identifier
        override val groupId: String = "",
        override val title: String = "Settlement",
        override val amount: BigDecimal,
        override val timestamp: Long,
        override val payerName: String,
        override val paidByCurrentUser: Boolean,
        override val myShare: BigDecimal = BigDecimal.ZERO // Settlements are direct transfers
    ) : FriendLedgerItem()
}

@Singleton
class FriendTransactionsRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val groupDao: GroupDao,
    private val userDao: UserDao,
    private val userContext: UserContext,
    private val settlementRepository: SettlementRepository
) {
    
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getTransactionsForFriend(friendId: String): Flow<List<FriendLedgerItem>> {
        return userContext.userId.flatMapLatest { currentUserId ->
            if (currentUserId.isEmpty()) return@flatMapLatest flowOf(emptyList())

            combine(
                expenseDao.getAllExpenses(),
                expenseDao.getAllSplits(),
                groupDao.getAllGroups(),
                userDao.getAllUsers(),
                settlementRepository.observeSettlementsBetween(currentUserId, friendId)
            ) { expenses, splits, groups, users, settlements ->
                
                // Lookup data maps
                val groupNameMap = groups.associate { it.id to it.name }
                val userNameMap = users.associate { it.id to it.name }
                val expenseSplitsMap = splits.groupBy { it.expenseId }
                
                // Helper to check participation
                val expenseParticipantsMap = mutableMapOf<String, MutableSet<String>>()
                splits.forEach { split ->
                    expenseParticipantsMap.getOrPut(split.expenseId) { mutableSetOf() }.add(split.userId)
                }

                // 1. Process Expenses
                val relevantExpenses = expenses.filter { expense ->
                    val participants = expenseParticipantsMap[expense.id] ?: emptySet()
                    val currentUserInvolved = expense.payerId == currentUserId || participants.contains(currentUserId)
                    val friendInvolved = expense.payerId == friendId || participants.contains(friendId)
                    currentUserInvolved && friendInvolved
                }

                val expenseItems = relevantExpenses.map { expense ->
                    val payerName = userNameMap[expense.payerId] 
                        ?: if (expense.payerId == currentUserId) "You" else "Unknown"
                    val displayPayerName = if (expense.payerId == currentUserId) "You" else payerName
                    val paidByCurrentUser = expense.payerId == currentUserId
                    
                    val expenseSplits = expenseSplitsMap[expense.id] ?: emptyList()
                    val myShare = expenseSplits.find { it.userId == currentUserId }?.amount ?: BigDecimal.ZERO
                    
                    if (expense.groupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                        FriendLedgerItem.DirectExpense(
                            expenseId = expense.id,
                            groupId = expense.groupId,
                            title = expense.title,
                            amount = expense.amount,
                            timestamp = expense.date.time,
                            payerName = displayPayerName,
                            paidByCurrentUser = paidByCurrentUser,
                            myShare = myShare
                        )
                    } else {
                        FriendLedgerItem.GroupExpense(
                            expenseId = expense.id,
                            groupId = expense.groupId,
                            groupName = groupNameMap[expense.groupId] ?: "Unknown Group",
                            title = expense.title,
                            amount = expense.amount,
                            timestamp = expense.date.time,
                            payerName = displayPayerName,
                            paidByCurrentUser = paidByCurrentUser,
                            myShare = myShare
                        )
                    }
                }

                // 2. Process Settlements
                val settlementItems = settlements.map { settlement ->
                    val isPayerMe = settlement.fromUserId == currentUserId
                    val displayPayerName = if (isPayerMe) "You" else (userNameMap[settlement.fromUserId] ?: "Unknown")

                    FriendLedgerItem.SettlementItem(
                        id = settlement.id,
                        amount = settlement.amount,
                        timestamp = settlement.date.time,
                        payerName = displayPayerName,
                        paidByCurrentUser = isPayerMe
                    )
                }

                // 3. Merge and Sort
                (expenseItems + settlementItems).sortedByDescending { it.timestamp }
            }
        }
    }
}
