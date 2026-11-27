package com.remotecontrolplus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.remotecontrolplus.ui.theme.glass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.remotecontrolplus.core.RemoteKey
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Swipe / D-pad as two glass pills; the chosen one filled with the accent. */
@Composable
fun PadModeToggle(mode: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        for ((value, label) in listOf("swipe" to "Swipe", "dpad" to "D-pad")) {
            val on = mode == value
            val fill by androidx.compose.animation.animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.07f),
                com.remotecontrolplus.ui.theme.AtriumMotion.snappy(),
                label = "pill",
            )
            val ink by androidx.compose.animation.animateColorAsState(
                if (on) MaterialTheme.colorScheme.onPrimary else Color(0xFFD5D9DF),
                com.remotecontrolplus.ui.theme.AtriumMotion.snappy(),
                label = "pill text",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(fill)
                    .clickable { onChange(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                    color = ink,
                )
            }
        }
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
    /** Press and hold on the centre button or the swipe surface. */
    onLongPress: (RemoteKey) -> Unit = {},
    modifier: Modifier = Modifier,
    showHint: Boolean = false,
) {
    val chevronTint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant

    // Swipe surface: the dot follows the finger like a joystick. Pushing past
    // the threshold sends that direction once; holding it there repeats, like
    // holding an arrow; easing back to the middle stops. A touch that never
    // moves is a tap, or a long press if held.
    val dot = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val gestureScope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnRepeat by rememberUpdatedState(onRepeat)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val gestureModifier = if (mode == "swipe" && enabled) {
        Modifier.pointerInput(Unit) {
            val threshold = 40.dp.toPx()
            val release = threshold * 0.6f
            val maxTravel = 76.dp.toPx()
            val slop = viewConfiguration.touchSlop
            val longPressMs = viewConfiguration.longPressTimeoutMillis

            fun directionOf(v: Offset, limit: Float): RemoteKey? = when {
                abs(v.x) < limit && abs(v.y) < limit -> null
                abs(v.x) > abs(v.y) -> if (v.x > 0) RemoteKey.RIGHT else RemoteKey.LEFT
                else -> if (v.y > 0) RemoteKey.DOWN else RemoteKey.UP
            }

            awaitEachGesture {
                val down = awaitFirstDown()
                var total = Offset.Zero
                var moved = false
                var longPressed = false
                var active: RemoteKey? = null
                var repeater: Job? = null
                val longPress = gestureScope.launch {
                    delay(longPressMs)
                    if (!moved) {
                        longPressed = true
                        currentOnLongPress(RemoteKey.SELECT)
                    }
                }
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    total += change.positionChange()
                    change.consume()
                    if (!moved && total.getDistance() > slop) {
                        moved = true
                        longPress.cancel()
                    }
                    // The dot tracks the finger, held within the pad.
                    val distance = total.getDistance()
                    val shown = if (distance > maxTravel) total * (maxTravel / distance) else total
                    gestureScope.launch { dot.snapTo(shown) }

                    // Keep the current direction until the finger eases back or clearly
                    // turns, so it does not flicker near a diagonal.
                    val held = active
                    val next = if (held != null && directionOf(total, release) == held) held else directionOf(total, threshold)
                    if (next != active) {
                        repeater?.cancel()
                        active = next
                        if (next != null) {
                            currentOnSwipe(next)
                            repeater = gestureScope.launch {
                                delay(400)
                                while (true) {
                                    currentOnRepeat(next)
                                    delay(110)
                                }
                            }
                        }
                    }
                }
                longPress.cancel()
                repeater?.cancel()
                gestureScope.launch {
                    dot.animateTo(Offset.Zero, com.remotecontrolplus.ui.theme.AtriumMotion.bouncy())
                }
                if (!moved && !longPressed) currentOnTap(RemoteKey.SELECT)
            }
        }
    } else {
        Modifier
    }

    if (mode == "dpad") {
        // Google Home's D-pad: one big ring with chevrons, and a large inner disc for OK.
        Box(
            modifier = modifier
                .glass(CircleShape),
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
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { onKey(RemoteKey.SELECT) },
                        onLongClick = { onLongPress(RemoteKey.SELECT) },
                        hapticFeedbackEnabled = false,
                    ),
            )
        }
    } else {
        // Google Home's swipe surface: a plain rounded square with a dot in the middle.
        Box(
            modifier = modifier
                .glass(RoundedCornerShape(28.dp))
                .then(gestureModifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(dot.value.x.roundToInt(), dot.value.y.roundToInt()) }
                    .drawBehind {
                        if (enabled) {
                            drawCircle(Color.White.copy(alpha = 0.04f), radius = size.minDimension / 2 + 30.dp.toPx())
                            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension / 2 + 14.dp.toPx())
                        }
                    }
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
            )
            if (showHint) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HintLine(Icons.Filled.Swipe, "Swipe to navigate, hold to keep moving")
                    HintLine(Icons.Filled.TouchApp, "Tap to select, hold for long press")
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
