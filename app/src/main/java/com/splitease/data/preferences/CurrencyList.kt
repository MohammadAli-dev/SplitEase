package com.splitease.data.preferences

/**
 * Static list of supported currencies.
 * Used for currency selection in Account screen.
 * 
 * Note: This is a static list — no exchange rates or conversions are implemented.
 */
object CurrencyList {
    val SUPPORTED = listOf(
        "USD",  // US Dollar
        "INR",  // Indian Rupee
        "EUR",  // Euro
        "GBP",  // British Pound
        "AUD",  // Australian Dollar
        "CAD",  // Canadian Dollar
        "SGD",  // Singapore Dollar
        "JPY",  // Japanese Yen
        "CNY",  // Chinese Yuan
        "AED"   // UAE Dirham
    )
}
