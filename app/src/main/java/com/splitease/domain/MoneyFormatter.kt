package com.splitease.domain

import java.math.BigDecimal
import java.text.DecimalFormat

/**
 * Formats monetary amounts for display.
 *
 * Uses absolute value - color conveys direction (positive/negative).
 * No double signs like -₹100 or ₹-100.
 */
object MoneyFormatter {

    private val formatter = DecimalFormat("#,##0.00")

    /**
     * Formats amount as ₹X,XXX.XX using absolute value.
     * Color in UI conveys whether user owes or is owed.
     */
    fun format(amount: BigDecimal): String {
        return "₹${formatter.format(amount.abs())}"
    }
}
