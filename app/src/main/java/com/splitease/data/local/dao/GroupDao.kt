package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.GroupMember
import com.splitease.data.local.entities.User
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: Group)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMember)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembers(members: List<GroupMember>)

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

    @Query("""
        SELECT users.* FROM users
        INNER JOIN group_members ON users.id = group_members.userId
        WHERE group_members.groupId = :groupId
    """)
    fun getGroupMembersWithDetails(groupId: String): Flow<List<User>>

    /**
     * Delete a group by ID. Used for zombie elimination on failed INSERT sync.
     */
    @Query("DELETE FROM expense_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    /**
     * Batch fetch group names by IDs.
     * Returns Map<groupId, name> for efficient N+1 prevention.
     */
    @androidx.room.MapInfo(keyColumn = "id", valueColumn = "name")
    @Query("SELECT id, name FROM expense_groups WHERE id IN (:ids)")
    suspend fun getNamesByIds(ids: List<String>): Map<String, String>
}
