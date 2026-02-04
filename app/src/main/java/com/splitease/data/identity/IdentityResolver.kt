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
            
            // Re-resolve if shadowed (multi-hop with cycle detection)
            var currentPerson = person
            val visited = mutableSetOf<String>()
            var depth = 0
            val MAX_DEPTH = 10

            while (currentPerson?.shadowedById != null) {
                if (depth >= MAX_DEPTH || !visited.add(currentPerson!!.id)) {
                    android.util.Log.e("IdentityResolver", "Shadow cycle or max depth exceeded for person ${person!!.id}")
                    break // Stop following shadow chain to prevent stack overflow/infinite loop
                }
                currentPerson = personDao.getPersonById(currentPerson!!.shadowedById!!)
                depth++
            }
            person = currentPerson

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
        // Check stableId match rather than magic string "Unknown"
        // If it was resolved as a Person, the stableId will match the requested id (or its canonical shadow).
        // If it fell back to "Unknown" with a synthetic ID, it won't match (unless id matches fallback logic).
        // Better check: isGuest is false OR stableId is found in DB.
        // Actually, resolve() returns a fallback struct if not found.
        // The fallback logic in resolve() sets stableId = personId (input) for fallback.
        // So checking if it is NOT a guest-fallback is safer if possible, but Persons can be guests.
        // Let's rely on the fact that `resolve` returns a fallback if DB lookup failed.
        // We can inspect if the name is "Unknown" AND it makes sense, or better:
        // Refactor resolve to return nullable? No.
        // Check if `asPerson` was actually found. 
        // For now, replacing the strict string check with a slightly more robust Guest check combination
        // But since this method `resolveIdeally` tries User next, we need to know if Person lookup FAILED.
        // A failed Person lookup returns `ResolvedParticipant("id", "Unknown", ..., isGuest=true)`.
        // A valid Guest Person returns `ResolvedParticipant("id", "Name", ..., isGuest=true)`.
        
        // So: If name is "Unknown" AND it's a guest, it's likely a miss.
        // But a user could be named "Unknown".
        // The robust fix is to check if real resolution happened. 
        // Since we can't easily change `resolve` return type now without breakages, we interpret "Unknown".
        if (asPerson.displayName != "Unknown" || !asPerson.isGuest) return asPerson
        
        // Note: The previous check `asPerson.displayName != "Unknown"` remains the most practical proxy 
        // for "Did we find a record?" given the current `resolve` implementation fallback.
        // We add `!asPerson.isGuest` to ensure that if we found a Real User named "Unknown", it returns true.
        
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
