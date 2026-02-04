package com.splitease.data.repository

import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.entities.Person
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Access point for Person data.
 *
 * ## Sprint 29C: UI Integration
 * This repository powers the "Universal Person Picker" and ensures strict
 * adherence to the Single-Write invariant.
 */
interface PersonRepository {
    /**
     * Observes all persons in the system, ordered by name.
     */
    fun getAllPersons(): Flow<List<Person>>

    /**
     * Observes a specific person.
     * Note: Primarily for UI observation; selection flows should use snapshots.
     */
    fun getPerson(id: String): Flow<Person?>

    /**
     * Ensures a Person entity exists for the given User ID.
     *
     * ## Strategy: Transitional Determinism
     * 1. If a Person is already linked to this userId, returns it.
     * 2. Otherwise, generates a deterministic Person ID from the userId and 
     *    creates a local-only Person record to bridge the gap until full sync.
     *
     * TODO: This deterministic mapping is transitional and will be replaced 
     * by canonical Person IDs once Phantom Merge & Migration completes.
     *
     * @param userId The ID of the User to ensure identity for.
     * @return The existing or newly created Person.
     */
    suspend fun ensurePerson(userId: String): Person

    /**
     * Creates a new "Phantom" Person and ensures it is synced.
     *
     * In Sprint 29C, this delegates to UserRepository to create a synced
     * phantom User, then ensures a corresponding Person exists.
     *
     * @param name The display name for the new person.
     * @return The ID of the newly created person.
     */
    suspend fun createPhantomPerson(name: String): String
}


