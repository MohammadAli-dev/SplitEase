package com.splitease.data.repository

import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.domain.ActivityItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface ActivityRepository {
    fun getActivityFeed(): Flow<List<ActivityItem>>
}

@Singleton
class ActivityRepositoryImpl
@Inject
constructor(
    private val expenseDao: ExpenseDao,
    private val settlementDao: SettlementDao,
    private val groupDao: GroupDao
) : ActivityRepository {
    override fun getActivityFeed(): Flow<List<ActivityItem>> {
        return combine(
            expenseDao.getAllExpenses(),
            settlementDao.getAllSettlements(),
            groupDao.getAllGroups()
        ) { expenses, settlements, groups ->
            val expenseItems =
                expenses.map { expense ->
                    val groupName = 
                        if (expense.groupId == com.splitease.domain.PersonalGroupConstants.PERSONAL_GROUP_ID) {
                            // Invariant: PERSONAL_GROUP_ID is a virtual group and does not exist in expense_groups table
                            com.splitease.domain.PersonalGroupConstants.PERSONAL_GROUP_NAME
                        } else {
                            groups.find { it.id == expense.groupId }?.name ?: "Unknown Group"
                        }
                    ActivityItem.ExpenseAdded(
                        id = "expense_${expense.id}",
                        title = expense.title,
                        amount = expense.amount,
                        currency = expense.currency,
                        groupName = groupName,
                        groupId = expense.groupId, // Fixed usage
                        timestamp = expense.date.time
                    )
                }

            val settlementItems =
                settlements.map { settlement ->
                    val groupName =
                        if (settlement.groupId == com.splitease.domain.PersonalGroupConstants.PERSONAL_GROUP_ID) {
                            com.splitease.domain.PersonalGroupConstants.PERSONAL_GROUP_NAME
                        } else {
                            groups.find { it.id == settlement.groupId }?.name ?: "Unknown Group"
                        }
                    ActivityItem.SettlementCreated(
                        id = "settlement_${settlement.id}",
                        fromUserName = "You", // Placeholder until Auth
                        toUserName = "Friend", // Placeholder until Auth
                        amount = settlement.amount,
                        currency = "INR", // Default currency
                        groupName = groupName,
                        groupId = settlement.groupId, // Fixed usage
                        timestamp = settlement.date.time
                    )
                }

            // Group creation timestamp heuristic:
            // Group entity doesn't store creation time.
            // Use earliest activity (expense or settlement) time as a proxy, or 0 if empty.
            // This preserves the invariant: Activity feed is strict-ish chronological.
            val groupItems =
                groups.map { group ->
                    val earliestExpenseDate = expenses.filter { it.groupId == group.id }.minByOrNull { it.date }?.date?.time
                    val earliestSettlementDate = settlements.filter { it.groupId == group.id }.minByOrNull { it.date }?.date?.time
                    
                    val timestamp = listOfNotNull(earliestExpenseDate, earliestSettlementDate).minOrNull() ?: 0L
                    // Note: If timestamp is 0, it appears at bottom of feed.

                    ActivityItem.GroupCreated(
                        id = "group_${group.id}",
                        groupName = group.name,
                        timestamp = timestamp
                    )
                }

            (expenseItems + settlementItems + groupItems).sortedByDescending { it.timestamp }
        }
    }
}
