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

    data class Reconciliation(val expenseId: String, val syncOpId: Int) : Screen("reconciliation/{expenseId}/{syncOpId}") {
        companion object {
            const val route = "reconciliation/{expenseId}/{syncOpId}"
            fun createRoute(expenseId: String, syncOpId: Int) = "reconciliation/$expenseId/$syncOpId"
        }
    }
    
    data class FriendDetail(val friendId: String) : Screen("friend_detail/{friendId}") {
        companion object {
            const val route = "friend_detail/{friendId}"
            fun createRoute(friendId: String) = "friend_detail/$friendId"
        }
    }
    
    data class PersonalLedger(val friendId: String) : Screen("personal_ledger/{friendId}") {
        companion object {
            const val route = "personal_ledger/{friendId}"
            fun createRoute(friendId: String) = "personal_ledger/$friendId"
        }
    }

    data class SettleUp(val friendId: String) : Screen("settle_up/{friendId}") {
        companion object {
            const val route = "settle_up/{friendId}"
            fun createRoute(friendId: String) = "settle_up/$friendId"
        }
    }
}
