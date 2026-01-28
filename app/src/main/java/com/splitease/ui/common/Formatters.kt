package com.splitease.ui.common

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Presentation-layer formatters for Date and Money.
 * Logic is strictly display-only.
 */
object Formatters {
    /**
     * Formats money with symbol, space, and always 2 decimal places.
     * Example: "₹ 1,200.50"
     */
    fun formatMoney(amount: BigDecimal, currencyCode: String = "₹"): String {
        // Instantiate per call for thread safety
        val currencyFormat = DecimalFormat("#,##0.00")
        return "$currencyCode ${currencyFormat.format(amount)}"
    }

    /**
     * Formats date as "Today", "Yesterday", or "dd MMM yyyy".
     * No timestamps.
     */
    fun formatDateHeader(timestamp: Long): String {
        // Instantiate per call for thread safety
        val standardDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        
        // Using simple relative logic for grouping headers
        return if (android.text.format.DateUtils.isToday(timestamp)) {
            "Today"
        } else if (android.text.format.DateUtils.isToday(timestamp + TimeUnit.DAYS.toMillis(1))) {
            "Yesterday"
        } else {
            standardDateFormat.format(Date(timestamp))
        }
    }
}
