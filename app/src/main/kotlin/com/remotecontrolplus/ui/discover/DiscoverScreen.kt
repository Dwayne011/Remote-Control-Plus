package com.remotecontrolplus.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrolplus.ui.MainViewModel
import com.remotecontrolplus.ui.PairingUiState
import com.remotecontrolplus.ui.components.PairingDialog
import com.remotecontrolplus.ui.components.icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val saved by viewModel.devices.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val pairingDevice by viewModel.pairingDevice.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find TVs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startScan() }, enabled = !scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Scan again")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }
            Text(
                if (scanning) "Looking on your Wi-Fi for Samsung, LG, Roku, Apple TV, Google TV and Chromecast devices…"
                else "Make sure the TV is on and on the same Wi-Fi, then scan again if it is missing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (discovered.isEmpty() && !scanning) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No TVs found", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { viewModel.startScan() }) { Text("Scan again") }
                }
            }
            LazyColumn {
                items(discovered, key = { it.id }) { device ->
                    val alreadySaved = saved.any { it.id == device.id }
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = {
                            Text(listOfNotNull(device.type.label, device.model, device.host).joinToString(" · "))
                        },
                        leadingContent = { Icon(device.type.icon(), contentDescription = null) },
                        trailingContent = {
                            if (alreadySaved) {
                                Icon(Icons.Filled.Check, contentDescription = "Already added", tint = MaterialTheme.colorScheme.primary)
                            } else {
                                FilledTonalButton(onClick = { viewModel.addDevice(device) }) { Text("Add") }
                            }
                        },
                        modifier = Modifier.clickable(enabled = !alreadySaved) { viewModel.addDevice(device) },
                    )
                }
            }
        }
    }

    val pd = pairingDevice
    if (pd != null && pairing != PairingUiState.Idle) {
        PairingDialog(
            device = pd,
            state = pairing,
            onSubmitPin = viewModel::submitPin,
            onRetry = viewModel::retryPairing,
            onDismiss = {
                val done = pairing == PairingUiState.Done
                viewModel.cancelPairing()
                if (done) onBack()
            },
        )
    }
}
