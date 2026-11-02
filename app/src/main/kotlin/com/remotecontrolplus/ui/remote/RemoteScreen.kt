package com.remotecontrolplus.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.ui.MainViewModel
import com.remotecontrolplus.ui.PairingUiState
import com.remotecontrolplus.ui.components.PairingDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    viewModel: MainViewModel,
    onAddDevice: () -> Unit,
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val textField by viewModel.textField.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val pairingDevice by viewModel.pairingDevice.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val device = selected
                    if (device == null) {
                        Text("Remote Control Plus")
                    } else {
                        TextButton(onClick = { switcherOpen = true }) {
                            Text(device.name, style = MaterialTheme.typography.titleLarge)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch TV")
                        }
                    }
                },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = { viewModel.reconnect() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reconnect")
                        }
                    }
                    IconButton(onClick = onAddDevice) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a TV")
                    }
                },
            )
        },
        floatingActionButton = {
            val caps = capabilities
            if (selected != null && caps?.textEntry == true && connection is ConnectionState.Connected) {
                ExtendedFloatingActionButton(
                    onClick = { keyboardOpen = true },
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                    text = { Text("Keyboard") },
                    expanded = textField.focused,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val device = selected
        if (device == null) {
            EmptyState(onAddDevice, Modifier.padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ConnectionBanner(
                    state = connection,
                    deviceLabel = device.type.label,
                    onPair = { viewModel.startPairing(device) },
                    onRetry = { viewModel.reconnect() },
                )
                Spacer(Modifier.height(16.dp))
                val keys = capabilities?.keys ?: emptySet()
                val enabled = connection is ConnectionState.Connected ||
                    (connection !is ConnectionState.Connecting && device.mac != null)
                RemotePad(
                    keys = keys,
                    enabled = enabled,
                    connected = connection is ConnectionState.Connected,
                    powerHint = capabilities?.powerHint,
                    onKey = viewModel::press,
                )
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    if (switcherOpen) {
        DeviceSwitcherSheet(
            devices = devices,
            selectedId = selected?.id,
            onSelect = { viewModel.selectDevice(it); switcherOpen = false },
            onForget = { viewModel.forgetDevice(it) },
            onAdd = { switcherOpen = false; onAddDevice() },
            onDismiss = { switcherOpen = false },
        )
    }

    if (keyboardOpen) {
        KeyboardSheet(
            currentText = textField,
            onSend = { viewModel.sendText(it) },
            onDismiss = { keyboardOpen = false },
        )
    }

    val pd = pairingDevice
    if (pd != null && pairing != PairingUiState.Idle) {
        PairingDialog(
            device = pd,
            state = pairing,
            onSubmitPin = viewModel::submitPin,
            onRetry = viewModel::retryPairing,
            onDismiss = { viewModel.cancelPairing() },
        )
    }
}

@Composable
private fun EmptyState(onAddDevice: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No TVs yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Find the TVs on your Wi-Fi and pair them once. Then switch between them from the title bar.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddDevice) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Find TVs")
        }
    }
}

@Composable
private fun ConnectionBanner(
    state: ConnectionState,
    deviceLabel: String,
    onPair: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        ConnectionState.Connected -> AssistChip(
            onClick = {},
            label = { Text("Connected · $deviceLabel") },
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, Modifier.width(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        )
        ConnectionState.Connecting -> AssistChip(onClick = {}, label = { Text("Connecting…") })
        ConnectionState.Disconnected -> AssistChip(onClick = onRetry, label = { Text("Not connected · tap to retry") })
        ConnectionState.PairingRequired -> FilledTonalButton(onClick = onPair) {
            Text("Pair with this $deviceLabel")
        }
        is ConnectionState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AssistChip(
                onClick = onRetry,
                label = { Text(state.message) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.width(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            )
        }
    }
}

/** The shared layout: power row, volume + D-pad, navigation row. */
@Composable
private fun RemotePad(
    keys: Set<RemoteKey>,
    enabled: Boolean,
    connected: Boolean,
    powerHint: String?,
    onKey: (RemoteKey) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = { onKey(RemoteKey.POWER) },
                    enabled = enabled && RemoteKey.POWER in keys,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.width(56.dp).height(56.dp),
                ) { Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Power") }
                Text(
                    powerHint ?: "Power",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(
                    onClick = { onKey(RemoteKey.MUTE) },
                    enabled = connected && RemoteKey.MUTE in keys,
                    modifier = Modifier.width(56.dp).height(56.dp),
                ) { Icon(Icons.Filled.VolumeOff, contentDescription = "Mute") }
                Text("Mute", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VolumeRocker(
                enabled = connected && RemoteKey.VOLUME_UP in keys,
                onUp = { onKey(RemoteKey.VOLUME_UP) },
                onDown = { onKey(RemoteKey.VOLUME_DOWN) },
            )
            DirectionPad(
                enabled = connected && RemoteKey.SELECT in keys,
                onKey = onKey,
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            LabelledButton("Back", Icons.AutoMirrored.Filled.ArrowBack, connected && RemoteKey.BACK in keys) { onKey(RemoteKey.BACK) }
            LabelledButton("Home", Icons.Filled.Home, connected && RemoteKey.HOME in keys) { onKey(RemoteKey.HOME) }
            PlayPauseButton(connected && RemoteKey.PLAY_PAUSE in keys) { onKey(RemoteKey.PLAY_PAUSE) }
        }
    }
}

@Composable
private fun LabelledButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.width(64.dp).height(64.dp),
        ) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayPauseButton(enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.width(64.dp).height(64.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.width(20.dp))
                Icon(Icons.Outlined.Pause, contentDescription = null, Modifier.width(20.dp))
            }
        }
        Text("Play / Pause", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VolumeRocker(enabled: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(onClick = onUp, enabled = enabled, modifier = Modifier.width(56.dp).height(72.dp)) {
            Icon(Icons.Filled.VolumeUp, contentDescription = "Volume up")
        }
        Text("VOL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalIconButton(onClick = onDown, enabled = enabled, modifier = Modifier.width(56.dp).height(72.dp)) {
            Icon(Icons.Filled.VolumeDown, contentDescription = "Volume down")
        }
    }
}

@Composable
private fun DirectionPad(enabled: Boolean, onKey: (RemoteKey) -> Unit) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .width(216.dp)
            .height(216.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(4.dp),
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawCircle(color = surfaceVariant)
            }
        }
        FilledTonalIconButton(
            onClick = { onKey(RemoteKey.UP) },
            enabled = enabled,
            modifier = Modifier.align(Alignment.TopCenter).width(56.dp).height(56.dp),
        ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up") }
        FilledTonalIconButton(
            onClick = { onKey(RemoteKey.DOWN) },
            enabled = enabled,
            modifier = Modifier.align(Alignment.BottomCenter).width(56.dp).height(56.dp),
        ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down") }
        FilledTonalIconButton(
            onClick = { onKey(RemoteKey.LEFT) },
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterStart).width(56.dp).height(56.dp),
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left") }
        FilledTonalIconButton(
            onClick = { onKey(RemoteKey.RIGHT) },
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterEnd).width(56.dp).height(56.dp),
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Right") }
        FilledIconButton(
            onClick = { onKey(RemoteKey.SELECT) },
            enabled = enabled,
            modifier = Modifier.width(80.dp).height(80.dp),
        ) { Text("OK", style = MaterialTheme.typography.titleMedium) }
    }
}
