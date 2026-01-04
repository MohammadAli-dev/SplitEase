package com.splitease.data.local.entities

/**
 * Simple projection class for batch ID-value lookups.
 * Used by DAOs to return lightweight pairs for display name resolution.
 * Avoids @MapInfo/@MapColumn annotation complexity in Room 2.6+.
 */
data class IdValuePair(
    val id: String,
    val value: String
)
