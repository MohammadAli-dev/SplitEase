package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: Group)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMember)

    @Query("""
        SELECT * FROM expense_groups 
        INNER JOIN group_members ON expense_groups.id = group_members.groupId 
        WHERE group_members.userId = :userId
    """)
    fun getGroupsForUser(userId: String): Flow<List<Group>>

    @Query("SELECT * FROM expense_groups ORDER BY name ASC")
    fun getAllGroups(): Flow<List<Group>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun getGroupMembers(groupId: String): Flow<List<GroupMember>>

    @Query("SELECT * FROM expense_groups WHERE id = :groupId")
    fun getGroup(groupId: String): Flow<Group?>
}
