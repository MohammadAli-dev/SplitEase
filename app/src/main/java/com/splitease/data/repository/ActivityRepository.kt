package com.splitease.data.repository

import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.dao.PersonDao
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
    private val groupDao: GroupDao,
    private val userDao: UserDao,
    private val personDao: PersonDao
) : ActivityRepository {
    override fun getActivityFeed(): Flow<List<ActivityItem>> {
        return combine(
            expenseDao.getAllExpenses(),
            settlementDao.getAllSettlements(),
            groupDao.getAllGroups(),
            userDao.getAllUsers(),
            personDao.getAllPersons()
        ) { expenses, settlements, groups, allUsers, allPersons ->
            // Build name discovery maps
            val userNames = allUsers.associate { it.id to it.name }
            val personNames = allPersons.associate { it.id to it.displayName }
            val personLinks = allPersons.filter { it.linkedUserId != null }.associate { it.linkedUserId!! to it.displayName }
            
            fun resolveName(id: String?, personId: String?): String {
                if (id == null && personId == null) return "Unknown"
                return personNames[personId] ?: userNames[id] ?: personLinks[id] ?: id?.take(8) ?: personId?.take(8) ?: "Unknown"
            }
            
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
                        fromUserName = resolveName(settlement.fromUserId, settlement.fromPersonId),
                        toUserName = resolveName(settlement.toUserId, settlement.toPersonId),
                        amount = settlement.amount,
                        currency = settlement.currency,
                        groupName = groupName,
                        groupId = settlement.groupId, 
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
