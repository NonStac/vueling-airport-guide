package com.nonstac.airportguide.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nonstac.airportguide.ui.screens.login.LoginScreen
import com.nonstac.airportguide.ui.screens.map.MapScreen
import com.nonstac.airportguide.ui.screens.register.RegisterScreen
import com.nonstac.airportguide.ui.screens.tickets.TicketsScreen
import com.nonstac.airportguide.ui.screens.chat.ChatScreen
import com.nonstac.airportguide.ui.screens.tickets.TicketsViewModel

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Register : Screen("register")
    object Tickets : Screen("tickets/{username}") {
        fun createRoute(username: String) = "tickets/$username"
    }
    object Map : Screen("map/{username}") {
        fun createRoute(username: String) = "map/$username"
    }
    object Chat : Screen("chat/{ticketId}") {
        fun createRoute(ticketId: String) = "chat/$ticketId"
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
            val ticketsViewModel: TicketsViewModel = viewModel(factory = TicketsViewModel.provideFactory(username))

            TicketsScreen(
                username = username,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToChat = { ticketId ->
                    navController.navigate(Screen.Chat.createRoute(ticketId))
                },
                ticketsViewModel = ticketsViewModel
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

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { username ->
                    navController.navigate(Screen.Map.createRoute(username)) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = { username ->
                    navController.navigate(Screen.Map.createRoute(username)) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Map.route) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: "User"
            MapScreen(
                username = username,
                onNavigateToTickets = {
                    navController.navigate(Screen.Tickets.createRoute(username))
                }
            )
        }

        // Updated TicketsScreen composable to add navigation to Chat
        composable(Screen.Tickets.route) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username") ?: "User"
            TicketsScreen(
                username = username,
                onNavigateBack = { navController.popBackStack() },
                // Add navigation action for chat
                onNavigateToChat = { ticketId ->
                    navController.navigate(Screen.Chat.createRoute(ticketId))
                }
            )
        }

        // Add ChatScreen composable
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("ticketId") { type = NavType.StringType })
        ) { backStackEntry ->
            val ticketId = backStackEntry.arguments?.getString("ticketId") ?: "unknown_ticket"
            ChatScreen(
                ticketId = ticketId, // Pass ticketId
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}