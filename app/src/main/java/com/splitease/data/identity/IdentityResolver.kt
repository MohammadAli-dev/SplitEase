package com.splitease.data.identity

import com.splitease.data.identity.model.ResolvedParticipant
import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.dao.UserDao
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single Source of Truth for Identity Resolution.
 *
 * Responsibility: Resolve canonical `Person` and `User` entities into UI-ready `ResolvedParticipant` models.
 *
 * Rules:
 * 1. Resolution Order: Person (Authoritative) -> User (Legacy) -> Fallback.
 * 2. Determinism: Batch resolution must be idempotent and order-independent.
 * 3. Layering: Must ONLY be called by Repositories. NEVER by ViewModels or UI.
 */
@Singleton
class IdentityResolver @Inject constructor(
    private val personDao: PersonDao,
    private val userDao: UserDao,
    private val userContext: UserContext
) {

    /**
     * Resolves a single identity by ID.
     *
     * @param personId The authoritative Person UUID (nullable).
     * @param userId The legacy User ID (nullable).
     * @return [ResolvedParticipant] with stable ID and display name.
     */
    suspend fun resolve(personId: String?, userId: String?): ResolvedParticipant {
        val currentUserId = userContext.userId.firstOrNull() ?: ""

        // 1. Authoritative: Person Logic
        if (personId != null) {
            var person = personDao.getPersonById(personId)
            
            // Re-resolve if shadowed (follows the merge link)
            if (person?.shadowedById != null) {
                person = personDao.getPersonById(person.shadowedById!!)
            }

            if (person != null) {
                val isMe = person.linkedUserId == currentUserId
                return ResolvedParticipant(
                    stableId = person.id,
                    displayName = if (isMe) "You" else person.displayName,
                    avatarUrl = null,
                    isCurrentUser = isMe,
                    isGuest = person.linkedUserId == null
                )
            }
        }

        // 2. Legacy: User Logic
        if (userId != null) {
            // Check if this User ID is actually linked to a Person (Dual-Read Fallback)
            val linkedPerson = personDao.getPersonByLinkedUserId(userId)
            if (linkedPerson != null) {
                val isMe = linkedPerson.linkedUserId == currentUserId
                return ResolvedParticipant(
                    stableId = linkedPerson.id,
                    displayName = if (isMe) "You" else linkedPerson.displayName,
                    avatarUrl = null,
                    isCurrentUser = isMe,
                    isGuest = false
                )
            }

            // Raw User Lookup
            val user = userDao.getUserById(userId)
            if (user != null) {
                val isMe = user.id == currentUserId
                return ResolvedParticipant(
                    stableId = user.id,
                    displayName = if (isMe) "You" else user.name,
                    avatarUrl = user.profileUrl,
                    isCurrentUser = isMe,
                    isGuest = false
                )
            }

            // Self Check (if User not in DB but matches Context)
            if (userId == currentUserId) {
                return ResolvedParticipant(
                    stableId = userId,
                    displayName = "You",
                    avatarUrl = null,
                    isCurrentUser = true,
                    isGuest = false
                )
            }
        }

        // 3. Fallback
        val fallbackId = personId ?: userId ?: "unknown"
        return ResolvedParticipant(
            stableId = fallbackId, // Deterministic synthetic ID
            displayName = "Unknown",
            avatarUrl = null,
            isCurrentUser = false,
            isGuest = true
        )
    }

    /**
     * Batch resolves a set of identities efficiently.
     *
     * Invariant: Output is deterministic regardless of input order.
     */
    suspend fun resolveBatch(
        participants: List<Pair<String?, String?>> // List of (personId, userId)
    ): Map<String, ResolvedParticipant> {
        // Optimization: Could pre-fetch ALL persons/users involved in one query.
        // For now, simple iteration to ensure correctness first. Data layer caching handles perf.
        
        val results = mutableMapOf<String, ResolvedParticipant>()
        
        participants.forEach { (pid, uid) ->
            val resolved = resolve(pid, uid)
            // Key by whatever ID provided the resolution trigger.
            // Ideally, callers map their raw IDs to this result.
            // We return map keyed by stableId for deduping.
            results[resolved.stableId] = resolved
        }
        
        return results
    }
    
    /**
     * Helper to resolve by a single ID string which could be either PersonId OR UserId.
     * Checks both tables. Priority: Person -> User.
     */
    suspend fun resolveIdeally(id: String): ResolvedParticipant {
        // Try as Person ID first
        val asPerson = resolve(personId = id, userId = null)
        if (asPerson.displayName != "Unknown") return asPerson
        
        // Try as User ID
        val asUser = resolve(personId = null, userId = id)
        return asUser
    }


    /**
     * Batch resolves identities where the input is a list of generic String IDs (could be User or Person IDs).
     *
     * @return Map where Key = Input ID (as provided), Value = ResolvedParticipant.
     * This preserves the mapping for callers who don't know the stable ID yet.
     */
    suspend fun resolveBatchByUserId(ids: List<String>): Map<String, ResolvedParticipant> {
        val results = mutableMapOf<String, ResolvedParticipant>()
        ids.forEach { id ->
            // Use resolveIdeally to check both tables
            results[id] = resolveIdeally(id)
        }
        return results
    }
}
