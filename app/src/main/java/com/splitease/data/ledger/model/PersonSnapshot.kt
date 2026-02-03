package com.splitease.data.ledger.model

/**
 * Canonical snapshot of a [com.splitease.data.local.entities.Person] for ledger persistence.
 *
 * Used primarily in `PERSON.CREATE` operations.
 */
data class PersonSnapshot(
    val personId: String,
    val displayName: String,
    val createdAt: Long
)

/**
 * Canonical snapshot for linking a [com.splitease.data.local.entities.Person] 
 * to a [com.splitease.data.local.entities.User].
 *
 * Used in `PERSON.LINK_USER` operations.
 * Enforces one-to-one mapping in the domain.
 */
data class PersonLinkSnapshot(
    val personId: String,
    val userId: String
)
