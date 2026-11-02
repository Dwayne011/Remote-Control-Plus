package com.remotecontrolplus.ui.remote

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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.Haptics
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.ui.MainViewModel
import com.remotecontrolplus.ui.PairingUiState
import com.remotecontrolplus.ui.components.PairingDialog
import com.remotecontrolplus.ui.components.icon
import com.remotecontrolplus.ui.components.skin
import kotlin.math.abs

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
                            Text(device.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
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
                    IconButton(onClick = onDiagnostics) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Diagnostics")
                    }
                    IconButton(onClick = onAddDevice) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionBanner(
                        state = connection,
                        deviceLabel = device.type.label,
                        onPair = { viewModel.startPairing(device) },
                        onRetry = { viewModel.reconnect() },
                    )
                    if (caps.keys.any { it in Capabilities.NAVIGATION }) {
                        PadModeToggle(mode = padMode, onChange = viewModel::setPadMode)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (caps.keys.any { it in Capabilities.NAVIGATION }) {
                        TouchPad(
                            mode = padMode,
                            enabled = connected,
                            onKey = viewModel::press,
                            onSwipe = viewModel::swipe,
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(1f, matchHeightConstraintsFirst = true),
                        )
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val skin = device.type.skin()
                        PillButton(skin.backIcon, skin.backLabel, connected && RemoteKey.BACK in caps.keys, Modifier.weight(1f)) {
                            viewModel.press(RemoteKey.BACK)
                        }
                        PillButton(skin.homeIcon, skin.homeLabel, connected && RemoteKey.HOME in caps.keys, Modifier.weight(1f)) {
                            viewModel.press(RemoteKey.HOME)
                        }
                    }
                }

                PowerRow(
                    showOn = RemoteKey.POWER_ON in caps.keys,
                    showOff = RemoteKey.POWER_OFF in caps.keys,
                    offLabel = caps.offLabel,
                    onEnabled = connected || device.mac != null,
                    offEnabled = connected,
                    onKey = viewModel::press,
                )

                MediaRow(keys = caps.keys, enabled = connected, onKey = viewModel::press)

                VolumeRow(
                    keys = caps.keys,
                    enabled = connected,
                    slider = caps.volumeSlider,
                    level = volume,
                    onKey = viewModel::press,
                    onSet = viewModel::setVolume,
                )

                if (caps.textEntry) {
                    FilledTonalButton(
                        onClick = { keyboardOpen = true },
                        enabled = connected,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
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
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, Modifier.size(18.dp)) },
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
        is ConnectionState.Error -> AssistChip(
            onClick = onRetry,
            label = { Text(state.message, maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        )
    }
}

@Composable
private fun PadModeToggle(mode: String, onChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = mode == "swipe",
            onClick = { onChange("swipe") },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Swipe") }
        SegmentedButton(
            selected = mode == "dpad",
            onClick = { onChange("dpad") },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("D-pad") }
    }
}

/**
 * The big square pad from Google Home's remote. In swipe mode a flick in
 * any direction moves and a tap selects; in D-pad mode the chevrons and the
 * centre circle are plain buttons.
 */
@Composable
private fun TouchPad(
    mode: String,
    enabled: Boolean,
    onKey: (RemoteKey) -> Unit,
    onSwipe: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chevronTint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant

    val gestureModifier = if (mode == "swipe" && enabled) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    Haptics.click()
                    onKey(RemoteKey.SELECT)
                })
            }
            .pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDrag = { change, delta ->
                        change.consume()
                        total += delta
                    },
                    onDragEnd = {
                        val threshold = 40.dp.toPx()
                        val direction = when {
                            abs(total.x) < threshold && abs(total.y) < threshold -> null
                            abs(total.x) > abs(total.y) -> if (total.x > 0) RemoteKey.RIGHT else RemoteKey.LEFT
                            else -> if (total.y > 0) RemoteKey.DOWN else RemoteKey.UP
                        }
                        if (direction != null) {
                            Haptics.click()
                            onSwipe(direction)
                        }
                    },
                )
            }
    } else {
        Modifier
    }

    if (mode == "dpad") {
        // Google Home's D-pad: one big ring with chevrons, and a large inner disc for OK.
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { onKey(RemoteKey.UP) }, enabled = enabled, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(64.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up", tint = chevronTint, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { onKey(RemoteKey.DOWN) }, enabled = enabled, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(64.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down", tint = chevronTint, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { onKey(RemoteKey.LEFT) }, enabled = enabled, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(64.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left", tint = chevronTint, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { onKey(RemoteKey.RIGHT) }, enabled = enabled, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(64.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Right", tint = chevronTint, modifier = Modifier.size(32.dp))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.44f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (enabled) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = enabled) { onKey(RemoteKey.SELECT) },
            )
        }
    } else {
        // Google Home's swipe surface: a plain rounded square with a dot in the middle.
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(gestureModifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
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
) {
    if (!showOn && !showOff) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (showOn) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(enabled = onEnabled) { onKey(RemoteKey.POWER_ON) },
                contentAlignment = Alignment.Center,
            ) {
                Text("On", color = if (onEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            }
        }
        if (showOn && showOff) {
            Box(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
        }
        if (showOff) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(enabled = offEnabled) { onKey(RemoteKey.POWER_OFF) },
                contentAlignment = Alignment.Center,
            ) {
                Text(offLabel, color = if (offEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun MediaRow(keys: Set<RemoteKey>, enabled: Boolean, onKey: (RemoteKey) -> Unit) {
    if (RemoteKey.PLAY !in keys && RemoteKey.PAUSE !in keys) return
    Row(
        modifier = Modifier.fillMaxWidth(),
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
) {
    if (RemoteKey.VOLUME_UP !in keys && RemoteKey.MUTE !in keys) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalIconButton(
            onClick = { onKey(RemoteKey.MUTE) },
            enabled = enabled && RemoteKey.MUTE in keys,
            modifier = Modifier.size(56.dp),
        ) { Icon(Icons.Filled.VolumeOff, contentDescription = "Mute") }

        if (slider && level != null) {
            // Local copy while dragging so the thumb does not fight incoming updates.
            var dragging by remember { mutableStateOf(false) }
            var local by remember { mutableFloatStateOf(level) }
            if (!dragging) local = level
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Slider(
                    value = local,
                    onValueChange = { dragging = true; local = it },
                    onValueChangeFinished = { dragging = false; onSet(local) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                ) { Icon(Icons.Filled.VolumeUp, contentDescription = "Volume up") }
            }
        }
    }
}
