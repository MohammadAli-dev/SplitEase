package com.splitease.domain

import java.math.BigDecimal
import java.math.RoundingMode

sealed class SplitValidationResult {
    object Valid : SplitValidationResult()
    data class Invalid(val reason: String) : SplitValidationResult()
}

/**
 * Split calculation and validation utilities.
 * All amounts use 2 decimal places with HALF_UP rounding.
 * Remainder is assigned to first participant (sorted order).
 */
object SplitValidator {

    private val HUNDRED = BigDecimal("100")

    // ============ Validation ============

    fun validateParticipants(participants: List<String>): SplitValidationResult {
        return if (participants.isEmpty()) {
            SplitValidationResult.Invalid("Select at least one participant")
        } else {
            SplitValidationResult.Valid
        }
    }

    fun validateAmount(amount: BigDecimal): SplitValidationResult {
        return if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            SplitValidationResult.Invalid("Amount must be greater than 0")
        } else {
            SplitValidationResult.Valid
        }
    }

    fun validateExactSum(amounts: Map<String, BigDecimal>, total: BigDecimal): SplitValidationResult {
        val sum = amounts.values.fold(BigDecimal.ZERO) { acc, curr -> acc.add(curr) }
        return if (sum.compareTo(total) == 0) {
            SplitValidationResult.Valid
        } else {
            SplitValidationResult.Invalid("Sum (₹$sum) must equal total (₹$total)")
        }
    }

    fun validatePercentageSum(percentages: Map<String, BigDecimal>): SplitValidationResult {
        val sum = percentages.values.fold(BigDecimal.ZERO) { acc, curr -> acc.add(curr) }
        return if (sum.compareTo(HUNDRED) == 0) {
            SplitValidationResult.Valid
        } else {
            SplitValidationResult.Invalid("Percentages must sum to 100% (currently $sum%)")
        }
    }

    fun validateSharesSum(shares: Map<String, Int>): SplitValidationResult {
        val hasInvalid = shares.values.any { it < 1 }
        if (hasInvalid) {
            return SplitValidationResult.Invalid("Each share must be at least 1")
        }
        val total = shares.values.sum()
        return if (total > 0) {
            SplitValidationResult.Valid
        } else {
            SplitValidationResult.Invalid("Total shares must be greater than 0")
        }
    }

    // ============ Calculations ============

    /**
     * Equal split: divide total evenly, remainder to first participant.
     */
    fun calculateEqualSplit(
        total: BigDecimal,
        participants: List<String>
    ): Map<String, BigDecimal> {
        if (participants.isEmpty()) return emptyMap()
        
        val count = BigDecimal(participants.size)
        val baseAmount = total.divide(count, 2, RoundingMode.FLOOR)
        val totalAllocated = baseAmount.multiply(count)
        val remainder = total.subtract(totalAllocated)

        val result = mutableMapOf<String, BigDecimal>()
        participants.forEachIndexed { index, userId ->
            result[userId] = if (index == 0 && remainder.compareTo(BigDecimal.ZERO) > 0) {
                baseAmount.add(remainder)
            } else {
                baseAmount
            }
        }
        return result
    }

    /**
     * Exact split: user specifies exact amounts per participant.
     * Returns as-is (pass-through for consistency).
     */
    fun calculateExactSplit(amounts: Map<String, BigDecimal>): Map<String, BigDecimal> {
        return amounts.mapValues { it.value.setScale(2, RoundingMode.HALF_UP) }
    }

    /**
     * Percentage split: convert percentages to amounts.
     * Remainder assigned to first participant.
     */
    fun calculatePercentageSplit(
        total: BigDecimal,
        percentages: Map<String, BigDecimal>
    ): Map<String, BigDecimal> {
        if (percentages.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, BigDecimal>()
        var allocated = BigDecimal.ZERO

        val sortedParticipants = percentages.keys.sorted()
        sortedParticipants.forEachIndexed { index, userId ->
            val pct = percentages[userId] ?: BigDecimal.ZERO
            val amount = total.multiply(pct).divide(HUNDRED, 2, RoundingMode.FLOOR)
            result[userId] = amount
            allocated = allocated.add(amount)
        }

        // Assign remainder to first participant
        val remainder = total.subtract(allocated)
        if (remainder.compareTo(BigDecimal.ZERO) > 0 && sortedParticipants.isNotEmpty()) {
            val first = sortedParticipants.first()
            result[first] = result[first]!!.add(remainder)
        }

        return result
    }

    /**
     * Shares split: divide total proportionally by shares.
     * Remainder assigned to first participant.
     */
    fun calculateSharesSplit(
        total: BigDecimal,
        shares: Map<String, Int>
    ): Map<String, BigDecimal> {
        if (shares.isEmpty()) return emptyMap()

        val totalShares = shares.values.sum()
        if (totalShares <= 0) return emptyMap()

        val result = mutableMapOf<String, BigDecimal>()
        var allocated = BigDecimal.ZERO

        val sortedParticipants = shares.keys.sorted()
        sortedParticipants.forEach { userId ->
            val share = shares[userId] ?: 0
            val amount = total.multiply(BigDecimal(share))
                .divide(BigDecimal(totalShares), 2, RoundingMode.FLOOR)
            result[userId] = amount
            allocated = allocated.add(amount)
        }

        // Assign remainder to first participant
        val remainder = total.subtract(allocated)
        if (remainder.compareTo(BigDecimal.ZERO) > 0 && sortedParticipants.isNotEmpty()) {
            val first = sortedParticipants.first()
            result[first] = result[first]!!.add(remainder)
        }

        return result
    }

    // ============ Legacy (for backward compatibility) ============

    @Deprecated("Use Map-based calculateEqualSplit instead")
    fun calculateEqualSplit(total: BigDecimal, memberCount: Int): List<BigDecimal> {
        if (memberCount <= 0) return emptyList()
        val participants = (0 until memberCount).map { "user_$it" }
        return calculateEqualSplit(total, participants).values.toList()
    }

    @Deprecated("Use type-specific validation instead")
    fun validateSplit(total: BigDecimal, splits: List<BigDecimal>): SplitValidationResult {
        val sum = splits.fold(BigDecimal.ZERO) { acc, curr -> acc.add(curr) }
        return if (sum.compareTo(total) == 0) {
            SplitValidationResult.Valid
        } else {
            SplitValidationResult.Invalid("Splits sum ($sum) does not equal total ($total)")
        }
    }
}
