package com.remotecontrolplus.ui.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.Dp
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

private val PAD_HEIGHT = 236.dp

/**
 * The pop-up remote card, after Google Home's: close, device chip with a
 * switcher, power; the pad; Back, Home and open-full; mute and volume.
 * Full width, compact height, and a downward swipe on it dismisses.
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
    val hasNavigation = caps.keys.any { it in Capabilities.NAVIGATION }
    val press: (RemoteKey) -> Unit = { remote.press(it) }
    var switcherOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, delta -> total += delta },
                    onDragEnd = { if (total > 100.dp.toPx()) onDismiss() },
                )
            },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Drag handle
            Box(
                Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // Close · device chip · power
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundButton(Icons.Filled.Close, "Close", enabled = true, size = 48.dp) { Haptics.click(); onDismiss() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
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
                val onTv = powerState == true || (powerState == null && connected)
                val powerAvailable = (RemoteKey.POWER_ON in caps.keys || RemoteKey.POWER_OFF in caps.keys) &&
                    !waking && (connected || device?.mac != null)
                RoundButton(
                    Icons.Filled.PowerSettingsNew,
                    if (onTv) "Turn off" else "Turn on",
                    enabled = powerAvailable,
                    size = 48.dp,
                ) { press(if (onTv) RemoteKey.POWER_OFF else RemoteKey.POWER_ON) }
            }

            // Pad: a wide rounded surface in swipe mode, a circle in D-pad mode.
            if (hasNavigation) {
                TouchPad(
                    mode = padMode,
                    enabled = connected,
                    onKey = press,
                    onSwipe = { remote.swipe(it) },
                    modifier = if (padMode == "dpad") {
                        Modifier.size(PAD_HEIGHT)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(PAD_HEIGHT)
                    },
                    showHint = true,
                )
            }

            // Back · Home · open full remote
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val skin = device?.type?.skin()
                RoundButton(skin?.backIcon ?: Icons.Filled.Close, skin?.backLabel ?: "Back", connected && RemoteKey.BACK in caps.keys, 60.dp) {
                    press(RemoteKey.BACK)
                }
                WideButton(skin?.homeIcon ?: Icons.Filled.Keyboard, skin?.homeLabel ?: "Home", connected && RemoteKey.HOME in caps.keys, Modifier.weight(1f).height(60.dp)) {
                    press(RemoteKey.HOME)
                }
                RoundButton(Icons.Filled.OpenInFull, "Open full remote", enabled = true, size = 60.dp) { Haptics.click(); onOpenFull() }
            }

            // Mute · volume down · volume up
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.VolumeOff, "Mute", connected && RemoteKey.MUTE in caps.keys, 56.dp) { press(RemoteKey.MUTE) }
                WideButton(Icons.Filled.Remove, "Volume down", connected && RemoteKey.VOLUME_DOWN in caps.keys, Modifier.weight(1f).height(56.dp)) {
                    press(RemoteKey.VOLUME_DOWN)
                }
                WideButton(Icons.Filled.VolumeUp, "Volume up", connected && RemoteKey.VOLUME_UP in caps.keys, Modifier.weight(1f).height(56.dp)) {
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
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(name, color = content, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (status != null) {
                Text(status, color = content, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(1.dp).height(24.dp).background(content.copy(alpha = 0.25f)))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch TV", tint = content)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean, size: Dp, onClick: () -> Unit) {
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
