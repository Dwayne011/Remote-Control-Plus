package com.remotecontrolplus.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Motion, from the Atrium animation pack (pixel-launcher-plus: docs/animation-spec.md, and
 * glance/.../ui/Motion.kt and Progress.kt at e24abcf), so this app moves the way the launcher
 * does. Four named springs for everything; springs for anything that can be interrupted; motion
 * follows the system animation setting, except progress, which runs on the frame clock so a
 * still bar never reads as a hang.
 */
object AtriumMotion {
    /** No bounce: pages, sheets, panels, tiles arriving, anything coming to rest. */
    fun <T> smooth(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 200f)

    /** A little bounce, quick: menus, pills, tabs, things popping out of a control. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.78f, stiffness = 380f)

    /** More bounce: something let go after being pulled. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.58f, stiffness = 170f)

    /** Following a finger, and things leaving: fast, almost no overshoot. */
    fun <T> interactive(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 900f)
}

/*
 * Page changes (spec 3.1): going deeper, the new page comes in from 1/5 of the width on the right
 * as the old eases 1/8 to the left; going back is the mirror, so a page leaves the way it came.
 * Both fade. For NavHost's enter/exit/popEnter/popExit.
 */
fun <S> AnimatedContentTransitionScope<S>.pageEnter(): EnterTransition =
    slideInHorizontally(AtriumMotion.smooth()) { it / 5 } + fadeIn(AtriumMotion.smooth())

fun <S> AnimatedContentTransitionScope<S>.pageExit(): ExitTransition =
    slideOutHorizontally(AtriumMotion.interactive()) { -it / 8 } + fadeOut(AtriumMotion.interactive())

fun <S> AnimatedContentTransitionScope<S>.pagePopEnter(): EnterTransition =
    slideInHorizontally(AtriumMotion.smooth()) { -it / 5 } + fadeIn(AtriumMotion.smooth())

fun <S> AnimatedContentTransitionScope<S>.pagePopExit(): ExitTransition =
    slideOutHorizontally(AtriumMotion.interactive()) { it / 8 } + fadeOut(AtriumMotion.interactive())

/**
 * Content changing in place (spec 4.2): the new fades in and rises 1/24 of its height on
 * smooth; the old fades out on interactive.
 */
@Composable
fun <T> Swap(state: T, modifier: Modifier = Modifier, contentKey: (T) -> Any? = { it }, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = {
            (slideInVertically(AtriumMotion.smooth()) { h -> h / 24 } + fadeIn(AtriumMotion.smooth()))
                .togetherWith(fadeOut(AtriumMotion.interactive()))
                .using(SizeTransform(clip = false))
        },
        label = "swap",
    ) { shown -> content(shown) }
}

/**
 * A tile arriving (spec 4.1): fades in and rises 16 dp into place on smooth, 35 ms after the
 * one before (the first six; after that they start together). Plays when [key] first appears or
 * changes (a screen opening, another TV picked), not on every redraw.
 */
fun Modifier.arriving(index: Int, key: Any?): Modifier = composed {
    val shown = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(35L * index.coerceAtMost(6))
        shown.animateTo(1f, AtriumMotion.smooth())
    }
    graphicsLayer { alpha = shown.value.coerceIn(0f, 1f); translationY = (1f - shown.value) * 16.dp.toPx() }
}

/** A whole panel appearing (spec 3.3): fades in and rises [rise] into place on smooth, once. */
fun Modifier.entering(rise: Dp = 24.dp): Modifier = composed {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, AtriumMotion.smooth()) }
    graphicsLayer { alpha = shown.value.coerceIn(0f, 1f); translationY = (1f - shown.value) * rise.toPx() }
}

/** A small thing appearing (spec 4.3): fades in while growing from 85% to full size on snappy, once. */
fun Modifier.poppingIn(): Modifier = composed {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, AtriumMotion.snappy()) }
    graphicsLayer { alpha = shown.value.coerceIn(0f, 1f); val k = 0.85f + 0.15f * shown.value; scaleX = k; scaleY = k }
}

/**
 * A progress bar (spec 8), from the pack's MovingBar. With [fraction] it is filled that far
 * (amount of work known); without, a piece 35% of the bar slides left to right once every
 * 1.4 s. Runs on the screen's frame clock, so it keeps moving with animations turned off.
 */
@Composable
fun MovingBar(modifier: Modifier = Modifier, fraction: Float? = null, height: Dp = 4.dp, colour: Color = MaterialTheme.colorScheme.primary, track: Color = GlassFillStrong) {
    var phase by remember { mutableFloatStateOf(0f) }
    if (fraction == null) LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> phase = ((now - start) / 1_400_000_000f) % 1f }
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(track, size = size, cornerRadius = radius)
        if (fraction != null) {
            drawRoundRect(colour, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
        } else {
            val piece = size.width * 0.35f
            val left = -piece + (size.width + piece) * phase
            val from = left.coerceAtLeast(0f)
            val to = (left + piece).coerceAtMost(size.width)
            if (to > from) drawRoundRect(colour, topLeft = Offset(from, 0f), size = Size(to - from, size.height), cornerRadius = radius)
        }
    }
}

/**
 * No invisible loading: a bar with words saying what is happening. The words cross-fade on
 * smooth when they change.
 */
@Composable
fun WorkingLine(words: String, modifier: Modifier = Modifier, fraction: Float? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MovingBar(fraction = fraction)
        Swap(words) { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
