package com.hqrecorder.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hqrecorder.app.ui.home.HomeScreen
import com.hqrecorder.app.ui.list.RecordingListScreen
import com.hqrecorder.app.ui.settings.SettingsScreen

@Composable
fun HqRecorderNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Destination.Home.route) {
        composable(Destination.Home.route) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Destination.Settings.route) },
                onOpenRecordings = { navController.navigate(Destination.RecordingList.route) }
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Destination.RecordingList.route) {
            RecordingListScreen(onBack = { navController.popBackStack() })
        }
    }
}
