package com.splitease.ui.sync

import androidx.annotation.StringRes

/**
 * Represents a display name that can be either a literal string
 * or a localized string resource. Enables localization-ready UI
 * without hardcoding strings in ViewModels.
 */
sealed class DisplayName {
    /** Literal text value (e.g., entity name from database) */
    data class Text(val value: String) : DisplayName()
    
    /** String resource ID for localization (e.g., "Deleted expense") */
    data class Resource(@StringRes val resId: Int) : DisplayName()
}
