package com.splitease.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date
import com.splitease.data.identity.IdentityConstants

/**
 * Settlement entity representing a payment between two users.
 *
 * **Currency Invariant**: All settlements inherit their currency from the group context.
 * The currency is determined at settlement creation time and captured as an immutable fact.
 * Currency must never be defaulted at runtime.
 */
@Entity(tableName = "settlements")
data class Settlement(
    @PrimaryKey val id: String,
    val groupId: String,
    val fromUserId: String,
    val toUserId: String,
    val amount: BigDecimal,
    /** Currency code (ISO 4217). Derived from group context at creation time. */
    val currency: String,
    val date: Date = Date(),
    val createdByUserId: String = IdentityConstants.LEGACY_USER_ID,
    val lastModifiedByUserId: String = IdentityConstants.LEGACY_USER_ID,
    /** Server-authoritative timestamp (epoch millis). 0 = never synced. */
    val updatedAt: Long = 0,
    /** Soft-delete timestamp (epoch millis). NULL = not deleted. */
    val deletedAt: Long? = null
)
