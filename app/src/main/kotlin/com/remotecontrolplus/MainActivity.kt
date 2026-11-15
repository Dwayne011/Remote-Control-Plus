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
import com.remotecontrolplus.core.Diagnostics
import com.remotecontrolplus.ui.MainViewModel
import com.remotecontrolplus.ui.diagnostics.DiagnosticsScreen
import com.remotecontrolplus.ui.discover.DiscoverScreen
import com.remotecontrolplus.ui.remote.RemoteScreen
import com.remotecontrolplus.ui.theme.RemoteControlPlusTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> Diagnostics.log("app", "notification permission ${if (granted) "granted" else "denied"}") }

    override fun onStart() {
        super.onStart()
        Diagnostics.log("app", "activity in foreground")
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        Diagnostics.log("app", "activity in background")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The lock-screen remote is a notification; Android 13+ needs the user's say-so.
        if (savedInstanceState == null && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
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
