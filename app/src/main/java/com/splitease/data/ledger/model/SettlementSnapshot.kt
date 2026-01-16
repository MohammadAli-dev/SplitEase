package com.splitease.data.ledger.model

import java.math.BigDecimal

/**
 * Canonical snapshot of a Settlement entity for ledger persistence.
 *
 * Contains all data required to reconstruct the Settlement without external reads.
 */
data class SettlementSnapshot(
    val schemaVersion: Int = 1,
    val id: String,
    val groupId: String,
    val fromUserId: String,
    val toUserId: String,
    val amount: BigDecimal,
    /** Epoch millis (from Date.time) */
    val date: Long,
    val createdByUserId: String,
    val lastModifiedByUserId: String,
    val updatedAt: Long,
    val deletedAt: Long?
)
