package com.splitease.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Signup : Screen("signup")
    object Dashboard : Screen("dashboard")
    object Groups : Screen("groups")
    object Activity : Screen("activity")
    object Account : Screen("account")
    
    data class AddExpense(val groupId: String) : Screen("add_expense/{groupId}") {
        companion object {
            const val route = "add_expense/{groupId}"
            fun createRoute(groupId: String) = "add_expense/$groupId"
        }
    }
}
