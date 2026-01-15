package com.splitease.data.repository

import com.splitease.data.local.entities.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing User entities.
 * 
 * Responsible for creating phantom users and providing streams of user data.
 * All user ID generation MUST happen within this repository to ensure consistency.
 */
interface UserRepository {

    /**
 * Streams all User entities from the data source.
 *
 * The emitted lists reflect raw data with no guaranteed ordering; consumers must apply any required sorting (for example, alphabetically).
 *
 * @return A stream emitting lists of users from the data source; list order is not guaranteed.
 */
    fun getAllUsers(): Flow<List<User>>

    /**
 * Emits updates for the user with the specified ID, producing `null` if no such user exists.
 *
 * @param userId The user's unique identifier.
 * @return A Flow that emits the corresponding `User` or `null` when the user is not found; emits new values when the user's data changes.
 */
    fun getUser(userId: String): Flow<User?>

    /**
 * Create a local "phantom" user.
 *
 * This function is the single source of truth for generating new user IDs; callers (for example ViewModels or UI) must not generate IDs themselves.
 *
 * @param name The display name for the new user.
 * @param email Optional email address (metadata only; not used for authentication or invitations).
 * @param phone Optional phone number (metadata only; not used for SMS or invitations).
 * @return The ID of the newly created user.
 */
    suspend fun createPhantomUser(name: String, email: String? = null, phone: String? = null): String
}