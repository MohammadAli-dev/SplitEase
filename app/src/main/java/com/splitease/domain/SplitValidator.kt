package com.splitease.domain

import java.math.BigDecimal
import java.math.RoundingMode

sealed class SplitValidationResult {
    object Valid : SplitValidationResult()
    data class Invalid(val reason: String) : SplitValidationResult()
}

object SplitValidator {

    fun validateSplit(total: BigDecimal, splits: List<BigDecimal>): SplitValidationResult {
        val sum = splits.fold(BigDecimal.ZERO) { acc, curr -> acc.add(curr) }
        
        // Allow a tiny margin of error if floating point noise, but we use BigDecimal so should be exact.
        // However, if we split 100 / 3 = 33.33 * 3 = 99.99. 
        // Logic: The sum of splits MUST equal total strictly.
        // If "Equal Split" generated the splits, they should sum up.
        // If "Exact Amount" user input, they must sum up.
        
        return if (sum.compareTo(total) == 0) {
            SplitValidationResult.Valid
        } else {
            SplitValidationResult.Invalid("Splits sum ($sum) does not equal total ($total)")
        }
    }

    /**
     * Calculates equal splits for a given total and member count.
     * Remainder is assigned to the first member (index 0).
     */
    fun calculateEqualSplit(total: BigDecimal, memberCount: Int): List<BigDecimal> {
        if (memberCount <= 0) return emptyList()

        val count = BigDecimal(memberCount)
        val baseAmount = total.divide(count, 2, RoundingMode.FLOOR) // e.g. 100/3 = 33.33
        
        val totalAllocated = baseAmount.multiply(count) // 33.33 * 3 = 99.99
        val remainder = total.subtract(totalAllocated) // 100 - 99.99 = 0.01

        val result = MutableList(memberCount) { baseAmount }
        
        // Add remainder to the first person (Deterministic rule)
        // Remainder will likely be 0.01 or similar small unit if any.
        // Since we scale to 2, remainder increments by 0.01.
        // Actually, we should check if remainder is > 0.
        // But simply adding remainder to result[0] works as long as remainder is a multiple of 0.01.
        // Which it is, because baseAmount is scale 2.
        
        if (remainder.compareTo(BigDecimal.ZERO) > 0) {
            result[0] = result[0].add(remainder)
        }

        return result
    }
}
