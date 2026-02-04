package com.splitease.data.repository

import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.entities.Person
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.firstOrNull

/**
 * Repository for managing Person identities in the SplitEase system.
 *
 * ## Sprint 29C: Person-First Architecture
 * This repository implements the core identity healing and phantom creation logic introduced
 * in Sprint 29C. It ensures that every User has a corresponding Person identity, enabling
 * cross-device deterministic resolution and preventing identity fragmentation.
 *
 * ## Key Responsibilities
 * - **Deterministic Healing**: Ensures a Person exists for any User via [ensurePerson].
 * - **Phantom Creation**: Creates ledger-backed phantom users and their Person identities.
 * - **Identity Resolution**: Provides flows for querying Person data by ID.
 *
 * ## Invariants (Sprint 29C)
 * - **Person-First Principle**: No domain writes (Expense, Settlement, Member) occur without
 *   a valid `personId`. This repository is the authoritative source for healing missing links.
 * - **Deterministic IDs**: Synthetic Person IDs are generated using `UUID.nameUUIDFromBytes`
 *   to ensure cross-device convergence. These IDs are marked with `isSynthetic = true`.
 * - **Non-Destructive**: Person records are never deleted. Duplicates are shadowed via
 *   the migration coordinator (Sprint 29C-4).
 *
 * ## Safety Properties
 * - **Idempotent**: Calling [ensurePerson] multiple times for the same User returns the
 *   same Person ID across all devices.
 * - **Replay-Safe**: Deterministic ID generation ensures that ledger replay produces
 *   identical Person records regardless of operation order.
 * - **No Ledger Mutation**: This repository only reads from and writes to the local database.
 *   Ledger operations are emitted separately by callers (e.g., ViewModels).
 *
 * @see Person
 * @see PersonDao
 * @since Sprint 29C-2 (Deterministic Identity Healing)
 */
@Singleton
class PersonRepositoryImpl @Inject constructor(
    private val personDao: PersonDao,
    private val userDao: com.splitease.data.local.dao.UserDao,
    private val userRepository: javax.inject.Provider<UserRepository>
) : PersonRepository {

    override fun getAllPersons(): Flow<List<Person>> {
        return personDao.getAllPersons()
    }

    override fun getPerson(id: String): Flow<Person?> {
        return personDao.getPersonByIdFlow(id)
    }

    /**
     * Ensures a Person identity exists for the given User ID.
     *
     * ## Sprint 29C-2: Deterministic Identity Healing
     * This method implements the core healing logic that prevents null `personId` writes.
     * It is called by all domain repositories (Expense, Settlement, Group) before persisting
     * any participant references.
     *
     * ## Resolution Strategy
     * 1. **Existing Link**: Returns the Person if `linkedUserId` already points to one.
     * 2. **Deterministic Fallback**: Generates a synthetic Person using a deterministic UUID
     *    derived from the User ID. This ensures cross-device convergence.
     * 3. **Local Healing**: Persists the synthetic Person locally with `isSynthetic = true`.
     *
     * ## Deterministic ID Generation
     * ```kotlin
     * UUID.nameUUIDFromBytes("Person:$userId".toByteArray(StandardCharsets.UTF_8))
     * ```
     * This ensures that:
     * - Device A and Device B generate the same Person ID for the same User.
     * - The synthetic Person can later be merged with a "real" Person via migration (29C-4).
     *
     * ## Idempotency Guarantee
     * - Multiple calls with the same `userId` return the same Person ID.
     * - Safe to call during every write operation without performance penalty (DB lookup is fast).
     *
     * ## Migration Context (Sprint 29C-4)
     * Synthetic Persons created by this method are later merged into canonical Persons by
     * [IdentityMigrationCoordinator]. The migration prefers "real" Persons (isSynthetic=false)
     * over synthetic ones, ensuring that user-created identities take precedence.
     *
     * @param userId The User ID to resolve a Person for.
     * @return The existing or newly created Person identity.
     * @see Person.isSynthetic
     * @see IdentityMigrationCoordinator
     * @since Sprint 29C-2
     */
    override suspend fun ensurePerson(userId: String): Person {
        // 1. Check existing direct link
        val existing = personDao.getPersonByLinkedUserId(userId)
        if (existing != null) return existing

        // 2. Deterministic Fallback (Option B: Transitional Determinism)
        val deterministicId = java.util.UUID.nameUUIDFromBytes("Person:$userId".toByteArray(java.nio.charset.StandardCharsets.UTF_8)).toString()
        
        // Check if we already created this synthetic person
        val byId = personDao.getPersonById(deterministicId)
        if (byId != null) return byId

        // 3. Heal locally
        // We attempt to get the user name if available
        val user = userDao.getUser(userId).firstOrNull()
        val name = user?.name ?: "Unknown"

        val person = Person(
            id = deterministicId,
            displayName = name,
            linkedUserId = userId,
            createdAt = System.currentTimeMillis(),
            isSynthetic = true
        )
        
        personDao.upsertPerson(person)
        return person
    }

    /**
     * Creates a phantom Person for a non-registered participant (e.g., phone contact).
     *
     * ## Sprint 29C-3: Phantom Creation Strategy
     * This method implements the ledger-backed phantom creation flow. It ensures that:
     * 1. A phantom User is created and emitted to the ledger (via [UserRepository.createPhantomUser]).
     * 2. A corresponding Person identity is immediately created via [ensurePerson].
     *
     * ## Ledger-First Guarantee
     * Unlike Sprint 29A/29B, phantom creation now emits a `USER.CREATE` ledger operation
     * **before** returning the Person ID. This ensures:
     * - Cross-device visibility: Other devices will replay the User and create the same Person.
     * - No orphan Persons: Every Person is backed by a User in the ledger.
     *
     * ## Deterministic Convergence
     * Because [ensurePerson] uses deterministic ID generation, all devices will create the
     * same Person ID for the phantom User, even if they process the ledger operation independently.
     *
     * ## Migration Context (Sprint 29C-4)
     * If a phantom User is later "upgraded" to a real User (e.g., they register), the migration
     * coordinator will merge duplicate Persons, preferring the real one over the phantom.
     *
     * @param name The display name for the phantom participant.
     * @return The Person ID of the newly created phantom.
     * @see UserRepository.createPhantomUser
     * @see ensurePerson
     * @since Sprint 29C-3
     */
    override suspend fun createPhantomPerson(name: String, email: String?, phone: String?): String {
        // Delegate to UserRepository to ensure a synced User exists
        val userId = userRepository.get().createPhantomUser(name, email, phone)
        
        // Immediately ensure a Person exists for this User
        val person = ensurePerson(userId)
        
        return person.id
    }
}
