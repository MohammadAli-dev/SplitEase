package com.splitease.data.ledger.model



/**
 * Canonical snapshot of a Settlement entity for ledger persistence.
 *
 * Contains all data required to reconstruct the Settlement without external reads.
 *
 * **Currency Invariant**: The currency field captures the explicit unit of the amount
 * at the time of settlement creation. This field is derived from group context and
 * must never be null or defaulted. It ensures the ledger remains a self-contained,
 * replayable record of financial truth.
 */
data class SettlementSnapshot(
    val schemaVersion: Int = 1,
    val id: String,
    val groupId: String,
    val fromUserId: String,
    /**
     * Canonical Person ID of the payer.
     * Null for legacy ops; populated for new ops.
     */
    val fromPersonId: String? = null,
    val toUserId: String,
    /**
     * Canonical Person ID of the payee.
     * Null for legacy ops; populated for new ops.
     */
    val toPersonId: String? = null,
    val amount: String,
    /** Currency code (ISO 4217). Derived from group context at creation time. */
    val currency: String,
    /** Epoch millis (from Date.time) */
    val date: Long,
    val createdByUserId: String,
    val lastModifiedByUserId: String,
    val updatedAt: Long,
    val deletedAt: Long?
)
