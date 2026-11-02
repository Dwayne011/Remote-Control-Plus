package com.remotecontrolplus.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.remotecontrolplus.core.TvDevice
import com.remotecontrolplus.ui.PairingUiState

/**
 * The one dialog that walks through every pairing style: a progress bar
 * while the TV is contacted or an on-screen prompt is awaited, or a code
 * field for devices that display one.
 */
@Composable
fun PairingDialog(
    device: TvDevice,
    state: PairingUiState,
    onSubmitPin: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by rememberSaveable(device.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(device.type.icon(), contentDescription = null) },
        title = { Text("Pair with ${device.name}") },
        text = {
            Column {
                when (state) {
                    is PairingUiState.Starting -> {
                        Text("Contacting your ${device.type.label}…")
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is PairingUiState.AwaitingApproval -> {
                        Text(state.instruction)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is PairingUiState.AwaitingPin -> {
                        Text(state.instruction)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it },
                            label = { Text("Code") },
                            singleLine = true,
                            enabled = !state.inProgress,
                            isError = state.error != null,
                            supportingText = state.error?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.inProgress) {
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is PairingUiState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    PairingUiState.Done -> Text("Paired. ${device.name} is ready to use.")
                    PairingUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is PairingUiState.AwaitingPin -> TextButton(
                    onClick = { onSubmitPin(pin) },
                    enabled = pin.isNotBlank() && !state.inProgress,
                ) { Text("Pair") }
                is PairingUiState.Failed -> TextButton(onClick = onRetry) { Text("Try again") }
                PairingUiState.Done -> TextButton(onClick = onDismiss) { Text("Done") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state != PairingUiState.Done) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
