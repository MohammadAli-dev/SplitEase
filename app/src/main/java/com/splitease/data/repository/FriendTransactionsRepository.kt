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
    abstract val currency: String // ISO 4217, e.g., "INR" or "USD"
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
        override val currency: String,
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
        override val currency: String,
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
        override val currency: String,
        override val timestamp: Long,
        override val payerName: String,
        override val paidByCurrentUser: Boolean,
        override val myShare: BigDecimal = BigDecimal.ZERO // Settlements are direct transfers
    ) : FriendLedgerItem()
}

@Singleton
class FriendTransactionsRepository @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val expenseDao: ExpenseDao,
    private val groupDao: GroupDao,
    private val settlementRepository: SettlementRepository,
    private val personDao: com.splitease.data.local.dao.PersonDao,
    private val userDao: UserDao,
    private val userContext: UserContext,
    private val identityResolver: com.splitease.data.identity.IdentityResolver
) {
    
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getTransactionsForFriend(friendId: String): Flow<List<FriendLedgerItem>> {
        return userContext.userId.flatMapLatest { currentUserId ->
            if (currentUserId.isEmpty()) return@flatMapLatest flowOf(emptyList())

            combine(
                expenseRepository.getAllEffectiveExpenses(),
                expenseDao.getAllSplits(),
                groupDao.getAllGroups(),
                settlementRepository.observeSettlementsBetween(currentUserId, friendId)
            ) { expenses, splits, groups, settlements ->
                
                // Lookup data maps
                val groupNameMap = groups.associate { it.id to it.name }
                
                // Collect all involved IDs for batch resolution
                val involvedIds = mutableSetOf<String>()
                involvedIds.add(currentUserId)
                involvedIds.add(friendId)
                
                expenses.forEach { involvedIds.add(it.payerId) }
                settlements.forEach { 
                    involvedIds.add(it.fromUserId) 
                    involvedIds.add(it.toUserId)
                }
                
                // Batch resolve known IDs
                // Note: We are missing split user IDs here in the batch if we don't iterate splits relative to filtered expenses.
                // However, filtering effectively happens below.
                // Let's iterate splits for relevant expenses only? Or just all splits? 
                // Iterating all splits is safer for correctness.
                splits.forEach { involvedIds.add(it.userId) }

                val resolvedMap = identityResolver.resolveBatchByUserId(involvedIds.toList())
                
                // Helper to resolve identities to canonical Person IDs
                fun toCanonicalId(id: String): String = resolvedMap[id]?.stableId ?: id
                
                // Determine target friend canonical ID
                val friendCanonicalId = toCanonicalId(friendId)
                
                // Determine current user canonical ID
                val myCanonicalId = toCanonicalId(currentUserId)

                val expenseSplitsMap = splits.groupBy { it.expenseId }
                
                // Helper to check participation
                val expenseParticipantsMap = mutableMapOf<String, MutableSet<String>>()
                splits.forEach { split ->
                    expenseParticipantsMap.getOrPut(split.expenseId) { mutableSetOf() }.add(split.userId)
                }

                // 1. Process Expenses
                val relevantExpenses = expenses.filter { expense ->
                    val participants = expenseParticipantsMap[expense.id] ?: emptySet()
                    
                    // Unified Check: Did Me OR My-Proxy participate?
                    val payerCanonical = toCanonicalId(expense.payerId)
                    val currentUserInvolved = payerCanonical == myCanonicalId || participants.any { toCanonicalId(it) == myCanonicalId }
                    
                    // Unified Check: Did Friend OR Friend-Proxy participate?
                    val friendInvolved = payerCanonical == friendCanonicalId || participants.any { toCanonicalId(it) == friendCanonicalId }
                    
                    currentUserInvolved && friendInvolved
                }

                val expenseItems = relevantExpenses.map { expense ->
                    val payerCanonical = toCanonicalId(expense.payerId)
                    val paidByCurrentUser = payerCanonical == myCanonicalId
                    
                    // Use resolved display name
                    val displayPayerName = resolvedMap[expense.payerId]?.displayName ?: "Unknown"
                    
                    
                    val expenseSplits = expenseSplitsMap[expense.id] ?: emptyList()
                    val myShare = expenseSplits.find { toCanonicalId(it.userId) == myCanonicalId }?.amount ?: BigDecimal.ZERO
                    
                    if (expense.groupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                        FriendLedgerItem.DirectExpense(
                            expenseId = expense.id,
                            groupId = expense.groupId,
                            title = expense.title,
                            amount = expense.amount,
                            currency = expense.currency,
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
                            currency = expense.currency,
                            timestamp = expense.date.time,
                            payerName = displayPayerName,
                            paidByCurrentUser = paidByCurrentUser,
                            myShare = myShare
                        )
                    }
                }

                // 2. Process Settlements
                // 2. Process Settlements
                val settlementItems = settlements.filter { settlement ->
                    val fromCanonical = toCanonicalId(settlement.fromUserId)
                    val toCanonical = toCanonicalId(settlement.toUserId)
                    
                    val meInvolved = fromCanonical == myCanonicalId || toCanonical == myCanonicalId
                    val friendInvolved = fromCanonical == friendCanonicalId || toCanonical == friendCanonicalId
                    
                    meInvolved && friendInvolved
                }.map { settlement ->
                    val fromCanonical = toCanonicalId(settlement.fromUserId)
                    val isPayerMe = fromCanonical == myCanonicalId
                    val displayPayerName = resolvedMap[settlement.fromUserId]?.displayName ?: "Unknown"

                    FriendLedgerItem.SettlementItem(
                        id = settlement.id,
                        amount = settlement.amount,
                        currency = settlement.currency,
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

    fun getFriendIdentity(friendId: String): Flow<com.splitease.data.identity.model.ResolvedParticipant> {
        return combine(
            personDao.getPersonByIdFlow(friendId),
            userDao.getUser(friendId)
        ) { _, _ ->
            identityResolver.resolveIdeally(friendId)
        }
    }
}
