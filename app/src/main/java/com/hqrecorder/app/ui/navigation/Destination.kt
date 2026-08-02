package com.hqrecorder.app.ui.navigation

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Settings : Destination("settings")
    data object RecordingList : Destination("recordings")
}
