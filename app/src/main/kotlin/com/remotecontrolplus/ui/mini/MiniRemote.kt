package com.remotecontrolplus.ui.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.Haptics
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.data.RemoteManager
import com.remotecontrolplus.ui.components.TouchPad
import com.remotecontrolplus.ui.components.icon
import com.remotecontrolplus.ui.components.skin

/**
 * The full-screen remote shown from the Quick Settings tile, after Google
 * Home's: close, device chip with a switcher, power; the pad; Back, Home and
 * a button to the full app; mute and volume. Swipe down anywhere outside
 * the pad to dismiss.
 */
@Composable
fun MiniRemote(remote: RemoteManager, onOpenFull: () -> Unit, onDismiss: () -> Unit) {
    val devices by remote.devices.collectAsStateWithLifecycle()
    val device by remote.selected.collectAsStateWithLifecycle()
    val connection by remote.connectionState.collectAsStateWithLifecycle()
    val capabilities by remote.capabilities.collectAsStateWithLifecycle()
    val powerState by remote.powerState.collectAsStateWithLifecycle()
    val waking by remote.waking.collectAsStateWithLifecycle()
    val padMode by remote.padMode.collectAsStateWithLifecycle()

    val caps = capabilities ?: Capabilities(emptySet(), false)
    val connected = connection is ConnectionState.Connected
    val press: (RemoteKey) -> Unit = { remote.press(it) }
    var switcherOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, delta -> total += delta },
                    onDragEnd = { if (total > 120.dp.toPx()) onDismiss() },
                )
            },
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Close · device chip · power
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.Close, "Close", enabled = true, size = 52.dp) { Haptics.click(); onDismiss() }
                Spacer(Modifier.weight(1f))
                Box {
                    DeviceChip(
                        name = device?.name ?: "No TV",
                        icon = device?.type?.icon(),
                        connected = connected,
                        status = when {
                            device == null -> null
                            waking -> "Waking up…"
                            powerState == false && connection !is ConnectionState.Connecting -> "Off"
                            connection is ConnectionState.Connecting -> "Connecting…"
                            connection is ConnectionState.PairingRequired -> "Needs pairing"
                            connected -> null
                            else -> "Not connected"
                        },
                        onClick = { Haptics.click(); switcherOpen = true },
                    )
                    DropdownMenu(expanded = switcherOpen, onDismissRequest = { switcherOpen = false }) {
                        devices.forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.name) },
                                leadingIcon = { Icon(d.type.icon(), contentDescription = null) },
                                trailingIcon = if (d.id == device?.id) {
                                    { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                                } else {
                                    null
                                },
                                onClick = { switcherOpen = false; remote.selectDevice(d.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val onTv = powerState == true || (powerState == null && connected)
                val powerAvailable = (RemoteKey.POWER_ON in caps.keys || RemoteKey.POWER_OFF in caps.keys) &&
                    !waking && (connected || device?.mac != null)
                RoundButton(
                    Icons.Filled.PowerSettingsNew,
                    if (onTv) "Turn off" else "Turn on",
                    enabled = powerAvailable,
                    size = 52.dp,
                ) { press(if (onTv) RemoteKey.POWER_OFF else RemoteKey.POWER_ON) }
            }

            // The pad takes whatever height the fixed rows leave.
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
                        onKey = press,
                        onSwipe = { remote.swipe(it) },
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(0.8f, matchHeightConstraintsFirst = true),
                        showHint = true,
                    )
                } else {
                    Icon(
                        device?.type?.icon() ?: Icons.Filled.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(96.dp),
                    )
                }
            }

            // Back · Home · open full remote
            if (caps.keys.any { it in Capabilities.NAVIGATION }) {
                val skin = device?.type?.skin()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(skin?.backIcon ?: Icons.Filled.Close, skin?.backLabel ?: "Back", connected && RemoteKey.BACK in caps.keys, 76.dp) {
                        press(RemoteKey.BACK)
                    }
                    WideButton(skin?.homeIcon ?: Icons.Filled.Keyboard, skin?.homeLabel ?: "Home", connected && RemoteKey.HOME in caps.keys, Modifier.weight(1f).height(76.dp)) {
                        press(RemoteKey.HOME)
                    }
                    RoundButton(Icons.Filled.OpenInFull, "Open full remote", enabled = true, size = 76.dp) { Haptics.click(); onOpenFull() }
                }
            }

            // Mute · volume down · volume up
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.VolumeOff, "Mute", connected && RemoteKey.MUTE in caps.keys, 64.dp) { press(RemoteKey.MUTE) }
                WideButton(Icons.Filled.Remove, "Volume down", connected && RemoteKey.VOLUME_DOWN in caps.keys, Modifier.weight(1f).height(64.dp)) {
                    press(RemoteKey.VOLUME_DOWN)
                }
                WideButton(Icons.Filled.VolumeUp, "Volume up", connected && RemoteKey.VOLUME_UP in caps.keys, Modifier.weight(1f).height(64.dp)) {
                    press(RemoteKey.VOLUME_UP)
                }
            }
        }
    }
}

@Composable
private fun DeviceChip(name: String, icon: ImageVector?, connected: Boolean, status: String?, onClick: () -> Unit) {
    val container = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(name, color = content, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (status != null) {
                Text(status, color = content, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(1.dp).height(28.dp).background(content.copy(alpha = 0.25f)))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch TV", tint = content)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun WideButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

