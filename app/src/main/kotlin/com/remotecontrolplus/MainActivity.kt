package com.remotecontrolplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotecontrolplus.ui.MainViewModel
import com.remotecontrolplus.ui.diagnostics.DiagnosticsScreen
import com.remotecontrolplus.ui.discover.DiscoverScreen
import com.remotecontrolplus.ui.remote.RemoteScreen
import com.remotecontrolplus.ui.theme.RemoteControlPlusTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteControlPlusTheme {
                AppNavHost(viewModel)
            }
        }
    }
}

@Composable
private fun AppNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "remote") {
        composable("remote") {
            RemoteScreen(
                viewModel = viewModel,
                onAddDevice = { navController.navigate("discover") },
                onDiagnostics = { navController.navigate("diagnostics") },
            )
        }
        composable("diagnostics") {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
        composable("discover") {
            DiscoverScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
