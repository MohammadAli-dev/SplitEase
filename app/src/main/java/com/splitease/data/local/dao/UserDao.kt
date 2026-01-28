package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.User
import com.splitease.data.local.entities.IdValuePair
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUser(user: User)

    /**
     * Upsert a user: Insert if not exists, Replace if exists.
     * Used by ClaimManager to insert the inviter (User A) when claiming an invite.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: User)

    @Query("SELECT * FROM users WHERE id = :userId")
    fun getUser(userId: String): Flow<User?>

    @Query("SELECT * FROM users")
    fun getAllUsers(): Flow<List<User>>

    /**
     * Emits the first user from the users table, or `null` when the table is empty.
     *
     * @return A Flow that emits the first stored [User], or `null` if no users exist. The Flow will emit again if the underlying data changes.
     */
    @Query("SELECT * FROM users ORDER BY id LIMIT 1")
    fun getAnyUser(): Flow<User?>
    /**
     * Removes the user with the given ID from the users table.
     *
     * Primarily used to perform phantom cleanup after a merge. Related
     * `connection_states` rows are removed automatically by the database
     * via foreign key cascade.
     *
     * @param userId The user's primary key ID to delete.
     */
    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteUser(userId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<User>)

    @Query("SELECT id FROM users")
    suspend fun getAllUserIdsSync(): List<String>

    @Query("SELECT id, name AS value FROM users WHERE id IN (:ids)")
    suspend fun getNamesByIds(ids: List<String>): List<IdValuePair>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserById(id: String): User?
}
