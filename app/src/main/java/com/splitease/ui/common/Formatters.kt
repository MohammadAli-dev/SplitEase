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
     * Maps ISO currency ISO codes (e.g., "INR") to symbols (e.g., "₹").
     * Example: "₹ 1,200.50"
     */
    fun formatMoney(amount: BigDecimal, currencyCode: String = "INR"): String {
        // Instantiate per call for thread safety
        val currencyFormat = DecimalFormat("#,##0.00")
        
        val symbol = when (currencyCode.uppercase(Locale.ROOT)) {
            "INR" -> "₹"
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            else -> {
                // Log warning for unknown currency code in debug builds (simulated here)
                // Fallback to the code itself if no symbol found, or just assume it's a symbol
                // if legacy data is passed (though we try to avoid that).
                // For safety in this sprint: if it looks like a code, use it.
                // If it's already a symbol (legacy "₹"), use it.
                currencyCode
            }
        }
        
        return "$symbol ${currencyFormat.format(amount)}"
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
