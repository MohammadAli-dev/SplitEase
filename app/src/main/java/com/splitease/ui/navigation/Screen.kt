package com.splitease.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Signup : Screen("signup")
    object Dashboard : Screen("dashboard")
    object Groups : Screen("groups")
    object Activity : Screen("activity")
    object Account : Screen("account")

    data class GroupDetail(val groupId: String) : Screen("group_detail/{groupId}") {
        companion object {
            const val route = "group_detail/{groupId}"
            fun createRoute(groupId: String) = "group_detail/$groupId"
        }
    }
    
    data class AddExpense(val groupId: String) : Screen("add_expense/{groupId}") {
        companion object {
            const val route = "add_expense/{groupId}"
            fun createRoute(groupId: String) = "add_expense/$groupId"
        }
    }
}
