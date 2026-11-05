package com.remotecontrolplus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.remotecontrolplus.core.RemoteKey
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun PadModeToggle(mode: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
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
fun TouchPad(
    mode: String,
    enabled: Boolean,
    onKey: (RemoteKey) -> Unit,
    onSwipe: (RemoteKey) -> Unit,
    /** Tap on the swipe surface; defaults to [onKey]. */
    onTap: (RemoteKey) -> Unit = onKey,
    /** Repeats while an arrow is held; defaults to [onKey]. */
    onRepeat: (RemoteKey) -> Unit = onKey,
    modifier: Modifier = Modifier,
    showHint: Boolean = false,
) {
    val chevronTint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant

    val gestureModifier = if (mode == "swipe" && enabled) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    onTap(RemoteKey.SELECT)
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
            RepeatArrow(RemoteKey.UP, enabled, onKey, onRepeat, Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(64.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up", tint = chevronTint, modifier = Modifier.size(32.dp))
            }
            RepeatArrow(RemoteKey.DOWN, enabled, onKey, onRepeat, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(64.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down", tint = chevronTint, modifier = Modifier.size(32.dp))
            }
            RepeatArrow(RemoteKey.LEFT, enabled, onKey, onRepeat, Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(64.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left", tint = chevronTint, modifier = Modifier.size(32.dp))
            }
            RepeatArrow(RemoteKey.RIGHT, enabled, onKey, onRepeat, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(64.dp)) {
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
            if (showHint) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HintLine(Icons.Filled.Swipe, "Swipe to navigate")
                    HintLine(Icons.Filled.TouchApp, "Tap to select")
                }
            }
        }
    }
}


@Composable
private fun HintLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * A D-pad arrow that repeats while held, like a physical remote: one press
 * immediately, then after 400 ms a repeat every 110 ms until release.
 */
@Composable
private fun RepeatArrow(
    key: RemoteKey,
    enabled: Boolean,
    onKey: (RemoteKey) -> Unit,
    onRepeat: (RemoteKey) -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        modifier = modifier
            .clip(CircleShape)
            .pointerInput(enabled, key) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    onKey(key)
                    val repeater = scope.launch {
                        kotlinx.coroutines.delay(400)
                        while (true) {
                            com.remotecontrolplus.core.Haptics.tick()
                            onRepeat(key)
                            kotlinx.coroutines.delay(110)
                        }
                    }
                    tryAwaitRelease()
                    repeater.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}
