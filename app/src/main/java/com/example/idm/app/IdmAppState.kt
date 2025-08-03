package com.example.idm.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

class IdmAppState(
    val navController: NavHostController
) {
}


@Composable
fun rememberAppState(): IdmAppState {
    val navController = rememberNavController()

    return IdmAppState(
        navController = navController
    )
}