package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.IdValuePair
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
     * Fetches group names for the given group IDs as id/value pairs.
     *
     * @param ids The group IDs to look up.
     * @return A list of `IdValuePair` containing `id` and `value` (the group's name) for each found group; groups not present in the table are omitted.
     */
    @Query("SELECT id, name AS value FROM expense_groups WHERE id IN (:ids)")
    suspend fun getNamesByIds(ids: List<String>): List<IdValuePair>

    // ========== Phantom Merge Operations ==========

    /**
     * Reassigns group memberships from one user ID to another to merge a phantom identity into a real one.
     *
     * @param oldUserId The phantom user ID to replace.
     * @param newUserId The real user ID to assign to memberships.
     */
    @Query("UPDATE group_members SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun updateMemberUserId(oldUserId: String, newUserId: String)
}