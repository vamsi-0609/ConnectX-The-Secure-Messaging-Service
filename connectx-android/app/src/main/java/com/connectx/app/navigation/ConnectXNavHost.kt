package com.connectx.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.connectx.app.Greeting
import com.connectx.app.ui.showcase.DesignShowcaseScreen

object ConnectXDestinations {
    const val HOME = "home"

    // Temporary, N1.7-only destination used to manually verify a minimal
    // two-destination navigation flow (HOME -> NAVIGATION_TEST -> HOME).
    // Not a real ConnectX screen. Not consumed by any future-phase code.
    const val NAVIGATION_TEST = "navigation_test"

    // Temporary, N1.8-only destination hosting the design-system showcase.
    // Not a real ConnectX screen -- expected to be removed once N4 (Core App
    // Shell) replaces this temporary navigation scaffolding.
    const val DESIGN_SHOWCASE = "design_showcase"
}

@Composable
fun ConnectXNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = ConnectXDestinations.HOME,
        modifier = modifier
    ) {
        composable(ConnectXDestinations.HOME) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Greeting(name = "Vamsi")
                Button(onClick = { navController.navigate(ConnectXDestinations.NAVIGATION_TEST) }) {
                    Text("Go to Navigation Test")
                }
                Button(onClick = { navController.navigate(ConnectXDestinations.DESIGN_SHOWCASE) }) {
                    Text("Design System Showcase")
                }
            }
        }
        composable(ConnectXDestinations.NAVIGATION_TEST) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Navigation Test Destination")
                Button(onClick = { navController.popBackStack() }) {
                    Text("Back")
                }
            }
        }
        composable(ConnectXDestinations.DESIGN_SHOWCASE) {
            DesignShowcaseScreen(onBack = { navController.popBackStack() })
        }
    }
}
