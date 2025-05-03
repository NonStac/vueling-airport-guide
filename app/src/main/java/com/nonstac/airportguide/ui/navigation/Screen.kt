package com.nonstac.airportguide.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nonstac.airportguide.ui.screens.login.LoginScreen
import com.nonstac.airportguide.ui.screens.map.MapScreen
import com.nonstac.airportguide.ui.screens.register.RegisterScreen
import com.nonstac.airportguide.ui.screens.tickets.TicketsScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Register : Screen("register")
    object Tickets : Screen("tickets/{username}") { // Pass username as argument
        fun createRoute(username: String) = "tickets/$username"
    }
    object Map : Screen("map/{username}") { // Pass username
        fun createRoute(username: String) = "map/$username"
    }
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Login.route) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { username ->
                    // Navigate to Map screen after successful login
                    navController.navigate(Screen.Map.createRoute(username)) {
                        popUpTo(Screen.Login.route) { inclusive = true } // Clear login from back stack
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                }
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = { username ->
                    // Navigate to Map screen after successful registration
                    navController.navigate(Screen.Map.createRoute(username)) {
                        popUpTo(Screen.Login.route) { inclusive = true } // Clear login/register stack
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Tickets.route) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: "User"
            TicketsScreen(
                username = username,
                onNavigateBack = { navController.popBackStack() }
                // Add navigation to map if needed from here
            )
        }
        composable(Screen.Map.route) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: "User"
            MapScreen(
                username = username,
                onNavigateToTickets = { // Allow navigating to tickets from map screen
                    navController.navigate(Screen.Tickets.createRoute(username))
                }
            )
        }
    }
}