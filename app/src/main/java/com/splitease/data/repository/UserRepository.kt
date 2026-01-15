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
     * Emits list of all users.
     * 
     * NOTE: This returns raw data from the data source with no guarantee of ordering.
     * ViewModels consuming this flow MUST apply their own sorting (e.g., alphabetically).
     */
    fun getAllUsers(): Flow<List<User>>

    /**
     * Emits a specific user by ID.
     */
    fun getUser(userId: String): Flow<User?>

    /**
     * Creates a local "phantom" user.
     * 
     * This method is the single source of truth for generating new User IDs.
     * ViewModels and UI must NOT generate IDs themselves.
     * 
     * @param name The display name for the new user.
     * @param email Optional email address (metadata only, no auth/invite semantics).
     * @param phone Optional phone number (metadata only, no SMS/invite semantics).
     * @return The ID of the newly created user.
     */
    suspend fun createPhantomUser(name: String, email: String? = null, phone: String? = null): String
}
