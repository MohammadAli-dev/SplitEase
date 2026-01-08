package com.splitease.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.splitease.ui.auth.LoginScreen
import com.splitease.ui.auth.SignupScreen
import com.splitease.ui.invite.ClaimInviteScreen

@Composable
fun SplitEaseNavGraph(startDestination: String = Screen.Login.route) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Login.route) {
            LoginScreen(
                onNavigateToSignup = { navController.navigate(Screen.Signup.route) },
                onLoginSuccess = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Signup.route) {
            SignupScreen(
                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                onSignupSuccess = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Dashboard.route) {
            MainScaffold()
        }
        composable(Screen.ClaimInvite.route) {
            ClaimInviteScreen(
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        // Keep ClaimInvite in backstack so user returns after login
                    }
                },
                onNavigateToDashboard = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.ClaimInvite.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
