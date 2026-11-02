package com.remotecontrolplus.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotecontrolplus.core.Haptics
import com.remotecontrolplus.core.TvDevice
import com.remotecontrolplus.ui.components.icon

/** The "which TV am I controlling" picker, as a Material 3 modal bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSwitcherSheet(
    devices: List<TvDevice>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onForget: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var confirmForget by remember { mutableStateOf<TvDevice?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Your TVs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            devices.forEach { device ->
                val selected = device.id == selectedId
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = {
                        Text(listOfNotNull(device.type.label, device.model, device.host).joinToString(" · "))
                    },
                    leadingContent = { Icon(device.type.icon(), contentDescription = null) },
                    trailingContent = {
                        if (selected) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            IconButton(onClick = { Haptics.click(); confirmForget = device }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Forget ${device.name}")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(device.id) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text("Add a TV") },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { Haptics.click(); onAdd() },
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    confirmForget?.let { device ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Forget ${device.name}?") },
            text = { Text("Its pairing will be removed. You can find and pair it again later.") },
            confirmButton = {
                TextButton(onClick = { onForget(device.id); confirmForget = null }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("Cancel") } },
        )
    }
}
