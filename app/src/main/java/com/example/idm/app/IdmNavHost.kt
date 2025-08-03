package com.example.idm.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import com.example.idm.features.download.DownloadManagerScreen
import com.example.idm.features.download.downloadManagerScreen

@Composable
fun IdmNavHost(appState: IdmAppState) {
    val navController = appState.navController
    NavHost(
        navController = navController,
        startDestination = DownloadManagerScreen,
    ) {
        downloadManagerScreen()
    }
}