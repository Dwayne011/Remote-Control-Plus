package com.remotecontrolplus.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/*
 * The frosted look shared with At-a-Glance-Plus (its GlassUi.kt): a near-black
 * base, soft blurred pools of the wallpaper's Material You colours, a slight
 * dark veil, and translucent glass tiles with a thin light edge over it.
 */

val GlassBase = Color(0xFF0C1018)
val GlassText = Color(0xFFF1F3F6)
val GlassSub = Color(0xFFA7ADB6)
val GlassFill = Color.White.copy(alpha = 0.085f)
val GlassFillStrong = Color.White.copy(alpha = 0.14f)
val GlassEdge = Color.White.copy(alpha = 0.14f)
val GlassUp = Color(0xFF6FE08A)
val GlassDown = Color(0xFFFF8A80)

/** Opaque dark surfaces for things that float over other content: sheets, dialogs, menus. */
val GlassSheet = Color(0xFF141925)
val GlassDialog = Color(0xFF1C2230)

val GlassShape = RoundedCornerShape(20.dp)

/** A glass tile: translucent fill, thin light edge. */
fun Modifier.glass(shape: Shape = GlassShape, fill: Color = GlassFill): Modifier =
    clip(shape).background(fill).border(1.dp, GlassEdge, shape)

private fun mix(a: Color, b: Color, f: Float): Color =
    Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)

/**
 * The backdrop behind a full screen: pools of colour from the wallpaper's
 * palette, blurred, with a dark veil so the glass tiles and text stay readable.
 */
@Composable
fun GlassBackdrop(content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colours = listOf(
        scheme.primary,
        mix(scheme.tertiary, scheme.primary, 0.25f),
        mix(scheme.secondary, scheme.tertiary, 0.35f),
        scheme.inversePrimary,
    )
    Box(Modifier.fillMaxSize().background(GlassBase)) {
        Canvas(Modifier.fillMaxSize().blur(48.dp)) {
            val spots = listOf(
                Offset(size.width * 0.15f, size.height * 0.06f) to size.width * 0.75f,
                Offset(size.width * 0.95f, size.height * 0.32f) to size.width * 0.7f,
                Offset(size.width * 0.3f, size.height * 0.95f) to size.width * 0.85f,
                Offset(size.width * 0.8f, size.height * 0.72f) to size.width * 0.45f,
            )
            spots.forEachIndexed { i, (centre, radius) ->
                drawCircle(
                    Brush.radialGradient(listOf(colours[i].copy(alpha = 0.55f), Color.Transparent), centre, radius),
                    radius,
                    centre,
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x590B0E14)))
        content()
    }
}
