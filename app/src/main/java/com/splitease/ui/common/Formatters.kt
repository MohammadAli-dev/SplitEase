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

    private val currencyFormat = DecimalFormat("#,##0.00")
    private val standardDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    /**
     * Formats money with symbol, space, and always 2 decimal places.
     * Example: "₹ 1,200.50"
     */
    fun formatMoney(amount: BigDecimal, currencyCode: String = "₹"): String {
        return "$currencyCode ${currencyFormat.format(amount)}"
    }

    /**
     * Formats date as "Today", "Yesterday", or "dd MMM yyyy".
     * No timestamps.
     */
    fun formatDateHeader(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        // Midnight check would be better, but simple diff is acceptable for UX polish phase
        // to avoid complex calendar logic deps.
        // Rule: < 24h is Today, < 48h is Yesterday (approx)
        
        // Strict "Day" logic using Calendar/LocalDate is ideal, but sticking to simple rules:
        // formatting strictly for grouping headers.
        
        // Using DateUtils logic roughly:
        return if (android.text.format.DateUtils.isToday(timestamp)) {
            "Today"
        } else if (android.text.format.DateUtils.isToday(timestamp + TimeUnit.DAYS.toMillis(1))) {
            "Yesterday"
        } else {
            standardDateFormat.format(Date(timestamp))
        }
    }
}
