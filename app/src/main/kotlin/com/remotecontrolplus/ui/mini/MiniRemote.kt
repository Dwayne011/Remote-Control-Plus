package com.remotecontrolplus.ui.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrolplus.core.Capabilities
import com.remotecontrolplus.core.ConnectionState
import com.remotecontrolplus.core.RemoteKey
import com.remotecontrolplus.data.RemoteManager
import com.remotecontrolplus.ui.components.skin

/**
 * The compact remote shown from the Quick Settings tile: D-pad, Back/Home,
 * media and volume on one card, with a button to the full app.
 */
@Composable
fun MiniRemote(remote: RemoteManager, onOpenFull: () -> Unit) {
    val device by remote.selected.collectAsStateWithLifecycle()
    val connection by remote.connectionState.collectAsStateWithLifecycle()
    val capabilities by remote.capabilities.collectAsStateWithLifecycle()
    val powerState by remote.powerState.collectAsStateWithLifecycle()
    val waking by remote.waking.collectAsStateWithLifecycle()

    val caps = capabilities ?: Capabilities(emptySet(), false)
    val connected = connection is ConnectionState.Connected
    val press: (RemoteKey) -> Unit = { remote.press(it) }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device?.name ?: "No TV selected", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val status = when {
                        device == null -> "Add a TV in the app"
                        waking -> "Waking up…"
                        powerState == false && connection !is ConnectionState.Connecting -> "Off"
                        connection is ConnectionState.Connected -> "Connected"
                        connection is ConnectionState.Connecting -> "Connecting…"
                        connection is ConnectionState.PairingRequired -> "Needs pairing · open the app"
                        else -> "Not connected"
                    }
                    Text(
                        listOfNotNull(device?.type?.label, status).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (RemoteKey.POWER_ON in caps.keys || RemoteKey.POWER_OFF in caps.keys) {
                    val onTv = powerState == true || (powerState == null && connected)
                    IconButton(
                        onClick = { press(if (onTv) RemoteKey.POWER_OFF else RemoteKey.POWER_ON) },
                        enabled = !waking && (connected || device?.mac != null),
                    ) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = if (onTv) "Turn off" else "Turn on",
                            tint = if (onTv) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onOpenFull) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = "Open full remote")
                }
            }

            if (caps.keys.any { it in Capabilities.NAVIGATION }) {
                MiniDpad(enabled = connected, onKey = press)
                val skin = (device?.type ?: return@Column).skin()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniPill(skin.backIcon, skin.backLabel, connected && RemoteKey.BACK in caps.keys, Modifier.weight(1f)) { press(RemoteKey.BACK) }
                    MiniPill(skin.homeIcon, skin.homeLabel, connected && RemoteKey.HOME in caps.keys, Modifier.weight(1f)) { press(RemoteKey.HOME) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniRound(Icons.Filled.VolumeOff, "Mute", connected && RemoteKey.MUTE in caps.keys) { press(RemoteKey.MUTE) }
                MiniRound(Icons.Filled.Remove, "Volume down", connected && RemoteKey.VOLUME_DOWN in caps.keys) { press(RemoteKey.VOLUME_DOWN) }
                MiniRound(Icons.Filled.VolumeUp, "Volume up", connected && RemoteKey.VOLUME_UP in caps.keys) { press(RemoteKey.VOLUME_UP) }
                Spacer(Modifier.weight(1f))
                MiniRound(Icons.Filled.PlayArrow, "Play", connected && RemoteKey.PLAY in caps.keys) { press(RemoteKey.PLAY) }
                MiniRound(Icons.Filled.Pause, "Pause", connected && RemoteKey.PAUSE in caps.keys) { press(RemoteKey.PAUSE) }
            }
        }
    }
}

@Composable
private fun MiniDpad(enabled: Boolean, onKey: (RemoteKey) -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(188.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = { onKey(RemoteKey.UP) }, enabled = enabled, modifier = Modifier.align(Alignment.TopCenter).size(48.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up", tint = tint)
        }
        IconButton(onClick = { onKey(RemoteKey.DOWN) }, enabled = enabled, modifier = Modifier.align(Alignment.BottomCenter).size(48.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down", tint = tint)
        }
        IconButton(onClick = { onKey(RemoteKey.LEFT) }, enabled = enabled, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left", tint = tint)
        }
        IconButton(onClick = { onKey(RemoteKey.RIGHT) }, enabled = enabled, modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Right", tint = tint)
        }
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
                .clickable(enabled = enabled) { onKey(RemoteKey.SELECT) },
        )
    }
}

@Composable
private fun MiniPill(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
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
private fun MiniRound(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = label)
    }
}

