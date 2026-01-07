package com.splitease.data.repository

import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.domain.PersonalGroupConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A transaction item shown in Friend Detail Screen.
 * 
 * Invariant: PERSONAL_GROUP_ID is a virtual identifier and does NOT exist in expense_groups table.
 */
sealed class FriendTransactionItem {
    abstract val expenseId: String
    abstract val title: String
    abstract val amount: BigDecimal
    abstract val timestamp: Long
    
    data class GroupExpense(
        override val expenseId: String,
        override val title: String,
        val groupId: String,
        val groupName: String,
        override val amount: BigDecimal,
        override val timestamp: Long
    ) : FriendTransactionItem()
    
    data class DirectExpense(
        override val expenseId: String,
        override val title: String,
        override val amount: BigDecimal,
        override val timestamp: Long
    ) : FriendTransactionItem()
}

/**
 * Repository for fetching all transactions (expenses) between the current user and a specific friend.
 * 
 * Invariant: This repository only queries data. No balance calculations or mutations.
 */
@Singleton
class FriendTransactionsRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val groupDao: GroupDao,
    private val userContext: UserContext
) {
    
    /**
     * Returns all expenses involving both the current user and the specified friend.
     * Includes:
     * - Group expenses where both participated
     * - Direct (non-group) expenses where both participated
     */
    fun getTransactionsForFriend(friendId: String): Flow<List<FriendTransactionItem>> {
        return combine(
            expenseDao.getAllExpenses(),
            expenseDao.getAllSplits(),
            groupDao.getAllGroups(),
            userContext.userId
        ) { expenses, splits, groups, currentUserId ->
            if (currentUserId.isEmpty()) return@combine emptyList()
            
            // Build group name lookup
            val groupNameMap = groups.associate { it.id to it.name }
            
            // Build splits lookup: expenseId -> Set of participant userIds
            val expenseParticipantsMap = mutableMapOf<String, MutableSet<String>>()
            splits.forEach { split ->
                expenseParticipantsMap.getOrPut(split.expenseId) { mutableSetOf() }
                    .add(split.userId)
            }
            
            // Filter expenses where BOTH current user AND friend participated
            val relevantExpenses = expenses.filter { expense ->
                val participants = expenseParticipantsMap[expense.id] ?: emptySet()
                // Both users must be participants (via splits)
                // OR one is the payer and the other is a participant
                val currentUserInvolved = expense.payerId == currentUserId || 
                    participants.contains(currentUserId)
                val friendInvolved = expense.payerId == friendId || 
                    participants.contains(friendId)
                currentUserInvolved && friendInvolved
            }
            
            // Map to domain model
            relevantExpenses.map { expense ->
                if (expense.groupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                    FriendTransactionItem.DirectExpense(
                        expenseId = expense.id,
                        title = expense.title,
                        amount = expense.amount,
                        timestamp = expense.date.time
                    )
                } else {
                    FriendTransactionItem.GroupExpense(
                        expenseId = expense.id,
                        title = expense.title,
                        groupId = expense.groupId,
                        groupName = groupNameMap[expense.groupId] ?: "Unknown Group",
                        amount = expense.amount,
                        timestamp = expense.date.time
                    )
                }
            }.sortedByDescending { it.timestamp }
        }
    }
}
