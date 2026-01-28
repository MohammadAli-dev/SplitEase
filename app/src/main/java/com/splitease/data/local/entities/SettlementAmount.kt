package com.splitease.data.local.entities

/**
 * Projection class for fetching settlement amount and currency.
 * Used for sync issues display to ensure correct currency formatting.
 */
data class SettlementAmount(
    val id: String,
    val value: String, // Amount as string
    val currency: String
)
