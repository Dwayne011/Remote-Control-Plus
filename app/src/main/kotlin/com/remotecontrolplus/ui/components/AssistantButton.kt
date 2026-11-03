package com.remotecontrolplus.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.remotecontrolplus.data.RemoteManager

/**
 * Hold to talk. The first press asks for microphone access; after that,
 * holding streams the phone microphone (Google TV) or holds Siri (Apple TV)
 * until the finger lifts.
 */
@Composable
fun AssistantButton(
    remote: RemoteManager,
    label: String,
    enabled: Boolean,
    listening: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var askedForMic by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) remote.message("Microphone allowed. Hold the button and speak.")
        else remote.message("Without the microphone the button only opens the assistant on the TV")
    }
    val container = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = when {
        listening -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(container)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted && !askedForMic && remote.wantsMicrophone()) {
                        askedForMic = true
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    remote.assistantPressed(micAllowed = granted)
                    tryAwaitRelease()
                    remote.assistantReleased()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = if (listening) "Listening" else "Hold to talk to $label", tint = content)
    }
}
