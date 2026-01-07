package com.splitease.domain

/**
 * Constants for the virtual "Non-Group Expense" container.
 * 
 * Invariant: "__personal__" is a virtual container for non-group expenses.
 * It does not represent a real group and must never appear in group screens.
 * Non-group expenses allow multiple participants without requiring a formal group.
 */
object PersonalGroupConstants {
    const val PERSONAL_GROUP_ID = "__personal__"
    const val PERSONAL_GROUP_NAME = "Non-Group Expense"
}
