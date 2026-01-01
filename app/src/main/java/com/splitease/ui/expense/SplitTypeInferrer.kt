package com.splitease.ui.expense

import com.splitease.data.local.entities.ExpenseSplit
import java.math.BigDecimal

fun inferSplitType(splits: List<ExpenseSplit>, total: BigDecimal): SplitType {
    if (splits.isEmpty()) return SplitType.EQUAL

    // Check for equality
    val count = BigDecimal(splits.size)
    val expectedShare = total.divide(count, 2, java.math.RoundingMode.HALF_UP)
    
    // Allow small tolerance
    val allEqual = splits.all { 
        it.amount.subtract(expectedShare).abs() < BigDecimal("0.02") 
    }
    
    if (allEqual) return SplitType.EQUAL
    
    // Assume EXACT for now if not equal, as percentages aren't stored explicitly
    return SplitType.EXACT
}
