package com.example.idm.features.download

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
object DownloadManagerScreen

fun NavGraphBuilder.downloadManagerScreen() {
    composable<DownloadManagerScreen> {
        DownloadScreen(

        )
    }
}