package com.splitease.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.splitease.ui.auth.LoginScreen
import com.splitease.ui.auth.SignupScreen
import com.splitease.ui.invite.ClaimInviteScreen

/**
 * Main navigation graph. Pure wiring - no IO, no side effects.
 *
 * @param navController External controller passed from MainActivity
 * @param startDestination Stable destination based on auth state
 */
@Composable
fun SplitEaseNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Login.route
) {
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
