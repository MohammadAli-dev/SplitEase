package com.splitease.ui.navigation

import androidx.compose.foundation.layout.padding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.splitease.ui.account.AccountScreen
import com.splitease.ui.activity.ActivityScreen
import com.splitease.ui.dashboard.DashboardScreen
import com.splitease.ui.expense.AddExpenseScreen
import com.splitease.ui.groups.CreateGroupScreen
import com.splitease.ui.groups.GroupDetailScreen
import com.splitease.ui.groups.GroupListScreen
import com.splitease.ui.sync.SyncIssuesScreen

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    
    val items = listOf(
        BottomNavItem(Screen.Dashboard.route, "Dashboard", Icons.Default.Home),
        BottomNavItem(Screen.Groups.route, "Groups", Icons.AutoMirrored.Filled.List),
        BottomNavItem(Screen.Activity.route, "Activity", Icons.Default.Notifications),
        BottomNavItem(Screen.Account.route, "Account", Icons.Default.AccountCircle)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    
    // Determine if bottom nav should be shown (only on main tabs)
    val showBottomBar = currentDestination?.route in items.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = androidx.compose.ui.Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen()
            }
            composable(Screen.Groups.route) {
                GroupListScreen(
                    onNavigateToGroupDetail = { groupId ->
                        navController.navigate(Screen.GroupDetail.createRoute(groupId))
                    },
                    onNavigateToCreateGroup = {
                        navController.navigate(Screen.CreateGroup.route)
                    }
                )
            }
            composable(Screen.Activity.route) {
                ActivityScreen()
            }
            composable(Screen.Account.route) {
                AccountScreen()
            }
            composable(
                route = Screen.GroupDetail.route,
                arguments = listOf(androidx.navigation.navArgument("groupId") { 
                    type = androidx.navigation.NavType.StringType 
                })
            ) {
                GroupDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAddExpense = { id ->
                        navController.navigate(Screen.AddExpense.createRoute(id))
                    },
                    onNavigateToEditExpense = { groupId, expenseId ->
                        navController.navigate(Screen.EditExpense.createRoute(groupId, expenseId))
                    }
                )
            }
            composable(
                route = Screen.AddExpense.route,
                arguments = listOf(androidx.navigation.navArgument("groupId") { 
                    type = androidx.navigation.NavType.StringType 
                })
            ) {
                AddExpenseScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onExpenseSaved = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.EditExpense.route,
                arguments = listOf(
                    androidx.navigation.navArgument("groupId") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("expenseId") { type = androidx.navigation.NavType.StringType }
                )
            ) {
                AddExpenseScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onExpenseSaved = { navController.popBackStack() }
                )
            }
            composable(Screen.CreateGroup.route) {
                CreateGroupScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.SyncIssues.route) {
                SyncIssuesScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
