package com.splitease.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitease.data.local.entities.Person
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the "persons" table.
 *
 * ## Principles
 * - **Immutability**: Once a person is created, they are never deleted.
 * - **Authority**: All queries use the canonical personId or linkedUserId.
 */
@Dao
interface PersonDao {
    /**
     * Inserts or updates a person identity.
     *
     * In Sprint 29A, this is purely for local persisting of ledger facts.
     * Use [OnConflictStrategy.REPLACE] to ensure idempotency.
     *
     * @param person The person entity to upsert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerson(person: Person)

    /**
     * Retrieves a person by their authoritative identity.
     *
     * @param id The canonical UUID of the person.
     * @return The Person record if found, null otherwise.
     */
    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getPersonById(id: String): Person?

    /**
     * Resolves a person by their linked user account.
     *
     * Useful for checking if the local user already has a "Self Person"
     * or for mapping authenticated users to their canonical person container.
     *
     * @param userId The ID of the registered User.
     * @return The linked Person if found, null otherwise.
     */
    @Query("SELECT * FROM persons WHERE linkedUserId = :userId")
    suspend fun getPersonByLinkedUserId(userId: String): Person?

    /**
     * Returns a stream of all persons in the system.
     */
    @Query("SELECT * FROM persons")
    fun getAllPersons(): Flow<List<Person>>
    
    // NO DELETE METHOD: People are never deleted to preserve ledger auditability.
}
