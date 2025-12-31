package com.splitease.ui.navigation

import androidx.compose.foundation.layout.padding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
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
import com.splitease.ui.groups.GroupListScreen

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
        BottomNavItem(Screen.Groups.route, "Groups", Icons.Default.List),
        BottomNavItem(Screen.Activity.route, "Activity", Icons.Default.Notifications),
        BottomNavItem(Screen.Account.route, "Account", Icons.Default.AccountCircle)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

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
                GroupListScreen()
            }
            composable(Screen.Activity.route) {
                ActivityScreen()
            }
            composable(Screen.Account.route) {
                AccountScreen()
            }
            composable(
                route = Screen.AddExpense.route,
                arguments = listOf(androidx.navigation.navArgument("groupId") { 
                    type = androidx.navigation.NavType.StringType 
                })
            ) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                AddExpenseScreen(
                    groupId = groupId,
                    onExpenseSaved = { navController.popBackStack() }
                )
            }
        }
    }
}
