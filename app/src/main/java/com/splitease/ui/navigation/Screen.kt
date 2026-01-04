package com.splitease.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Signup : Screen("signup")
    object Dashboard : Screen("dashboard")
    object Groups : Screen("groups")
    object Activity : Screen("activity")
    object Account : Screen("account")
    object CreateGroup : Screen("create_group")
    object SyncIssues : Screen("sync_issues")

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

    data class EditExpense(val groupId: String, val expenseId: String) : Screen("edit_expense/{groupId}/{expenseId}") {
        companion object {
            const val route = "edit_expense/{groupId}/{expenseId}"
            fun createRoute(groupId: String, expenseId: String) = "edit_expense/$groupId/$expenseId"
        }
    }
}
