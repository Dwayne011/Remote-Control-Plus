package com.remotecontrolplus.ui.remote

import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.remotecontrolplus.ui.theme.arriving
import com.remotecontrolplus.ui.theme.glass
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.Haptics
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.ui.MainViewModel
import com.remotecontrolplus.ui.PairingUiState
import com.remotecontrolplus.ui.components.PairingDialog
import com.remotecontrolplus.ui.components.icon
import com.remotecontrolplus.ui.components.AssistantButton
import com.remotecontrolplus.ui.components.PadModeToggle
import com.remotecontrolplus.ui.components.TouchPad
import com.remotecontrolplus.ui.components.skin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    viewModel: MainViewModel,
    onAddDevice: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val textField by viewModel.textField.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val padMode by viewModel.padMode.collectAsStateWithLifecycle()
    val powerState by viewModel.powerState.collectAsStateWithLifecycle()
    val waking by viewModel.waking.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val pairingDevice by viewModel.pairingDevice.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    var offerVoicePairing by remember { mutableStateOf<com.remotecontrolplus.core.TvDevice?>(null) }
    LaunchedEffect(Unit) { viewModel.voicePairingNeeded.collect { offerVoicePairing = it } }

    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    val device = selected
                    if (device == null) {
                        Text("Remote Control Plus")
                    } else {
                        // Name on top, "<type> · <state>" with a status dot underneath;
                        // the whole thing opens the switcher.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { Haptics.click(); switcherOpen = true }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(device.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Switch TV",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp).size(20.dp),
                                )
                            }
                            StatusLine(state = connection, deviceLabel = device.type.label, powerState = powerState, waking = waking)
                        }
                    }
                },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = { Haptics.click(); viewModel.reconnect() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reconnect")
                        }
                    }
                    IconButton(onClick = { Haptics.click(); onDiagnostics() }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Diagnostics")
                    }
                    IconButton(onClick = { Haptics.click(); onAddDevice() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a TV")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val device = selected
        if (device == null) {
            EmptyState(onAddDevice, Modifier.padding(padding))
        } else {
            val caps = capabilities ?: Capabilities(emptySet(), false)
            val connected = connection is ConnectionState.Connected
            // No scrolling: the fixed rows take what they need and the pad
            // flexes to fill whatever height is left.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The top control changes in place as the connection changes.
                val topControl = when (connection) {
                    ConnectionState.PairingRequired -> "pair"
                    is ConnectionState.Error, ConnectionState.Disconnected -> "reconnect"
                    else -> "mode"
                }
                com.remotecontrolplus.ui.theme.Swap(topControl, Modifier.fillMaxWidth().arriving(0, device.id)) { control -> when (control) {
                    "pair" -> FilledTonalButton(
                        onClick = { viewModel.startPairing(device) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair with this ${device.type.label}") }
                    "reconnect" -> FilledTonalButton(
                        onClick = { viewModel.reconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reconnect")
                    }
                    else -> if (caps.keys.any { it in Capabilities.NAVIGATION }) {
                        PadModeToggle(mode = padMode, onChange = viewModel::setPadMode, modifier = Modifier.fillMaxWidth())
                    }
                } }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .arriving(1, device.id),
                    contentAlignment = Alignment.Center,
                ) {
                    if (caps.keys.any { it in Capabilities.NAVIGATION }) {
                        // Swipe and D-pad swap in place.
                        com.remotecontrolplus.ui.theme.Swap(padMode, Modifier.fillMaxHeight()) { mode ->
                        TouchPad(
                            mode = mode,
                            enabled = connected,
                            onKey = viewModel::press,
                            onSwipe = viewModel::swipe,
                            onRepeat = { viewModel.press(it, haptic = false) },
                            onLongPress = { viewModel.longPress(it) },
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(1f, matchHeightConstraintsFirst = true),
                        )
                        }
                    } else {
                        Icon(
                            device.type.icon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }

                if (caps.keys.any { it in Capabilities.NAVIGATION }) {
                    Row(Modifier.fillMaxWidth().arriving(2, device.id), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val skin = device.type.skin()
                        PillButton(skin.backIcon, skin.backLabel, connected && RemoteKey.BACK in caps.keys, Modifier.weight(1f)) {
                            viewModel.press(RemoteKey.BACK)
                        }
                        PillButton(skin.homeIcon, skin.homeLabel, connected && RemoteKey.HOME in caps.keys, Modifier.weight(1f)) {
                            viewModel.press(RemoteKey.HOME)
                        }
                        if (RemoteKey.ASSISTANT in caps.keys) {
                            val listening by viewModel.remoteManager.listening.collectAsStateWithLifecycle()
                            AssistantButton(
                                remote = viewModel.remoteManager,
                                label = skin.assistantLabel,
                                enabled = connected,
                                listening = listening,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1f).height(56.dp),
                            )
                        }
                    }
                }

                PowerRow(
                    showOn = RemoteKey.POWER_ON in caps.keys,
                    showOff = RemoteKey.POWER_OFF in caps.keys,
                    offLabel = caps.offLabel,
                    onEnabled = powerState != true && !waking && (connected || device.mac != null),
                    offEnabled = connected && powerState != false,
                    onKey = viewModel::press,
                    modifier = Modifier.arriving(3, device.id),
                )

                MediaRow(keys = caps.keys, enabled = connected, onKey = viewModel::press, modifier = Modifier.arriving(4, device.id))

                VolumeRow(
                    keys = caps.keys,
                    enabled = connected,
                    slider = caps.volumeSlider,
                    level = volume,
                    onKey = viewModel::press,
                    onSet = viewModel::setVolume,
                    modifier = Modifier.arriving(5, device.id),
                )

                if (caps.textEntry) {
                    FilledTonalButton(
                        onClick = { Haptics.click(); keyboardOpen = true },
                        enabled = connected,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .arriving(6, device.id),
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (textField.focused) "Keyboard · field focused on TV" else "Keyboard")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (switcherOpen) {
        DeviceSwitcherSheet(
            devices = devices,
            selectedId = selected?.id,
            onSelect = { viewModel.selectDevice(it); switcherOpen = false },
            onRename = { id, name -> viewModel.renameDevice(id, name) },
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


    offerVoicePairing?.let { device ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { offerVoicePairing = null },
            title = { Text("Set up Siri voice?") },
            text = {
                Text(
                    "To hear you through your phone, ${device.name} needs one more pairing. " +
                        "A 4-digit code will appear on the TV. This is experimental and may not work on every tvOS version.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    offerVoicePairing = null
                    viewModel.startVoicePairing(device)
                }) { Text("Pair") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { offerVoicePairing = null }) { Text("Not now") }
            },
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
private fun StatusLine(state: ConnectionState, deviceLabel: String, powerState: Boolean?, waking: Boolean) {
    val (dot, text) = when {
        waking -> MaterialTheme.colorScheme.tertiary to "Waking up…"
        powerState == false && state !is ConnectionState.Connecting -> MaterialTheme.colorScheme.outline to "Off"
        else -> when (state) {
        ConnectionState.Connected -> MaterialTheme.colorScheme.primary to "Connected"
        ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary to "Connecting…"
        ConnectionState.PairingRequired -> MaterialTheme.colorScheme.tertiary to "Not paired"
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline to "Not connected"
        is ConnectionState.Error -> MaterialTheme.colorScheme.error to state.message
        }
    }
    val dotColour by androidx.compose.animation.animateColorAsState(dot, com.remotecontrolplus.ui.theme.AtriumMotion.smooth(), label = "status dot")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColour),
        )
        Spacer(Modifier.width(6.dp))
        com.remotecontrolplus.ui.theme.Swap("$deviceLabel · $text") { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    // No invisible loading: a bar while it connects or wakes the TV; the words above say which.
    androidx.compose.animation.AnimatedVisibility(
        visible = waking || state is ConnectionState.Connecting,
        enter = androidx.compose.animation.fadeIn(com.remotecontrolplus.ui.theme.AtriumMotion.smooth()),
        exit = androidx.compose.animation.fadeOut(com.remotecontrolplus.ui.theme.AtriumMotion.interactive()),
    ) {
        com.remotecontrolplus.ui.theme.MovingBar(modifier = Modifier.padding(top = 4.dp).width(120.dp), height = 3.dp)
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, com.remotecontrolplus.ui.theme.GlassEdge),
        modifier = modifier.height(56.dp),
    ) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun PowerRow(
    showOn: Boolean,
    showOff: Boolean,
    offLabel: String,
    onEnabled: Boolean,
    offEnabled: Boolean,
    onKey: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!showOn && !showOff) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showOn) PowerSegment("On", onEnabled, Modifier.weight(1f)) { onKey(RemoteKey.POWER_ON) }
        if (showOff) PowerSegment(offLabel, offEnabled, Modifier.weight(1f)) { onKey(RemoteKey.POWER_OFF) }
    }
}

/**
 * One power action. Available: filled, coloured, with the power icon.
 * Unavailable (the TV is already in that state): flat, faded outline.
 */
@Composable
private fun PowerSegment(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val isOn = label == "On"
    val container = when {
        !enabled -> Color.Transparent
        isOn -> com.remotecontrolplus.ui.theme.GlassUp.copy(alpha = 0.2f)
        else -> com.remotecontrolplus.ui.theme.GlassFill
    }
    val contentTarget = if (enabled) {
        if (isOn) com.remotecontrolplus.ui.theme.GlassUp else MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val content by androidx.compose.animation.animateColorAsState(contentTarget, com.remotecontrolplus.ui.theme.AtriumMotion.smooth(), label = "power content")
    val containerAnimated by androidx.compose.animation.animateColorAsState(container, com.remotecontrolplus.ui.theme.AtriumMotion.smooth(), label = "power container")
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(containerAnimated)
            .border(1.dp, if (enabled) com.remotecontrolplus.ui.theme.GlassEdge else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = content, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MediaRow(keys: Set<RemoteKey>, enabled: Boolean, onKey: (RemoteKey) -> Unit, modifier: Modifier = Modifier) {
    if (RemoteKey.PLAY !in keys && RemoteKey.PAUSE !in keys) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onKey(RemoteKey.PREVIOUS) }, enabled = enabled && RemoteKey.PREVIOUS in keys) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
        }
        FilledTonalIconButton(onClick = { onKey(RemoteKey.PLAY) }, enabled = enabled && RemoteKey.PLAY in keys, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
        }
        FilledTonalIconButton(onClick = { onKey(RemoteKey.PAUSE) }, enabled = enabled && RemoteKey.PAUSE in keys, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Filled.Pause, contentDescription = "Pause")
        }
        IconButton(onClick = { onKey(RemoteKey.NEXT) }, enabled = enabled && RemoteKey.NEXT in keys) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next")
        }
    }
}

@Composable
private fun VolumeRow(
    keys: Set<RemoteKey>,
    enabled: Boolean,
    slider: Boolean,
    level: Float?,
    onKey: (RemoteKey) -> Unit,
    onSet: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (RemoteKey.VOLUME_UP !in keys && RemoteKey.MUTE !in keys) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalIconButton(
            onClick = { onKey(RemoteKey.MUTE) },
            enabled = enabled && RemoteKey.MUTE in keys,
            modifier = Modifier.size(56.dp),
        ) { Icon(Icons.Filled.VolumeOff, contentDescription = "Mute") }

        run {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .glass(RoundedCornerShape(20.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(enabled = enabled && RemoteKey.VOLUME_DOWN in keys) { onKey(RemoteKey.VOLUME_DOWN) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Remove, contentDescription = "Volume down") }
                Box(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(enabled = enabled && RemoteKey.VOLUME_UP in keys) { onKey(RemoteKey.VOLUME_UP) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = "Volume up") }
            }
        }
    }
}
