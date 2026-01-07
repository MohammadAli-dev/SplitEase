package com.splitease.data.repository

import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.UserDao
import com.splitease.domain.PersonalGroupConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A ledger item shown in Friend Ledger Screen.
 * This is a read-only projection of expenses. Not persisted.
 * 
 * Invariant: PERSONAL_GROUP_ID is a virtual identifier and does NOT exist in expense_groups table.
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
    
    /**
     * An expense from a shared group.
     * Subtitle: "Shared group" or the group name
     */
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
    
    /**
     * A direct (non-group) expense between users.
     * Subtitle: "<payerName> paid ₹<amount>"
     */
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
}

/**
 * Repository for fetching all expenses between the current user and a specific friend.
 * 
 * This is a cross-group, person-centric projection that includes:
 * - Group expenses from any shared groups
 * - Direct (non-group) expenses between the two users
 * 
 * Invariant: This repository only queries data. No balance calculations or mutations.
 */
@Singleton
class FriendTransactionsRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val groupDao: GroupDao,
    private val userDao: UserDao,
    private val userContext: UserContext
) {
    
    /**
     * Returns all expenses involving both the current user and the specified friend.
     * 
     * Includes:
     * - Group expenses where both participated
     * - Direct (non-group) expenses where both participated
     * 
     * Each item includes payer information for proper UI subtitle display.
     */
    fun getTransactionsForFriend(friendId: String): Flow<List<FriendLedgerItem>> {
        return combine(
            expenseDao.getAllExpenses(),
            expenseDao.getAllSplits(),
            groupDao.getAllGroups(),
            userDao.getAllUsers(),
            userContext.userId
        ) { expenses, splits, groups, users, currentUserId ->
            if (currentUserId.isEmpty()) return@combine emptyList()
            
            // Build group name lookup
            val groupNameMap = groups.associate { it.id to it.name }
            
            // Build user name lookup
            val userNameMap = users.associate { it.id to it.name }
            
            // Build splits lookup: expenseId -> List of splits
            val expenseSplitsMap = splits.groupBy { it.expenseId }
            
            // Build splits lookup: expenseId -> Set of participant userIds
            val expenseParticipantsMap = mutableMapOf<String, MutableSet<String>>()
            splits.forEach { split ->
                expenseParticipantsMap.getOrPut(split.expenseId) { mutableSetOf() }
                    .add(split.userId)
            }
            
            // Filter expenses where BOTH current user AND friend participated
            val relevantExpenses = expenses.filter { expense ->
                val participants = expenseParticipantsMap[expense.id] ?: emptySet()
                // Both users must be participants (via splits or as payer)
                val currentUserInvolved = expense.payerId == currentUserId || 
                    participants.contains(currentUserId)
                val friendInvolved = expense.payerId == friendId || 
                    participants.contains(friendId)
                currentUserInvolved && friendInvolved
            }
            
            // Map to domain model with payer info
            relevantExpenses.map { expense ->
                val payerName = userNameMap[expense.payerId] 
                    ?: if (expense.payerId == currentUserId) "You" else "Unknown"
                val displayPayerName = if (expense.payerId == currentUserId) "You" else payerName
                val paidByCurrentUser = expense.payerId == currentUserId
                
                // Calculate my share from splits
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
            }.sortedByDescending { it.timestamp }
        }
    }
}

